package com.example.timetable.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.timetable.data.Course
import com.example.timetable.data.Session
import com.example.timetable.data.Timetable
import java.util.Calendar

/**
 * 周视图课表（自定义绘制）：
 * - 节次列 + 周一至周五 5 列，随屏幕宽度自适应（无横向滚动）
 * - 上午 1-5 / 下午 6-9 / 晚上 10-13 分组
 * - 课程卡片按起止节次定位，教室编号白底深色粗体徽章
 * - 编辑模式：卡片上下沿可拖拽调整节次；可接收从课程列表拖入的课程（调课添加）
 */
class TimetableView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var courses: List<Course> = emptyList()
    private var week = 1
    private var termStart: Calendar = Calendar.getInstance()

    /** 点击卡片（打开编辑表单） */
    var onCourseClick: ((Course, Session) -> Unit)? = null
    /** 拖拽添加：从课程列表拖入到课表某天某节 */
    var onDropAdd: ((name: String, day: Int, start: Int, end: Int) -> Unit)? = null
    /** 拖到 0 节槽：表示该天不上该课（从课表移除该课程当天记录） */
    var onDropZero: ((name: String, day: Int) -> Unit)? = null
    /** 长按已有卡片拖到 0 节槽：删除本节课（该 Session） */
    var onDropSession: ((name: String, day: Int, weeks: String, start: Int, end: Int) -> Unit)? = null
    /** 卡片上下沿拖拽调整节次完成 */
    var onResizeCommit: ((course: Course, sess: Session, newStart: Int, newEnd: Int) -> Unit)? = null

    /** 编辑模式：显示拖拽手柄、0 节拖放槽、接收拖入、允许调整节次 */
    var editMode: Boolean = false
        set(value) {
            if (field != value) requestLayout()
            field = value
            invalidate()
        }

    fun setData(courses: List<Course>, week: Int, termStart: Calendar) {
        this.courses = courses
        this.week = week
        this.termStart = termStart
        requestLayout()
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Int) = v * density
    private fun dpf(v: Float) = v * density

    private val pad = dp(8)
    private val periodW = dp(48)
    private val headH = dp(44)
    private val rowH = dp(52)
    /** 编辑模式顶部 0 节拖放槽的高度 */
    private val zeroSlotH = dp(36)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val topSpace = if (editMode) zeroSlotH else pad
        // 网格 13 行 + 表头(headH) + 顶部空间 + 底部边距
        setMeasuredDimension(w, rowH * 13 + topSpace + headH + pad)
    }

    private fun dayWidth(): Float {
        val w = width.toFloat()
        return (w - pad * 2 - periodW) / 5f
    }

    /** 网格起点：表头(headH)之下 */
    private fun gridTop(): Float = (if (editMode) zeroSlotH else pad).toFloat() + headH

    /** 表头顶部 */
    private fun headTop(): Float = (if (editMode) zeroSlotH else pad).toFloat()

    private fun dayFromX(x: Float): Int {
        val dayW = dayWidth()
        val v = ((x - pad - periodW) / dayW).toInt() + 1
        return v.coerceIn(1, 5)
    }

    private fun periodFromY(y: Float): Int {
        if (editMode && y < zeroSlotH) return 0
        val g = gridTop()
        if (y < g) return 1
        val v = ((y - g) / rowH).toInt() + 1
        return v.coerceIn(1, 13)
    }

    private fun isToday(day: Int): Boolean {
        val now = Calendar.getInstance()
        val dow = now.get(Calendar.DAY_OF_WEEK) // 1=周日
        return week == Timetable.currentWeek(termStart, now) && dow == day + 1
    }

    override fun onDraw(canvas: Canvas) {
        tapTargets = emptyList()
        val w = width.toFloat()
        val dayW = dayWidth()
        val y0 = gridTop()
        val bottom = y0 + rowH * 13f

        val linePaint = Paint().apply { color = 0xFFE2E8F0.toInt(); strokeWidth = 1f }
        val sepPaint = Paint().apply { color = 0xFFCBD5E1.toInt(); strokeWidth = dpf(1.2f) }

        // 背景
        canvas.drawColor(0xFFF8FAFC.toInt())
        // 节次列背景
        canvas.drawRect(pad.toFloat(), y0, pad + periodW.toFloat(), bottom, Paint().apply { color = 0xFFF1F5F9.toInt() })

        // 编辑模式：顶部 0 节拖放槽（拖入课程到此处 = 该天不上该课）
        if (editMode) {
            val active = dragTargetVisible && dragPeriod == 0
            val zRect = RectF(pad.toFloat(), dpf(4), pad + periodW + dayW * 5, headTop() - dpf(4))
            canvas.drawRoundRect(
                zRect, dpf(6), dpf(6),
                Paint().apply { color = if (active) 0xFFFEE2E2.toInt() else 0xFFF1F5F9.toInt() }
            )
            val zStroke = Paint().apply {
                color = if (active) 0xFFEF4444.toInt() else 0xFFCBD5E1.toInt()
                style = Paint.Style.STROKE
                strokeWidth = dpf(1)
                isAntiAlias = true
            }
            canvas.drawRoundRect(zRect, dpf(6), dpf(6), zStroke)
            val zp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (active) 0xFFDC2626.toInt() else 0xFF94A3B8.toInt()
                textSize = dpf(10)
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val zFm = zp.fontMetrics
            val zCy = zRect.centerY() - (zFm.ascent + zFm.descent) / 2
            canvas.drawText(
                if (active) "松开 = 0 节：该天不上该课" else "0 节槽：把课程拖到这里 = 该天不上该课",
                pad + periodW / 2 + dayW * 5 / 2, zCy, zp
            )
        }

        // 表头
        val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt(); textSize = dpf(14); typeface = Typeface.DEFAULT_BOLD
        }
        // 星期表头
        for (i in 0 until 5) {
            val x = pad + periodW + i * dayW
            if (isToday(i)) canvas.drawRect(x, headTop(), x + dayW, headTop() + headH.toFloat(), Paint().apply { color = 0xFFEFF6FF.toInt() })
            drawCenterText(canvas, Timetable.DAY_NAMES[i], x, headTop(), dayW, headH.toFloat(), headPaint)
            canvas.drawLine(x, headTop(), x, bottom, linePaint)
        }

        // 网格行
        for (r in 0..13) {
            val y = y0 + r * rowH.toFloat()
            canvas.drawLine(pad.toFloat(), y, pad + periodW + dayW * 5, y, linePaint)
        }
        // 分组分隔线
        for (sep in listOf(5, 9)) {
            val y = y0 + sep * rowH.toFloat()
            canvas.drawLine(pad.toFloat(), y, pad + periodW + dayW * 5, y, sepPaint)
        }

        // 节次数字
        val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF475569.toInt(); textSize = dpf(11); textAlign = Paint.Align.CENTER
        }
        for (n in 1..13) {
            val cy = y0 + (n - 1) * rowH + rowH / 2f + dpf(2)
            canvas.drawText("$n", pad + periodW / 2f, cy, numPaint)
        }
        // 上午/下午/晚上
        drawSeg(canvas, "上午", 0xFFE8F0FE.toInt(), 0xFF3B82F6.toInt(), y0)
        drawSeg(canvas, "下午", 0xFFFDF0E4.toInt(), 0xFFD97706.toInt(), y0 + 5 * rowH)
        drawSeg(canvas, "晚上", 0xFFF0EAF7.toInt(), 0xFF7C3AED.toInt(), y0 + 9 * rowH)

        // 拖入预览：目标格高亮
        if (dragTargetVisible && dragDay in 1..5 && dragPeriod in 1..13) {
            val px = pad + periodW + (dragDay - 1) * dayW
            val py = y0 + (dragPeriod - 1) * rowH
            canvas.drawRect(px, py, px + dayW, py + rowH, Paint().apply { color = 0x332563EB.toInt() })
        }

        // 课程卡片
        for (i in 0 until 5) {
            val dayX = pad + periodW + i * dayW
            val dayCourses = Timetable.coursesOfDay(courses, week, i + 1)
            for ((course, sess) in dayCourses) {
                // 拖拽调整节次中：用临时起止绘制预览
                val rs = resize
                val drawSess = if (rs != null && rs.course.name == course.name && rs.sess.weeks == sess.weeks &&
                    rs.sess.start == sess.start && rs.sess.end == sess.end
                ) {
                    sess.copy(start = rs.newStart, end = rs.newEnd)
                } else sess
                drawCourseCard(canvas, course, drawSess, dayX, dayW, y0)
            }
        }
    }

    private fun drawSeg(canvas: Canvas, label: String, bg: Int, fg: Int, top: Float) {
        val w = periodW.toFloat()
        val rect = RectF(pad.toFloat(), top, pad + w, top + dpf(16))
        canvas.drawRoundRect(rect, dpf(2), dpf(2), Paint().apply { color = bg })
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textSize = dpf(9); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val fm = p.fontMetrics
        canvas.drawText(label, pad + w / 2, top + dpf(16) - (dpf(16) - fm.ascent - fm.descent) / 2 - fm.ascent, p)
    }

    private fun drawCenterText(canvas: Canvas, text: String, x: Float, y: Float, w: Float, h: Float, paint: TextPaint) {
        val fm = paint.fontMetrics
        val ty = y + (h - (fm.ascent + fm.descent)) / 2
        canvas.drawText(text, x + (w - paint.measureText(text)) / 2, ty, paint)
    }

    private fun drawCourseCard(
        canvas: Canvas, course: Course, sess: Session,
        dayX: Float, dayW: Float, y0: Float
    ) {
        val x = dayX + dpf(2)
        val w = dayW - dpf(4)
        val top = y0 + (sess.start - 1) * rowH + dpf(2)
        val h = (sess.end - sess.start + 1) * rowH - dpf(4)
        if (w <= 0 || h <= 0) return

        val cardColor = Timetable.colorOf(course.name)
        val rect = RectF(x, top, x + w, top + h)
        // 阴影
        val shadowRect = RectF(x + dpf(1), top + dpf(2), x + w + dpf(1), top + h + dpf(2))
        canvas.drawRoundRect(shadowRect, dpf(8), dpf(8), Paint().apply { color = 0x18000000.toInt() })
        // 卡片主体
        canvas.drawRoundRect(rect, dpf(8), dpf(8), Paint().apply { color = cardColor })

        // 内容区：任何一块放不下就跳过该块，绝不 return，保证拖拽手柄与点击区域永远存在
        val padX = dpf(6)
        val innerW = (w - padX * 2).toInt()
        val contentBottom = top + h - dpf(4)
        var cursor = top + dpf(6)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val roomPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt() }
        fun fits(next: Int): Boolean = cursor + next <= contentBottom

        // 课程名：正常 13sp 最多 2 行；矮卡片（如 1 节）自动缩到 10sp 单行
        textPaint.textSize = dpf(13)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        var nameSL = StaticLayout.Builder.obtain(course.name, 0, course.name.length, textPaint, innerW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val avail = contentBottom - cursor
        if (nameSL.height > avail) {
            textPaint.textSize = dpf(10)
            nameSL = StaticLayout.Builder.obtain(course.name, 0, course.name.length, textPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        if (fits(nameSL.height)) {
            canvas.save(); canvas.translate(x + padX, cursor); nameSL.draw(canvas); canvas.restore()
            cursor += nameSL.height + dpf(2)
        } else {
            cursor += dpf(2)
        }

        // 节次·周次（完整显示不省略，超高自动换行，两行放不下则缩小字号）
        textPaint.textSize = dpf(10)
        textPaint.typeface = Typeface.DEFAULT
        val timeText = "${sess.start}-${sess.end}节 · ${sess.weeks}周"
        var wSize = dpf(10)
        var timeSL: StaticLayout
        while (true) {
            timeSL = StaticLayout.Builder.obtain(timeText, 0, timeText.length, textPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            if (timeSL.lineCount <= 2 || wSize <= dpf(7.5f)) break
            wSize -= dpf(0.5f)
            textPaint.textSize = wSize
        }
        if (fits(timeSL.height)) {
            canvas.save(); canvas.translate(x + padX, cursor); timeSL.draw(canvas); canvas.restore()
            cursor += timeSL.height + dpf(2)
        } else {
            cursor += dpf(2)
        }

        // 调课徽章
        if (sess.makeup) {
            val mkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF92400E.toInt(); textSize = dpf(9); typeface = Typeface.DEFAULT_BOLD
            }
            val mkText = "调课"
            val mkW = minOf((mkPaint.measureText(mkText) + dpf(8)).toInt(), innerW)
            if (fits(dpf(14).toInt())) {
                val mkRect = RectF(x + padX, cursor, x + padX + mkW, cursor + dpf(14))
                canvas.drawRoundRect(mkRect, dpf(3), dpf(3), Paint().apply { color = 0xFFFDE68A.toInt() })
                val fm = mkPaint.fontMetrics
                val mkBaseline = cursor + dpf(14) / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(mkText, x + padX + (mkW - mkPaint.measureText(mkText)) / 2, mkBaseline, mkPaint)
            }
            cursor += dpf(16)
        } else {
            cursor += dpf(2)
        }

        // 教室：白底编号徽章 + 白字后缀
        val locCode = roomCode(course.loc)
        val locRest = course.loc.split(' ').drop(1).joinToString(" ").trim()
        roomPaint.textSize = dpf(11)
        roomPaint.typeface = Typeface.DEFAULT_BOLD
        // 徽章不超出卡片：文字太长时按可用宽度等比缩小字号，保证完整显示不截断
        val maxBadgeText = (innerW - dpf(8)).toFloat()
        val textW0 = roomPaint.measureText(locCode)
        if (textW0 > maxBadgeText) roomPaint.textSize = dpf(11) * maxBadgeText / textW0
        val textW = roomPaint.measureText(locCode)
        val badgeW = minOf((textW + dpf(10)).toInt(), innerW)
        val badgeH = dpf(16)
        if (fits(badgeH.toInt())) {
            val bRect = RectF(x + padX, cursor, x + padX + badgeW, cursor + badgeH)
            canvas.drawRoundRect(bRect, dpf(4), dpf(4), Paint().apply { color = 0xFFF2F2F2.toInt() })
            val fm = roomPaint.fontMetrics
            val baseline = cursor + badgeH / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(locCode, x + padX + (badgeW - textW) / 2f, baseline, roomPaint)
            // 后缀（白字单行）
            if (locRest.isNotEmpty() && x + padX + badgeW + dpf(4) < x + w - dpf(2)) {
                val restPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = dpf(9); typeface = Typeface.DEFAULT
                }
                val restW = (w - padX * 2 - badgeW - dpf(4)).toInt()
                if (restW > 0) {
                    val restSL = StaticLayout.Builder.obtain(locRest, 0, locRest.length, restPaint, restW)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(1)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()
                    canvas.save()
                    canvas.translate(x + padX + badgeW + dpf(4), cursor + (badgeH - restSL.height) / 2)
                    restSL.draw(canvas)
                    canvas.restore()
                }
            }
            cursor += badgeH + dpf(3)
        }

        // 教师（单行）
        if (course.teacher.isNotBlank() && course.teacher != "未知教师") {
            textPaint.textSize = dpf(10)
            textPaint.typeface = Typeface.DEFAULT
            val tSL = StaticLayout.Builder.obtain(course.teacher, 0, course.teacher.length, textPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(1)
                .build()
            if (fits(tSL.height)) {
                canvas.save(); canvas.translate(x + padX, cursor); tSL.draw(canvas); canvas.restore()
            }
        }

        // 编辑模式：上下沿拖拽手柄（无条件绘制，矮卡片也保留）
        if (editMode) {
            val handlePaint = Paint().apply { color = 0x66FFFFFF.toInt() }
            val hx0 = x + dpf(10)
            val hx1 = x + w - dpf(10)
            // 上沿手柄
            canvas.drawRoundRect(
                RectF(hx0, top + dpf(3), hx1, top + dpf(8)),
                dpf(3), dpf(3), handlePaint
            )
            // 下沿手柄
            canvas.drawRoundRect(
                RectF(hx0, top + h - dpf(8), hx1, top + h - dpf(3)),
                dpf(3), dpf(3), handlePaint
            )
        }

        // 点击区域记录（无条件记录，保证矮卡片也能点中）
        tapTargets = tapTargets.orEmpty() + TapTarget(rect, course, sess)
    }

    private fun roomCode(loc: String): String {
        val parts = loc.split(' ')
        val code = if (parts.size > 1) parts[0] else loc.replace(Regex("合班教室$"), "").trim()
        // 统一教室号格式：B5-1-1楼102 / B5-1-102 这类 OCR 怪格式 → B5-102（楼栋-楼层-房间）
        val m = Regex("^([A-Za-z]\\d+)-(\\d+)-?(\\d*)楼?(\\d{3})$").find(code)
        return if (m != null) m.groupValues[1] + "-" + m.groupValues[4] else code
    }

    private data class TapTarget(val rect: RectF, val course: Course, val sess: Session)

    private var tapTargets: List<TapTarget>? = null

    // ===== 拖拽调整节次 =====

    private data class ResizeState(
        val course: Course, val sess: Session,
        val mode: Int, // 0 = 上沿(改 start)，1 = 下沿(改 end)
        var newStart: Int, var newEnd: Int
    )

    private var resize: ResizeState? = null

    // 长按卡片拖拽（编辑模式）：长按卡片中部 -> 拖动到 0 节槽 = 删除本节课
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressTarget: TapTarget? = null
    private var longPressPending = false
    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val longPressRunnable = Runnable {
        val t = longPressTarget
        if (longPressPending && t != null) {
            longPressPending = false
            longPressTriggered = true
            val data = ClipData.newPlainText(
                "course-sess",
                "sess:${t.course.name}|${t.course.day}|${t.sess.weeks}|${t.sess.start}|${t.sess.end}"
            )
            val sw = t.rect.width().toInt()
            val sh = t.rect.height().toInt()
            val shadow = object : View.DragShadowBuilder(this@TimetableView) {
                override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
                    size.set(sw, sh)
                    touch.set((downX - t.rect.left).toInt(), (downY - t.rect.top).toInt())
                }

                override fun onDrawShadow(canvas: Canvas) {
                    val bg = Timetable.colorOf(t.course.name)
                    canvas.drawRoundRect(
                        RectF(0f, 0f, sw.toFloat(), sh.toFloat()), dpf(8), dpf(8),
                        Paint().apply { color = bg }
                    )
                    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE; textSize = dpf(13); typeface = Typeface.DEFAULT_BOLD
                    }
                    val fm = p.fontMetrics
                    canvas.drawText(t.course.name, dpf(8), sh / 2f - (fm.ascent + fm.descent) / 2, p)
                }
            }
            startDragAndDrop(data, shadow, this@TimetableView, 0)
            invalidate()
        }
    }

    private fun findResizeHandle(x: Float, y: Float): ResizeState? {
        if (!editMode) return null
        val targets = tapTargets ?: return null
        val hh = dp(16).toFloat()
        for (t in targets) {
            if (!t.rect.contains(x, y)) continue
            val rectH = t.rect.height()
            if (rectH <= hh * 2) {
                // 矮卡片（如拖成 1 节时）：上半=改开始，下半=改结束，避免中部盲区导致拖不动
                return if (y < t.rect.centerY()) {
                    ResizeState(t.course, t.sess, 0, t.sess.start, t.sess.end)
                } else {
                    ResizeState(t.course, t.sess, 1, t.sess.start, t.sess.end)
                }
            }
            if (y <= t.rect.top + hh) {
                return ResizeState(t.course, t.sess, 0, t.sess.start, t.sess.end)
            }
            if (y >= t.rect.bottom - hh) {
                return ResizeState(t.course, t.sess, 1, t.sess.start, t.sess.end)
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = findResizeHandle(event.x, event.y)
                    if (hit != null) {
                        resize = hit
                        // 阻止父级(滚动容器)拦截本次手势
                        (parent as? android.view.ViewParent)?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    // 非手柄区域：记录长按候选（长按=拖动卡片到0节槽删除；短按=点击编辑）
                    val targets = tapTargets
                    val tap = targets?.firstOrNull { it.rect.contains(event.x, event.y) }
                    downX = event.x
                    downY = event.y
                    longPressPending = false
                    longPressTriggered = false
                    longPressTarget = null
                    if (tap != null) {
                        longPressTarget = tap
                        longPressPending = true
                        handler.postDelayed(longPressRunnable, 400)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rs = resize
                    if (rs != null) {
                        if (rs.mode == 0) {
                            rs.newStart = periodFromY(event.y).coerceIn(1, rs.newEnd)
                        } else {
                            rs.newEnd = periodFromY(event.y).coerceIn(rs.newStart, 13)
                        }
                        invalidate()
                    } else if (longPressPending) {
                        // 手指移动超过阈值：取消长按候选
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            longPressPending = false
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val rs = resize
                    val isUp = event.action == MotionEvent.ACTION_UP
                    if (rs != null && isUp) {
                        onResizeCommit?.invoke(rs.course, rs.sess, rs.newStart, rs.newEnd)
                    } else if (rs == null && isUp && !longPressTriggered) {
                        // 未拖手柄且未长按：视为点击卡片，打开编辑表单
                        val targets = tapTargets
                        if (targets != null) {
                            val hit = targets.firstOrNull { it.rect.contains(event.x, event.y) }
                            if (hit != null) onCourseClick?.invoke(hit.course, hit.sess)
                        }
                    }
                    resize = null
                    longPressPending = false
                    longPressTriggered = false
                    longPressTarget = null
                    handler.removeCallbacks(longPressRunnable)
                    (parent as? android.view.ViewParent)?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return true
        }

        // 非编辑模式：点击卡片
        if (event.action == MotionEvent.ACTION_UP) {
            val targets = tapTargets ?: return super.onTouchEvent(event)
            val x = event.x
            val y = event.y
            val hit = targets.firstOrNull { it.rect.contains(x, y) }
            if (hit != null) {
                onCourseClick?.invoke(hit.course, hit.sess)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ===== 接收拖入（课程列表 -> 课表） =====

    init {
        setOnDragListener { v, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    val ok = e.clipDescription != null &&
                        e.clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && editMode
                    if (ok) {
                        dragTargetVisible = true
                        invalidate()
                    }
                    ok
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    dragDay = dayFromX(e.x)
                    dragPeriod = periodFromY(e.y)
                    // 拖到接近屏幕顶部时，自动向上滚动父级，让 0 节槽可见
                    // e.y 是相对本 View 的坐标，减去已滚动量才是屏幕上的位置
                    val sv = parent as? androidx.core.widget.NestedScrollView
                    val onScreenY = e.y - (sv?.scrollY ?: 0)
                    if (sv != null && sv.scrollY > 0 && onScreenY < dp(90).toFloat()
                        && SystemClock.uptimeMillis() - lastAutoScroll > 400
                    ) {
                        lastAutoScroll = SystemClock.uptimeMillis()
                        sv.smoothScrollTo(0, 0)
                    }
                    invalidate()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    dragTargetVisible = false
                    if (!editMode) return@setOnDragListener false
                    val cs = e.clipData ?: return@setOnDragListener false
                    val text = cs.getItemAt(0).text?.toString()
                        ?: return@setOnDragListener false
                    val day = dayFromX(e.x)
                    val p = periodFromY(e.y)
                    if (text.startsWith("sess:")) {
                        // 长按卡片拖拽：拖到 0 节槽 = 删除本节课
                        val parts = text.removePrefix("sess:").split("|")
                        if (parts.size >= 5 && p == 0) {
                            // 用卡片原所在天匹配（拖到槽的不同横向位置不应改变目标天）
                            val sday = parts[1].toIntOrNull() ?: day
                            val wks = parts[2]
                            val s = parts[3].toIntOrNull() ?: 1
                            val en = parts[4].toIntOrNull() ?: 1
                            onDropSession?.invoke(parts[0], sday, wks, s, en)
                        }
                        // 拖到课表其他位置：暂不支持移动，忽略
                    } else {
                        // 课程列表拖入
                        if (day in 1..5 && p == 0) {
                            onDropZero?.invoke(text, day)
                        } else if (day in 1..5 && p in 1..13) {
                            onDropAdd?.invoke(text, day, p, p)
                        }
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragTargetVisible = false
                    invalidate()
                    true
                }
                else -> true
            }
        }
    }

    /** 拖入预览：目标列/节次高亮 */
    private var dragTargetVisible = false
    private var dragDay = -1
    private var dragPeriod = -1
    private var lastAutoScroll = 0L
}
