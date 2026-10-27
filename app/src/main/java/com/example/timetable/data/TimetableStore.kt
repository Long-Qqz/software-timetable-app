package com.example.timetable.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 课表与设置的本地持久化（SharedPreferences + JSON）。
 * 云端 OCR 的百度 Key 只存本地，不上传。
 */
class TimetableStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("timetable", Context.MODE_PRIVATE)

    // ===== 课表 =====

    fun saveCourses(courses: List<Course>) {
        val arr = JSONArray()
        courses.forEach { c ->
            val sArr = JSONArray()
            c.sessions.forEach { s ->
                val so = JSONObject()
                so.put("weeks", s.weeks)
                so.put("start", s.start)
                so.put("end", s.end)
                so.put("makeup", s.makeup)
                if (s.note != null) so.put("note", s.note)
                sArr.put(so)
            }
            val o = JSONObject()
            o.put("name", c.name)
            o.put("day", c.day)
            if (c.classes != null) o.put("classes", c.classes)
            // 空值也写入空字符串而不是移除键：否则 org.json 会删除该键，
            // 加载时 getString 抛异常导致整份课表解析失败、被内置课表覆盖
            o.put("loc", c.loc ?: "")
            o.put("teacher", c.teacher ?: "")
            o.put("sessions", sArr)
            arr.put(o)
        }
        prefs.edit().putString("courses_json", arr.toString()).apply()
    }

    fun loadCourses(): List<Course> {
        val raw = prefs.getString("courses_json", null) ?: return emptyList()
        return parseCourses(raw)
    }

    /** 内置默认课表（assets/default_timetable.json），首次安装无课表时使用 */
    fun loadDefaultCourses(): List<Course> {
        // 1) 优先读 assets 打包文件
        try {
            val raw = context.assets.open("default_timetable.json").bufferedReader().use { it.readText() }
            val parsed = parseCourses(raw)
            if (parsed.isNotEmpty()) return parsed
        } catch (e: Exception) {
            // 忽略，走代码构建兜底
        }
        // 2) 代码直接构建兜底（不经 JSON 解析，任何情况下必然返回完整课表）
        return buildDefaultCourses()
    }

    /** 代码直接构建内置课表：与网页版（软件开发方向课表全屏版.html）17 门课完全一致，含调课标记 */
    private fun buildDefaultCourses(): List<Course> = listOf(
        Course("Web服务器端开发", 1, "1、2班", "B2-410 区块链与信息安全实验室", "金刚波", listOf(
            Session("1-4,6-14,17-18", 1, 4)
        )),
        Course("软件工程综合实践", 1, "1班", "B2-422 软件技术实验室", "胡绵发", listOf(
            Session("15-16", 1, 4)
        )),
        Course("Python程序设计", 1, "1班", "B2-409 智能硬件与物联网实验室", "刘欣", listOf(
            Session("1-3", 6, 9),
            Session("4-6双,7-14,17-18", 6, 8)
        )),
        Course("软件工程综合实践", 1, "1班", "B2-422 软件技术实验室", "胡绵发", listOf(
            Session("16", 6, 9)
        )),
        Course("智能终端应用软件开发", 2, "1、2班", "B2-417 程序设计基础实验室", "徐志强", listOf(
            Session("11-14,17-18", 1, 4),
            Session("1-4,6-7,9-10", 1, 5)
        )),
        Course("软件工程综合实践", 2, "1班", "B2-422 软件技术实验室", "胡绵发", listOf(
            Session("15-16", 1, 5)
        )),
        Course("软件体系结构与架构技术", 2, "1班", "B2-406 生成式AI创新实验室", "王亚洲", listOf(
            Session("1", 6, 9),
            Session("2-3,7", 6, 13),
            Session("4", 6, 9, note = "原周二晚10-13节已调至第8周周四下午"),
            Session("6", 6, 9, note = "原周二晚10-13节已调至第9周周三晚上")
        )),
        Course("界面设计", 2, "1班", "B2-406 生成式AI创新实验室", "沈伊诺", listOf(
            Session("9-14,17-18", 6, 11)
        )),
        Course("软件工程综合实践", 3, "1班", "B2-422 软件技术实验室", "胡绵发", listOf(
            Session("15-16", 1, 9)
        )),
        Course("软件体系结构与架构技术", 3, "1班", "B2-406 生成式AI创新实验室", "王亚洲", listOf(
            Session("1", 6, 9),
            Session("9", 10, 13, makeup = true, note = "由第6周周二晚调课补上")
        )),
        Course("Web服务器端开发", 3, "1、2班", "B2-410 区块链与信息安全实验室", "金刚波", listOf(
            Session("18", 10, 13)
        )),
        Course("软件体系结构与架构技术", 4, "1班", "B2-406 生成式AI创新实验室", "王亚洲", listOf(
            Session("8", 6, 9, makeup = true, note = "由第4周周二晚调课补上")
        )),
        Course("形势与政策", 4, "1、2班", "B5-1-1楼102合班教室", "向丽丽", listOf(
            Session("9-13单,17", 6, 7)
        )),
        Course("软件工程综合实践", 4, "1班", "B2-422 软件技术实验室", "胡绵发", listOf(
            Session("15-16", 6, 9)
        )),
        Course("软件工程综合实践", 5, "1班", "B2-422 软件技术实验室", "胡绵发", listOf(
            Session("16", 1, 4)
        )),
        Course("软件质量保证与测试", 5, "1班", "B2-406 生成式AI创新实验室", "赵煦华", listOf(
            Session("1-2,5-11", 6, 9),
            Session("12-14,18", 6, 8)
        )),
        Course("软件工程综合实践", 5, "1班", "B2-422 软件技术实验室", "胡绵发", listOf(
            Session("15-16", 6, 9)
        ))
    )

    private fun parseCourses(raw: String): List<Course> {
        return try {
            val arr = JSONArray(raw)
            // 逐条容错：某一条课程数据异常只跳过该条，绝不让整份课表解析失败（否则会被内置课表覆盖）
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val sArr = o.optJSONArray("sessions") ?: return@mapNotNull null
                    val sessions = (0 until sArr.length()).mapNotNull { j ->
                        try {
                            val so = sArr.getJSONObject(j)
                            Session(
                                weeks = so.getString("weeks"),
                                start = so.getInt("start"),
                                end = so.getInt("end"),
                                makeup = so.optBoolean("makeup", false),
                                note = so.optString("note")
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    Course(
                        name = o.optString("name", "").ifBlank { return@mapNotNull null },
                        day = o.optInt("day", 1),
                        classes = o.optString("classes").takeIf { it.isNotBlank() },
                        loc = o.optString("loc"),
                        teacher = o.optString("teacher"),
                        sessions = sessions
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===== 设置 =====

    /** 第1周周一日期，默认 2026-09-07 */
    fun termStart(): Calendar {
        val y = prefs.getInt("term_y", 2026)
        val m = prefs.getInt("term_m", 8)  // 0-based，8 = 9月
        val d = prefs.getInt("term_d", 7)
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, m)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }

    fun saveTermStart(y: Int, month0: Int, d: Int) {
        prefs.edit().putInt("term_y", y).putInt("term_m", month0).putInt("term_d", d).apply()
    }

    fun baiduApiKey(): String = prefs.getString("baidu_ak", "") ?: ""
    fun baiduSecretKey(): String = prefs.getString("baidu_sk", "") ?: ""
    fun saveBaiduKeys(ak: String, sk: String) {
        prefs.edit().putString("baidu_ak", ak.trim()).putString("baidu_sk", sk.trim()).apply()
    }

    // ===== OCR 引擎选择与自定义 API 配置 =====
    // ocr_mode: offline(默认，离线 ML Kit) / baidu / custom
    fun ocrMode(): String = prefs.getString("ocr_mode", "offline") ?: "offline"
    fun saveOcrMode(mode: String) {
        prefs.edit().putString("ocr_mode", mode).apply()
    }

    fun ocrCustomUrl(): String = prefs.getString("ocr_custom_url", "") ?: ""
    fun saveOcrCustomUrl(v: String) {
        prefs.edit().putString("ocr_custom_url", v.trim()).apply()
    }

    fun ocrCustomToken(): String = prefs.getString("ocr_custom_token", "") ?: ""
    fun saveOcrCustomToken(v: String) {
        prefs.edit().putString("ocr_custom_token", v.trim()).apply()
    }

    fun ocrCustomImageField(): String = prefs.getString("ocr_custom_image_field", "image") ?: "image"
    fun saveOcrCustomImageField(v: String) {
        prefs.edit().putString("ocr_custom_image_field", v.trim().ifEmpty { "image" }).apply()
    }

    fun ocrCustomHeaders(): String = prefs.getString("ocr_custom_headers", "") ?: ""
    fun saveOcrCustomHeaders(v: String) {
        prefs.edit().putString("ocr_custom_headers", v.trim()).apply()
    }

    /** 上一次识别时用户选择的专业方向 */
    fun lastMajor(): String = prefs.getString("last_major", "") ?: ""
    fun saveLastMajor(major: String) {
        prefs.edit().putString("last_major", major).apply()
    }
}
