package com.example.timetable.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.timetable.data.Course
import com.example.timetable.data.Session
import com.example.timetable.data.Timetable
import com.example.timetable.data.TimetableStore
import com.example.timetable.R
import com.example.timetable.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: TimetableStore

    private var week = 1
    private var courses: List<Course> = emptyList()
    private var editMode = false

    /** 编辑模式草稿：所有编辑操作先改内存草稿，点「保存修改」才写回存储 */
    private var editDraft: MutableList<Course>? = null

    private val REQ_EDIT_COURSE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = TimetableStore(this)

        binding.btnImport.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        binding.btnTimetables.setOnClickListener { showTimetableManager() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnEdit.setOnClickListener {
            if (editMode) {
                val draft = editDraft
                // 有未保存修改时点「完成」：先确认，防止误点丢失本次改动
                if (draft != null && draft != courses) {
                    AlertDialog.Builder(this)
                        .setTitle("有未保存的修改")
                        .setMessage("课表还有未保存的改动，退出会丢失。要保存后退出吗？")
                        .setPositiveButton("保存并退出") { _, _ -> exitEditMode(true) }
                        .setNegativeButton("不保存退出") { _, _ -> exitEditMode(false) }
                        .setNeutralButton("取消", null)
                        .show()
                } else {
                    exitEditMode(commit = false)
                }
            } else {
                enterEditMode()
            }
        }
        binding.btnSaveEdit.setOnClickListener { exitEditMode(commit = true) }
        binding.btnDiscardEdit.setOnClickListener { exitEditMode(commit = false) }

        binding.btnPrev.setOnClickListener {
            if (week > 1) { week--; render() }
        }
        binding.btnNext.setOnClickListener {
            if (week < Timetable.TOTAL_WEEKS) { week++; render() }
        }
        binding.btnToday.setOnClickListener {
            week = Timetable.currentWeek(store.termStart())
            render()
        }
        binding.weekLabel.setOnClickListener { showWeekPicker() }

        // 速览卡片：点击标题行折叠/展开
        binding.panelHeader.setOnClickListener { toggleOverview() }

        binding.timetable.onCourseClick = { course, sess ->
            openCourseEdit(course, sess)
        }
        binding.timetable.onDropAdd = { name, day, start, end ->
            lastDroppedName = name
            showDropDialog(day, start, end)
        }
        binding.timetable.onDropZero = { name, day ->
            removeDroppedCourse(name, day)
        }
        binding.timetable.onDropSession = { name, day, weeks, start, end ->
            removeSession(name, day, weeks, start, end)
        }
        binding.timetable.onResizeCommit = { course, sess, newStart, newEnd ->
            commitResize(course, sess, newStart, newEnd)
        }
        binding.btnNewCourse.setOnClickListener {
            openCourseEdit(null, null)
        }
        binding.btnLoadDefault.setOnClickListener {
            val defaults = store.loadDefaultCourses()
            if (defaults.isNotEmpty()) {
                store.saveCourses(defaults)
                courses = defaults
                render()
                rebuildCourseList()
                Toast.makeText(this, "已加载内置课表（软件开发方向，共 ${defaults.map { it.name }.distinct().size} 门课）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "内置课表加载失败", Toast.LENGTH_SHORT).show()
            }
        }

        loadAndRender()
    }

    override fun onResume() {
        super.onResume()
        // 导入/设置/编辑返回后刷新；编辑模式下保留草稿，不重载存储
        if (::store.isInitialized && !editMode) {
            loadAndRender()
            rebuildCourseList()
        }
    }

    private fun loadAndRender() {
        courses = store.loadCourses()
        // 仅首次安装（第一个课表 default 仍为空）时加载内置课表；
        // 用户新建的空课表保持空白，不触发内置
        if (courses.isEmpty() && store.currentTimetableId() == "default") {
            val defaults = store.loadDefaultCourses()
            if (defaults.isNotEmpty()) {
                store.saveCourses(defaults)
                courses = defaults
            }
        }
        week = Timetable.currentWeek(store.termStart())
        render()
        binding.tvTitle.text = store.currentTimetableName()
    }

    private fun render() {
        val data = editDraft ?: courses
        binding.weekLabel.text = weekLabelText(week)
        binding.emptyView.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
        binding.timetable.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        binding.timetable.setData(data, week, store.termStart())
        renderWeekPanel()
    }

    // ===== 编辑模式 =====

    private fun enterEditMode() {
        if (courses.isEmpty()) {
            Toast.makeText(this, "请先加载课表（可点「加载内置课表」）", Toast.LENGTH_SHORT).show()
            return
        }
        editDraft = courses.toMutableList()
        editMode = true
        binding.btnEdit.text = "完成"
        binding.btnEdit.setTextColor(0xFFFFD54F.toInt())
        binding.editPanel.visibility = View.VISIBLE
        binding.timetable.editMode = true
        // 进入编辑模式：速览默认折叠，给课表腾出空间
        overviewCollapsed = true
        applyOverviewState()
        rebuildCourseList()
        render()
    }

    private fun exitEditMode(commit: Boolean) {
        val draft = editDraft
        if (commit && draft != null) {
            courses = draft.toList()
            store.saveCourses(courses)
            Toast.makeText(this, "已保存修改", Toast.LENGTH_SHORT).show()
        } else if (commit) {
            Toast.makeText(this, "没有可保存的修改", Toast.LENGTH_SHORT).show()
        }
        editDraft = null
        editMode = false
        binding.btnEdit.text = "编辑"
        binding.btnEdit.setTextColor(0xFFFFFFFF.toInt())
        binding.editPanel.visibility = View.GONE
        binding.timetable.editMode = false
        overviewCollapsed = false
        applyOverviewState()
        render()
    }

    // ===== 速览折叠 =====

    private var overviewCollapsed = false

    private fun toggleOverview() {
        overviewCollapsed = !overviewCollapsed
        applyOverviewState()
    }

    private fun applyOverviewState() {
        binding.weekPanel.visibility = if (overviewCollapsed) View.GONE else View.VISIBLE
        binding.panelArrow.text = if (overviewCollapsed) "▸" else "▾"
    }

    /** 课程拖拽列表：去重后的课程名，长按拖到课表 */
    private fun rebuildCourseList() {
        binding.courseList.removeAllViews()
        val src = editDraft ?: courses
        val names = src.map { it.name }.distinct()
        if (names.isEmpty()) {
            val tv = TextView(this).apply {
                text = "暂无课程"
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
            }
            binding.courseList.addView(tv)
            return
        }
        names.forEach { name ->
            val proto = src.first { it.name == name }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = resources.getDrawable(R.drawable.bg_chip, theme)
                setOnLongClickListener {
                    val data = ClipData.newPlainText("course", name)
                    startDragAndDrop(data, View.DragShadowBuilder(it), it, 0)
                    true
                }
            }
            val dot = TextView(this).apply {
                text = "●"
                textSize = 10f
                setTextColor(Timetable.colorOf(name))
                setPadding(0, 0, dp(8), 0)
            }
            val nameTv = TextView(this).apply {
                text = name
                setTextColor(0xFF0F172A.toInt())
                textSize = 13.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val meta = TextView(this).apply {
                text = " ${proto.teacher} · ${roomCodeOf(proto.loc)}"
                setTextColor(0xFF64748B.toInt())
                textSize = 11f
                setPadding(dp(6), 0, 0, 0)
            }
            row.addView(dot)
            row.addView(nameTv)
            row.addView(meta)
            binding.courseList.addView(row)
        }
    }

    /** 拖入课表后的周次/节次确认弹窗 */
    private fun showDropDialog(day: Int, start: Int, end: Int) {
        val etWeeks = EditText(this).apply {
            setText("1-18")
            hint = "如 1-18 / 4,6 / 9-13单"
        }
        val etStart = EditText(this).apply {
            setText("$start")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etEnd = EditText(this).apply {
            setText("$end")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        form.addView(fieldRow("周次", etWeeks))
        form.addView(fieldRow("开始节次", etStart))
        form.addView(fieldRow("结束节次", etEnd))

        AlertDialog.Builder(this)
            .setTitle("添加课程 · ${Timetable.DAY_NAMES[day - 1]}")
            .setMessage("拖入的课程：${lastDroppedName ?: ""}\n确认周次与节次：")
            .setView(form)
            .setPositiveButton("添加") { _, _ ->
                val weeks = etWeeks.text.toString().trim()
                val s = etStart.text.toString().toIntOrNull()
                val e = etEnd.text.toString().toIntOrNull()
                if (weeks.isEmpty() || s == null || e == null || e < s) {
                    Toast.makeText(this, "周次或节次不合法", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addDroppedCourse(day, s, e, weeks)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fieldRow(label: String, et: EditText): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val lv = TextView(this).apply {
            text = label
            setTextColor(0xFF0F172A.toInt())
            textSize = 14f
            setPadding(0, 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(lv)
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** 把拖入的课程写入草稿：同名同天课程追加 Session，否则新建课程（点保存才生效） */
    private fun addDroppedCourse(day: Int, start: Int, end: Int, weeks: String) {
        val name = lastDroppedName ?: return
        lastDroppedName = null
        val draft = editDraft ?: return
        val proto = draft.firstOrNull { it.name == name }
        val loc = proto?.loc ?: ""
        val teacher = proto?.teacher ?: "未知教师"
        val classes = proto?.classes

        // 防御：节次必须 1..13 且 start<=end，杜绝 0 节/倒序
        val ns = start.coerceIn(1, 13)
        val ne = end.coerceIn(ns, 13)
        val idx = draft.indexOfFirst { it.name == name && it.day == day }
        val newSess = Session(weeks, ns, ne)
        if (idx >= 0) {
            val old = draft[idx]
            val exists = old.sessions.any { it.weeks == weeks }
            val newSessions = if (exists) {
                old.sessions.map { if (it.weeks == weeks) newSess else it }
            } else old.sessions + newSess
            draft[idx] = old.copy(sessions = newSessions)
        } else {
            draft.add(Course(name, day, classes, loc, teacher, listOf(newSess)))
        }
        render()
        rebuildCourseList()
        Toast.makeText(this, "已添加（待保存）：${Timetable.DAY_NAMES[day - 1]} $ns-$ne 节", Toast.LENGTH_SHORT).show()
    }

    /** 拖入 0 节槽：该天不上该课，从草稿移除该课程当天记录（点保存才生效） */
    private fun removeDroppedCourse(name: String, day: Int) {
        val draft = editDraft ?: return
        val idx = draft.indexOfFirst { it.name == name && it.day == day }
        if (idx < 0) {
            Toast.makeText(this, "${Timetable.DAY_NAMES[day - 1]} 没有这门课，无需移除", Toast.LENGTH_SHORT).show()
            return
        }
        draft.removeAt(idx)
        render()
        rebuildCourseList()
        Toast.makeText(this, "已设为0节（待保存）：${Timetable.DAY_NAMES[day - 1]} 不上「$name」", Toast.LENGTH_SHORT).show()
    }

    /** 长按卡片拖到 0 节槽：删除当前周的这节课，点保存才生效（只影响本周，其余周保留） */
    private fun removeSession(name: String, day: Int, weeks: String, start: Int, end: Int) {
        val draft = editDraft ?: return
        val idx = draft.indexOfFirst { it.name == name && it.day == day }
        if (idx < 0) {
            Toast.makeText(this, "没找到这门课，无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        val c = draft[idx]
        // 只从周次中移除当前周；移空则整个删除
        val newWeeks = Timetable.weeksWithout(weeks, week)
        val remain = c.sessions.map { s ->
            if (s.weeks == weeks && s.start == start && s.end == end) {
                if (newWeeks.isEmpty()) null else s.copy(weeks = newWeeks)
            } else s
        }.filterNotNull()
        if (remain.isEmpty()) draft.removeAt(idx) else draft[idx] = c.copy(sessions = remain)
        render()
        rebuildCourseList()
        if (newWeeks.isEmpty()) {
            Toast.makeText(this, "本周已删除，且无剩余周次，整课移除（待保存）", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已删除本周的这节课，其余周保留（待保存）：${Timetable.DAY_NAMES[day - 1]}「$name」", Toast.LENGTH_SHORT).show()
        }
    }

    /** 记录最近一次拖入的课程名（onDropAdd 弹窗确认前暂存） */
    private var lastDroppedName: String? = null

    /** 卡片上下沿拖拽调整节次完成：只改草稿，点保存才生效 */
    private fun commitResize(course: Course, sess: Session, newStart: Int, newEnd: Int) {
        // 防御：节次必须 1..13 且 start<=end，杜绝 0 节/倒序
        val ns = newStart.coerceIn(1, 13)
        val ne = newEnd.coerceIn(ns, 13)
        if (ns == sess.start && ne == sess.end) return
        val draft = editDraft ?: return
        var changed = false
        for (i in draft.indices) {
            val c = draft[i]
            if (c.name != course.name || c.day != course.day) continue
            val ns2 = c.sessions.map { s ->
                if (s.weeks == sess.weeks && s.start == sess.start && s.end == sess.end) {
                    changed = true
                    s.copy(start = ns, end = ne)
                } else s
            }
            draft[i] = c.copy(sessions = ns2)
        }
        if (changed) {
            render()
            Toast.makeText(this, "已调整为 $ns-$ne 节（待保存）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun weekLabelText(w: Int): String {
        val monday = Timetable.mondayOfWeek(store.termStart(), w)
        val fmt = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
        return "第 $w 周（${fmt.format(monday.time)}起）${if (w == Timetable.currentWeek(store.termStart())) "·本周" else ""}"
    }

    private fun renderWeekPanel() {
        val data = editDraft ?: courses
        binding.panelTitle.text = "本周课程速览 · 第 $week 周"
        binding.weekPanel.removeAllViews()
        for (d in 1..5) {
            val list = Timetable.coursesOfDay(data, week, d)
            if (list.isEmpty()) continue
            val head = TextView(this).apply {
                text = "${Timetable.DAY_NAMES[d - 1]}（${list.size}门）"
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(0), dp(6), dp(0), dp(2))
            }
            binding.weekPanel.addView(head)
            for ((course, sess) in list) {
                binding.weekPanel.addView(weekItemView(course, sess))
            }
        }
        if (binding.weekPanel.childCount == 0) {
            val empty = TextView(this).apply {
                text = "本周无课"
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
                setPadding(dp(0), dp(6), dp(0), dp(0))
            }
            binding.weekPanel.addView(empty)
        }
    }

    private fun weekItemView(course: Course, sess: Session): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(0), dp(3), dp(0), dp(3))
        }
        val dot = TextView(this).apply {
            text = "●"
            textSize = 9f
            setTextColor(Timetable.colorOf(course.name))
            setPadding(dp(0), dp(0), dp(7), dp(0))
        }
        val name = TextView(this).apply {
            text = course.name
            setTextColor(0xFF0F172A.toInt())
            textSize = 13f
        }
        val meta = TextView(this).apply {
            text = buildString {
                append("${sess.start}-${sess.end}节")
                append(" · ${roomCodeOf(course.loc)}")
                if (sess.makeup) append(" · 调课")
            }
            setTextColor(0xFF64748B.toInt())
            textSize = 11.5f
            setPadding(dp(8), dp(0), dp(0), dp(0))
        }
        row.addView(dot)
        row.addView(name)
        row.addView(meta)
        return row
    }

    private fun roomCodeOf(loc: String): String {
        val parts = loc.split(' ')
        val code = if (parts.size > 1) parts[0] else loc.replace(Regex("合班教室$"), "").trim()
        // 统一教室号格式：B5-1-1楼102 / B5-1-102 这类 OCR 怪格式 → B5-102（楼栋-楼层-房间）
        val m = Regex("^([A-Za-z]\\d+)-(\\d+)-?(\\d*)楼?(\\d{3})$").find(code)
        return if (m != null) m.groupValues[1] + "-" + m.groupValues[4] else code
    }

    private fun showWeekPicker() {
        val options = (1..Timetable.TOTAL_WEEKS).map { "第 $it 周" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择周次")
            .setSingleChoiceItems(options, week - 1) { d, which ->
                week = which + 1
                d.dismiss()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openCourseEdit(course: Course?, sess: Session?) {
        val intent = Intent(this, CourseEditActivity::class.java)
        intent.putExtra("course_json", courseToJson(course, sess))
        // 编辑模式：返回结果由 MainActivity 更新草稿；非编辑模式：直接写存储
        intent.putExtra("commit_store", !editMode)
        intent.putExtra("current_week", week)
        startActivityForResult(intent, REQ_EDIT_COURSE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EDIT_COURSE || resultCode != RESULT_OK || data == null) return
        val raw = data.getStringExtra("course_json") ?: return
        val draft = editDraft ?: return
        try {
            val o = org.json.JSONObject(raw)
            val oldName = o.optString("old_name")
            val oldDay = o.optInt("old_day", 1)
            val oldWeeks = o.optString("old_weeks")
            val oldStart = o.optInt("old_start", 1)
            val oldEnd = o.optInt("old_end", 1)

            // 编辑页点了「删除本节课」：只从草稿移除当前周的这节课（点保存才生效，其余周保留）
            if (o.optBoolean("delete_session", false)) {
                val idx = draft.indexOfFirst { it.name == oldName && it.day == oldDay }
                if (idx >= 0) {
                    val c = draft[idx]
                    val newWeeks = Timetable.weeksWithout(oldWeeks, week)
                    val remain = c.sessions.map { s ->
                        if (s.weeks == oldWeeks && s.start == oldStart && s.end == oldEnd) {
                            if (newWeeks.isEmpty()) null else s.copy(weeks = newWeeks)
                        } else s
                    }.filterNotNull()
                    if (remain.isEmpty()) draft.removeAt(idx) else draft[idx] = c.copy(sessions = remain)
                }
                render()
                rebuildCourseList()
                Toast.makeText(this, "已删除本周的这节课，其余周保留（待保存）", Toast.LENGTH_SHORT).show()
                return
            }
            val idx = draft.indexOfFirst { it.name == oldName && it.day == oldDay }
            val newSess = Session(o.optString("weeks"), o.optInt("start", 1), o.optInt("end", 1))
            if (idx >= 0) {
                val old = draft[idx]
                val newSessions = old.sessions.map { s ->
                    if (s.weeks == oldWeeks && s.start == oldStart && s.end == oldEnd) {
                        Session(newSess.weeks, newSess.start, newSess.end, s.makeup, s.note)
                    } else s
                }
                draft[idx] = old.copy(
                    name = o.optString("name"), day = o.optInt("day", oldDay),
                    loc = o.optString("loc"), teacher = o.optString("teacher"),
                    sessions = newSessions
                )
            } else {
                draft.add(
                    Course(
                        name = o.optString("name"), day = o.optInt("day", oldDay),
                        loc = o.optString("loc"), teacher = o.optString("teacher"),
                        sessions = listOf(newSess)
                    )
                )
            }
            render()
            rebuildCourseList()
        } catch (e: Exception) {
            // 解析失败静默，不破坏草稿
        }
    }

    private fun courseToJson(course: Course?, sess: Session?): String {
        val o = org.json.JSONObject()
        if (course != null && sess != null) {
            o.put("name", course.name)
            o.put("day", course.day)
            o.put("loc", course.loc)
            o.put("teacher", course.teacher)
            o.put("weeks", sess.weeks)
            o.put("start", sess.start)
            o.put("end", sess.end)
        }
        return o.toString()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ===== 课表管理：切换 / 新建 / 删除 =====

    private fun showTimetableManager() {
        if (editMode) {
            Toast.makeText(this, "请先退出编辑模式再管理课表", Toast.LENGTH_SHORT).show()
            return
        }
        refreshTimetableDialog()
    }

    private fun refreshTimetableDialog() {
        val list = store.listTimetables()
        val cur = store.currentTimetableId()
        val ids = list.map { it.first }
        val labels = list.map { it.second }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("课表管理（当前：${store.currentTimetableName()}）")
            .setSingleChoiceItems(labels, ids.indexOf(cur).coerceAtLeast(0)) { d, which ->
                store.switchTimetable(ids[which])
                d.dismiss()
                loadAndRender()
                rebuildCourseList()
            }
            .setNeutralButton("新建空课表") { _, _ -> showCreateTimetableDialog() }
            .setPositiveButton("删除当前课表") { _, _ -> showDeleteTimetableDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreateTimetableDialog() {
        val et = EditText(this).apply {
            hint = "课表名称，如「我的新课表」"
            setText("课表 ${store.listTimetables().size + 1}")
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("新建空课表")
            .setMessage("新课表从空白开始，与现有课表相互独立")
            .setView(et)
            .setPositiveButton("创建") { _, _ ->
                val name = et.text.toString().trim().ifBlank { "新课表" }
                store.createTimetable(name)
                Toast.makeText(this, "已新建并切换到「$name」", Toast.LENGTH_SHORT).show()
                loadAndRender()
                rebuildCourseList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteTimetableDialog() {
        val id = store.currentTimetableId()
        val name = store.currentTimetableName()
        if (store.listTimetables().size <= 1) {
            Toast.makeText(this, "至少保留一个课表，不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除课表「$name」？")
            .setMessage("删除后该课表的全部课程将丢失，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                store.deleteTimetable(id)
                Toast.makeText(this, "已删除并切换到「${store.currentTimetableName()}」", Toast.LENGTH_SHORT).show()
                loadAndRender()
                rebuildCourseList()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
