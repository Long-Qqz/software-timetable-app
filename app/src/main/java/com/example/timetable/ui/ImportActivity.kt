package com.example.timetable.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.timetable.data.Course
import com.example.timetable.data.Timetable
import com.example.timetable.data.TimetableStore
import com.example.timetable.databinding.ActivityImportBinding
import com.example.timetable.databinding.DialogCourseEditBinding
import com.example.timetable.databinding.ItemCourseFixBinding
import com.example.timetable.ocr.BaiduEngine
import com.example.timetable.ocr.LayoutAnalyzer
import com.example.timetable.ocr.LayoutAnalyzer.ParsedCourse
import com.example.timetable.ocr.LlmOcrEngine
import com.example.timetable.ocr.MlKitEngine
import com.example.timetable.ocr.OcrConfigException
import com.example.timetable.ocr.OcrEngine
import com.example.timetable.ocr.OcrRemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入课表：选图 → 选 OCR 引擎 → 识别 → 版面分析 → 专业选择 → 校正 → 生成课表。
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var store: TimetableStore

    private var bitmap: Bitmap? = null
    private var parsed: MutableList<ParsedCourse> = mutableListOf()
    private var majors: List<String> = emptyList()
    private var selectedMajor: String? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }

    private val adapter by lazy { FixAdapter(parsed) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = TimetableStore(this)

        binding.btnPick.setOnClickListener {
            pickImage.launch("image/*")
        }
        binding.btnRecognize.setOnClickListener {
            val bmp = bitmap
            if (bmp == null) { Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            recognize(bmp)
        }
        binding.btnGenerate.setOnClickListener { generate() }

        // 恢复上次使用的识别引擎
        when (store.ocrMode()) {
            "baidu" -> binding.rbCloud.isChecked = true
            "llm" -> binding.rbCustom.isChecked = true
            else -> binding.rbOffline.isChecked = true
        }

        binding.rvCourses.layoutManager = LinearLayoutManager(this)
        binding.rvCourses.adapter = adapter
    }

    private fun loadImage(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeSampled(uri) }
            if (bmp == null) {
                Toast.makeText(this@ImportActivity, "图片读取失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            bitmap = bmp
            binding.imgPreview.setImageBitmap(bmp)
            binding.btnRecognize.isEnabled = true
        }
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        return try {
            val resolver = contentResolver
            // 先读尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val maxSide = 1600
            val bigSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (bigSide / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp: Bitmap? = null
            resolver.openInputStream(uri)?.use { bmp = BitmapFactory.decodeStream(it, null, opts) }
            bmp
        } catch (e: Exception) { null }
    }

    private fun recognize(bmp: Bitmap) {
        store.saveOcrMode(
            when {
                binding.rbCustom.isChecked -> "llm"
                binding.rbCloud.isChecked -> "baidu"
                else -> "offline"
            }
        )
        val isLlm = binding.rbCustom.isChecked
        val engine: OcrEngine = when {
            isLlm -> LlmOcrEngine(
                store.llmPlatform(), store.llmBaseUrl(),
                store.llmApiKey(), store.llmModel(), store.llmPrompt()
            )
            binding.rbCloud.isChecked -> BaiduEngine(store.baiduApiKey(), store.baiduSecretKey())
            else -> MlKitEngine()
        }

        binding.progress.isVisible = true
        binding.btnRecognize.isEnabled = false
        binding.btnGenerate.isEnabled = false
        binding.rgEngine.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { engine.recognize(bmp) }
                if (isLlm) {
                    // 大模型直接返回结构化课程 JSON（每行一个对象），不走坐标版面分析
                    val courses = result.lines.mapNotNull { LayoutAnalyzer.parseCourseFromJson(it.text) }
                    parsed.clear()
                    parsed.addAll(courses)
                    majors = LayoutAnalyzer.extractMajorsFromJson(parsed)
                } else {
                    val analysis = withContext(Dispatchers.Default) { LayoutAnalyzer.analyze(result) }
                    parsed.clear()
                    parsed.addAll(analysis.courses)
                    majors = analysis.majors
                }
                showAnalysis()
            } catch (e: OcrConfigException) {
                Toast.makeText(this@ImportActivity, e.message ?: "识别引擎未配置", Toast.LENGTH_LONG).show()
            } catch (e: OcrRemoteException) {
                Toast.makeText(this@ImportActivity, e.message ?: "云端识别失败", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ImportActivity, "识别失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progress.isVisible = false
                binding.btnRecognize.isEnabled = bitmap != null
                binding.rgEngine.isEnabled = true
                binding.btnGenerate.isEnabled = parsed.isNotEmpty()
            }
        }
    }

    private fun showAnalysis() {
        // 专业选择
        binding.rgMajor.removeAllViews()
        if (majors.size > 1) {
            binding.tvMajor.text = "识别到 ${majors.size} 个专业方向，请选择你的专业："
            binding.tvMajor.isVisible = true
            binding.rgMajor.isVisible = true
            majors.forEach { m ->
                val rb = RadioButton(this).apply { text = m }
                rb.setOnCheckedChangeListener { _, checked -> if (checked) { selectedMajor = m; store.saveLastMajor(m) } }
                binding.rgMajor.addView(rb)
            }
            if (selectedMajor == null) selectedMajor = store.lastMajor().takeIf { it in majors }
            val pre = selectedMajor?.let { m -> majors.indexOf(m) } ?: 0
            (binding.rgMajor.getChildAt(pre) as? RadioButton)?.isChecked = true
        } else if (majors.size == 1) {
            selectedMajor = majors[0]
            binding.tvMajor.text = "识别到专业方向：${majors[0]}（将只保留该专业课程）"
            binding.tvMajor.isVisible = true
            binding.rgMajor.isVisible = false
        } else {
            selectedMajor = null
            binding.tvMajor.isVisible = false
            binding.rgMajor.isVisible = false
        }

        if (parsed.isEmpty()) {
            Toast.makeText(this, "未能识别出课程，请换更清晰的图片或换识别引擎", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "识别到 ${parsed.size} 门课程，可点击校正", Toast.LENGTH_SHORT).show()
        }
        adapter.notifyDataSetChanged()
        binding.btnGenerate.isEnabled = parsed.isNotEmpty()
    }

    /** 按专业过滤：保留公共课（无专业方向）与所选专业课程 */
    private fun filterByMajor(list: List<ParsedCourse>): List<Course> {
        val major = selectedMajor
        return if (major == null) {
            list.map { it.course }
        } else {
            list.filter { it.major == null || it.major == major }.map { it.course }
        }
    }

    private fun generate() {
        if (parsed.isEmpty()) { Toast.makeText(this, "没有可生成的课程", Toast.LENGTH_SHORT).show(); return }
        val courses = filterByMajor(parsed)
        if (courses.isEmpty()) { Toast.makeText(this, "所选专业没有课程", Toast.LENGTH_SHORT).show(); return }
        store.saveCourses(courses)
        Toast.makeText(this, "课表已生成（${courses.size} 门课）", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun showEditDialog(index: Int) {
        val item = parsed[index]
        val b = DialogCourseEditBinding.inflate(layoutInflater)
        b.spDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Timetable.DAY_NAMES)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spDay.setSelection(item.course.day.coerceIn(1, 5) - 1)
        b.etName.setText(item.course.name)
        b.etStart.setText(item.course.sessions.first().start.toString())
        b.etEnd.setText(item.course.sessions.first().end.toString())
        b.etWeeks.setText(item.course.sessions.first().weeks)
        b.etLoc.setText(item.course.loc)
        b.etTeacher.setText(item.course.teacher)

        AlertDialog.Builder(this)
            .setTitle("校正课程")
            .setView(b.root)
            .setPositiveButton("保存") { _, _ ->
                val name = b.etName.text.toString().trim()
                val weeks = b.etWeeks.text.toString().trim()
                val loc = b.etLoc.text.toString().trim()
                val teacher = b.etTeacher.text.toString().trim()
                val start = b.etStart.text.toString().toIntOrNull()
                val end = b.etEnd.text.toString().toIntOrNull()
                if (name.isEmpty() || start == null || end == null || weeks.isEmpty()) {
                    Toast.makeText(this, "请填写课程名、节次与周次", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val s = item.course.sessions.first()
                parsed[index] = ParsedCourse(
                    item.course.copy(
                        name = name,
                        day = b.spDay.selectedItemPosition + 1,
                        loc = loc,
                        teacher = teacher,
                        sessions = listOf(s.copy(weeks = weeks, start = start, end = end))
                    ),
                    item.major
                )
                adapter.notifyItemChanged(index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class FixAdapter(private val list: MutableList<ParsedCourse>) :
        RecyclerView.Adapter<FixAdapter.VH>() {

        inner class VH(val b: ItemCourseFixBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCourseFixBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val s = item.course.sessions.first()
            holder.b.tvName.text = item.course.name
            val meta = buildString {
                append("周${item.course.day} · ${s.start}-${s.end}节 · ${s.weeks}周")
                append(" · ${roomCode(item.course.loc)}")
                item.major?.let { append(" · $it") }
            }
            holder.b.tvMeta.text = meta
            holder.b.root.setOnClickListener { showEditDialog(position) }
            holder.b.btnRemove.setOnClickListener {
                list.removeAt(position)
                notifyItemRemoved(position)
                binding.btnGenerate.isEnabled = list.isNotEmpty()
            }
        }
    }

    private fun roomCode(loc: String): String {
        val parts = loc.split(' ')
        return if (parts.size > 1) parts[0] else loc.replace(Regex("合班教室$"), "")
    }
}
