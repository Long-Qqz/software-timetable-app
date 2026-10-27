package com.example.timetable.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.timetable.data.TimetableStore
import com.example.timetable.databinding.ActivitySettingsBinding

/**
 * 设置：学期开始日期（计算当前周）+ 百度云端 OCR Key + 大模型识别配置（仅存本地）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: TimetableStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = TimetableStore(this)

        val term = store.termStart()
        binding.etYear.setText(term.get(java.util.Calendar.YEAR).toString())
        binding.etMonth.setText((term.get(java.util.Calendar.MONTH) + 1).toString())
        binding.etDay.setText(term.get(java.util.Calendar.DAY_OF_MONTH).toString())
        binding.etAk.setText(store.baiduApiKey())
        binding.etSk.setText(store.baiduSecretKey())

        // 大模型识别配置
        when (store.llmPlatform()) {
            "gemini" -> binding.rbLlmGemini.isChecked = true
            else -> binding.rbLlmOpenai.isChecked = true
        }
        binding.etLlmBaseUrl.setText(store.llmBaseUrl())
        binding.etLlmApiKey.setText(store.llmApiKey())
        binding.etLlmModel.setText(store.llmModel())
        binding.etLlmPrompt.setText(store.llmPrompt())

        binding.btnSave.setOnClickListener {
            val y = binding.etYear.text.toString().toIntOrNull()
            val m = binding.etMonth.text.toString().toIntOrNull()
            val d = binding.etDay.text.toString().toIntOrNull()
            if (y != null && m != null && d != null) {
                store.saveTermStart(y, m - 1, d)
            } else {
                Toast.makeText(this, "学期日期格式不正确", Toast.LENGTH_SHORT).show()
            }
            store.saveBaiduKeys(binding.etAk.text.toString(), binding.etSk.text.toString())
            store.saveLlmPlatform(if (binding.rbLlmGemini.isChecked) "gemini" else "openai")
            store.saveLlmBaseUrl(binding.etLlmBaseUrl.text.toString())
            store.saveLlmApiKey(binding.etLlmApiKey.text.toString())
            store.saveLlmModel(binding.etLlmModel.text.toString())
            store.saveLlmPrompt(binding.etLlmPrompt.text.toString().trim())
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
