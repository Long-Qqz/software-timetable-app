package com.example.timetable.ocr

import com.example.timetable.data.Course
import com.example.timetable.data.Session
import com.example.timetable.data.Timetable.TOTAL_WEEKS
import org.json.JSONObject

/**
 * 版面分析：把 OCR 行级结果重建为结构化课表。
 *
 * 假设课表格式与源图一致：最左为节次列(1-13)，右侧为周一至周五 5 列，
 * 表头含"星期一/周二/周一"等；课程单元格文本含课程名、节次周次、教室、教师、
 * 专业方向、教学班组成等字段。OCR 有误差，识别结果依赖后续手动校正兜底。
 */
object LayoutAnalyzer {

    data class ParsedCourse(
        val course: Course,
        val major: String?      // 该课程的专业方向，null = 公共课/未标注
    )

    data class ParseResult(
        val majors: List<String>,
        val courses: List<ParsedCourse>
    )

    fun analyze(result: OcrResult): ParseResult {
        val lines = result.lines

        // 1) 节次列：纯数字 1-13 的行
        val periodRows = lines
            .mapNotNull { l ->
                val n = l.text.trim().toIntOrNull()
                if (n != null && n in 1..13) l to n else null
            }
            .sortedBy { it.first.y }

        // 按 y 聚类去重（同数字多行取中心均值）
        val numToCenter = LinkedHashMap<Int, MutableList<Int>>()
        for ((l, n) in periodRows) {
            numToCenter.getOrPut(n) { mutableListOf() }.add(l.y + l.h / 2)
        }
        val centers = LinkedHashMap<Int, Int>()
        numToCenter.forEach { (n, ys) -> centers[n] = ys.average().toInt() }

        if (centers.size < 6) {
            throw IllegalStateException("未能识别节次列（仅识别到 ${centers.size} 个节次），请换清晰图片或换识别引擎")
        }

        // 2) 13 个节次行的中心：缺失的节次用相邻插值
        val sortedNums = centers.keys.sorted()
        // 简单线性插值帮助函数（局部函数须先声明后使用）
        fun centerOfBelow(below: Int, num: Int): Int {
            val lb = sortedNums.lastOrNull { it < below }
            if (lb == null) return centers[below]!! - 40 * (num - below)
            val step = (centers[below]!! - centers[lb]!!) / (below - lb)
            return centers[below]!! + step * (num - below)
        }
        fun centerOfAbove(above: Int, num: Int): Int {
            val la = sortedNums.firstOrNull { it > above }
            if (la == null) return centers[above]!! + 40 * (above - num)
            val step = (centers[la]!! - centers[above]!!) / (la - above)
            return centers[above]!! - step * (above - num)
        }
        fun centerOf(num: Int): Int {
            if (centers.containsKey(num)) return centers[num]!!
            val below = sortedNums.lastOrNull { it < num }
            val above = sortedNums.firstOrNull { it > num }
            return when {
                below != null && above != null -> (centers[below]!! + centers[above]!!) / 2
                below != null -> centers[below]!! + (centers[below]!! - centerOfBelow(below, num))
                else -> centers[above]!! - (centerOfAbove(above!!, num) - centers[above]!!)
            }
        }

        val rowCenter = (1..13).associateWith { centerOf(it) }

        // 行区间
        fun rowRange(num: Int): Pair<Int, Int> {
            val top = if (num == 1) 0 else (rowCenter[num]!! + rowCenter[num - 1]!!) / 2
            val bottom = if (num == 13) result.imageHeight else (rowCenter[num]!! + rowCenter[num + 1]!!) / 2
            return top to bottom
        }

        // 3) 节次列右边界（用于天列起始）
        val periodRight = periodRows.map { it.first.x + it.first.w }.maxOrNull() ?: 0
        val dayLeft = periodRight + 4

        // 4) 天列边界：优先用表头"星期X/周一"，否则均分
        val headerLines = lines.filter {
            Regex("星期[一二三四五]|周[一二三四五]").containsMatchIn(it.text)
        }.sortedBy { it.x + it.w / 2 }

        val colBounds = IntArray(6)
        colBounds[0] = dayLeft
        if (headerLines.size >= 3) {
            val heads = headerLines.map { (it.x + it.w / 2) }.distinct().sorted()
            // 取 5 个表头中心（若多于 5 个取跨度最大的 5 个）
            val h5 = if (heads.size == 5) heads else pickFive(heads)
            for (i in 0 until 5) {
                val left = if (i == 0) dayLeft else (h5[i - 1] + h5[i]) / 2
                val right = if (i == 4) result.imageWidth else (h5[i] + h5[i + 1]) / 2
                colBounds[i + 1] = right
            }
        } else {
            val dayW = (result.imageWidth - dayLeft) / 5
            for (i in 0 until 5) colBounds[i + 1] = dayLeft + dayW * (i + 1)
        }

        fun colRange(day: Int): Pair<Int, Int> = colBounds[day] to colBounds[day + 1]

        // 5) 单元格文本
        fun cellLines(day: Int, num: Int): List<OcrLine> {
            val (rTop, rBottom) = rowRange(num)
            val (cLeft, cRight) = colRange(day)
            return lines.filter { l ->
                val cy = l.y + l.h / 2
                val cx = l.x + l.w / 2
                cy in rTop..rBottom && cx in cLeft..cRight
            }.sortedBy { it.y }
        }

        // 6) 每天列扫描：连续非空行块 → 一门课
        val parsed = mutableListOf<ParsedCourse>()
        for (day in 0 until 5) {
            var num = 1
            while (num <= 13) {
                val cell = cellLines(day, num)
                if (cell.isEmpty()) { num++; continue }
                // 找到块尾
                var end = num
                var linesInBlock = cell.toMutableList()
                while (end < 13 && cellLines(day, end + 1).isNotEmpty()) {
                    end++
                    linesInBlock.addAll(cellLines(day, end))
                }
                // 合并块文本（去重行）
                val text = linesInBlock.distinctBy { it.text + it.y }.sortedBy { it.y }
                    .joinToString("\n") { it.text }
                parseCourseFromCell(text, day + 1, num, end)?.let { parsed.add(it) }
                num = end + 1
            }
        }

        val majors = extractMajors(lines)
        return ParseResult(majors, parsed)
    }

    /** 从 5 个表头中心点较多时，取跨度最大且接近等距的 5 个 */
    private fun pickFive(points: List<Int>): List<Int> {
        if (points.size <= 5) return points
        // 简单策略：首、尾 + 中间等分 3 个
        val first = points.first()
        val last = points.last()
        val step = (last - first) / 4
        val picked = mutableListOf(first)
        for (i in 1..3) picked.add(first + step * i)
        picked.add(last)
        return picked
    }

    /** 解析单元格文本 → 课程 */
    fun parseCourseFromCell(text: String, day: Int, blockStart: Int, blockEnd: Int): ParsedCourse? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        var name = ""
        var loc = ""
        var teacher = ""
        var major: String? = null
        var classes: String? = null
        var weeks = ""
        val candidates = mutableListOf<String>()

        for (t in lines) {
            when {
                t.contains("专业方向") -> {
                    major = extractAfter(t, "专业方向")?.takeWhile { it != ':' && it != '：' }?.trim()
                }
                t.contains("教学班组成") -> {
                    classes = extractClasses(extractAfter(t, "教学班组成").orEmpty())
                }
                t.contains(Regex("\\d+\\s*-\\s*\\d+\\s*节")) -> {
                    // 节次信息，块范围已确定，忽略
                }
                t.contains(Regex("[\\d,，\\-]+(?:单|双)?\\s*周")) -> {
                    weeks = normalizeWeeks(t)
                }
                t.contains(Regex("[A-Za-z]?\\d+[\\-\\d]*楼?\\d+")) -> {
                    loc = t
                }
                t.length in 2..4 && t.all { it.isLetter() } && !t.contains(Regex("星期|周")) -> {
                    candidates.add(t)
                }
                else -> candidates.add(t)
            }
        }

        // 课程名：第一个含中文的行（去掉标记符号）
        name = lines.firstOrNull { it.contains(Regex("[\\u4e00-\\u9fa5]")) }
            ?.replace(Regex("[*$&#:：]"), "")
            ?.trim() ?: ""
        // 若名字为空但有候选（纯字母课程名等）
        if (name.isEmpty()) {
            name = candidates.firstOrNull() ?: return null
        }
        if (name.isEmpty()) return null

        // 教师：候选行中第一个不是课程名且不是节次/周次的
        teacher = candidates.firstOrNull { it != name && !it.contains(Regex("\\d")) } ?: ""

        if (weeks.isEmpty()) {
            // 兜底：从文本找纯数字段作为周次
            weeks = lines.firstOrNull { it.contains(Regex("\\d")) && !it.contains("节") }
                ?.replace(Regex("[^\\d,\\-，单双]"), "") ?: ""
        }

        val course = Course(
            name = name,
            day = day,
            classes = classes?.takeIf { it.isNotEmpty() },
            loc = loc.ifEmpty { "未知教室" },
            teacher = teacher.ifEmpty { "未知教师" },
            sessions = listOf(
                Session(
                    weeks = weeks.ifEmpty { "1-${TOTAL_WEEKS}" },
                    start = blockStart,
                    end = blockEnd
                )
            )
        )
        return ParsedCourse(course, major)
    }

    /** 提取"xxx"后的内容 */
    private fun extractAfter(line: String, key: String): String? {
        val idx = line.indexOf(key)
        if (idx < 0) return null
        return line.substring(idx + key.length).trim()
    }

    /** 从教学班组成文本提取班级，如 "1、2班" */
    private fun extractClasses(raw: String): String {
        val nums = Regex("(\\d+)班").findAll(raw).map { it.groupValues[1] }.toList().distinct()
        return if (nums.isEmpty()) "" else nums.joinToString("、") + "班"
    }

    /** 规范化周次文本 */
    private fun normalizeWeeks(t: String): String {
        val m = Regex("([\\d,，\\-]+(?:单|双)?)周").find(t)
        if (m != null) return m.groupValues[1].replace('，', ',')
        val m2 = Regex("([\\d,，\\-]+(?:单|双)?)\\s*周?").find(t)
        return m2?.groupValues?.get(1)?.replace('，', ',') ?: ""
    }

    /** 全文提取专业方向 */
    private fun extractMajors(lines: List<OcrLine>): List<String> {
        val majors = LinkedHashSet<String>()
        for (l in lines) {
            val m = Regex("专业方向[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z]+)").find(l.text)
            if (m != null && m.groupValues[1].isNotBlank()) {
                majors.add(m.groupValues[1])
            }
        }
        return majors.toList()
    }

    // ===== 大模型结构化 JSON 解析（LlmOcrEngine 输出） =====

    /** 从大模型返回的单个课程 JSON 对象解析为 ParsedCourse */
    fun parseCourseFromJson(json: String): ParsedCourse? = try {
        val o = JSONObject(json)
        val name = o.optString("name").trim()
        if (name.isEmpty()) return null

        var day = o.optInt("day", 0)
        if (day !in 1..5) {
            val dayName = o.optString("day").trim()
            val idx = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "周一", "周二", "周三", "周四", "周五")
                .indexOfFirst { it == dayName }
            day = if (idx in 0..4) (idx % 5) + 1 else 0
        }
        if (day !in 1..5) return null

        val start = o.optInt("start", 0)
        if (start !in 1..13) return null
        val end = o.optInt("end", start).coerceIn(start, 13)

        val weeks = o.optString("weeks").trim().ifEmpty { "1-${TOTAL_WEEKS}" }
        val loc = o.optString("loc").trim().ifEmpty { "未知教室" }
        val teacher = o.optString("teacher").trim().ifEmpty { "未知教师" }
        val classes = o.optString("classes").trim().takeIf { it.isNotEmpty() }
        val major = o.optString("major").trim().takeIf { it.isNotEmpty() }

        val course = Course(
            name = name,
            day = day,
            classes = classes,
            loc = loc,
            teacher = teacher,
            sessions = listOf(Session(weeks = weeks, start = start, end = end))
        )
        ParsedCourse(course, major)
    } catch (_: Exception) {
        null
    }

    /** 大模型结果里汇总专业方向 */
    fun extractMajorsFromJson(courses: List<ParsedCourse>): List<String> =
        courses.mapNotNull { it.major }.distinct()
}
