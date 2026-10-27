package com.example.timetable.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.timetable.data.TimetableStore
import com.example.timetable.databinding.ActivitySettingsBinding

/**
 * 设置：学期开始日期（计算当前周）+ 百度云端 OCR Key（可选，仅存本地）。
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
        binding.etCustomUrl.setText(store.ocrCustomUrl())
        binding.etCustomToken.setText(store.ocrCustomToken())
        binding.etCustomField.setText(store.ocrCustomImageField())
        binding.etCustomHeaders.setText(store.ocrCustomHeaders())

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
            store.saveOcrCustomUrl(binding.etCustomUrl.text.toString())
            store.saveOcrCustomToken(binding.etCustomToken.text.toString())
            store.saveOcrCustomImageField(binding.etCustomField.text.toString())
            store.saveOcrCustomHeaders(binding.etCustomHeaders.text.toString())
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
