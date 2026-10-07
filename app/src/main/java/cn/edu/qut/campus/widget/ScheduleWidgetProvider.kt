package cn.edu.qut.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import cn.edu.qut.campus.QutApplication
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.model.CampusPeriod
import cn.edu.qut.campus.data.model.Course
import cn.edu.qut.campus.data.model.cleanClassroom
import cn.edu.qut.campus.data.model.dayNameOf
import cn.edu.qut.campus.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

class ScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "ScheduleWidget"
        const val ACTION_PREV_DAY = "cn.edu.qut.campus.ACTION_WIDGET_PREV_DAY"
        const val ACTION_NEXT_DAY = "cn.edu.qut.campus.ACTION_WIDGET_NEXT_DAY"
        const val ACTION_REFRESH = "cn.edu.qut.campus.ACTION_WIDGET_REFRESH"

        /** 允许切换的相对日期范围：-1 昨日 / 0 今日 / 1 明日，超出范围时对应按钮隐藏 */
        private const val MIN_DAY_OFFSET = -1
        private const val MAX_DAY_OFFSET = 1

        /** 相对日期文案资源 id，key 为 dayOffset */
        private val OFFSET_NAME_RES = mapOf(
            -1 to R.string.widget_offset_yesterday,
            0 to R.string.widget_offset_today,
            1 to R.string.widget_offset_tomorrow
        )

        private const val REQ_PREV_DAY = 1
        private const val REQ_NEXT_DAY = 2
        private const val REQ_REFRESH = 3
        private const val REQ_OPEN_APP = 4

        /** 布局里预置的课程行（RemoteViews 不能动态添加 View，只能预置后按档位显隐） */
        private val COURSE_ROWS = arrayOf(
            CourseRowIds(R.id.layout_course1, R.id.tv_course1_time, R.id.tv_course1_title, R.id.tv_course1_desc),
            CourseRowIds(R.id.layout_course2, R.id.tv_course2_time, R.id.tv_course2_title, R.id.tv_course2_desc),
            CourseRowIds(R.id.layout_course3, R.id.tv_course3_time, R.id.tv_course3_title, R.id.tv_course3_desc)
        )

        /** 布局中固定的预算高度（dp），与 widget_schedule_layout.xml 一一对应，改动需同步注释 */
        private const val ROOT_VERTICAL_PADDING_DP = 12 // paddingTop 6 + paddingBottom 6
        private const val HEADER_HEIGHT_DP = 34 // layout_widget_header 的固定高度
        private const val COURSE_AREA_TOP_PADDING_DP = 2 // layout_courses_state 的 paddingTop
        private const val COURSE_ROW_HEIGHT_DP = 32 // 每行课程行 /「还有 N 门课」行的高度
        private const val COURSE_ROW_GAP_DP = 3 // 行间距（只计一个间隔）

        /** 除行本身以外，顶部区域占掉的固定高度 */
        private const val FIXED_TOP_DP = ROOT_VERTICAL_PADDING_DP + HEADER_HEIGHT_DP + COURSE_AREA_TOP_PADDING_DP

        /**
         * 每个档位额外保留的余量（dp）。
         * 用于吸收字体缩放（用户把系统字号调大）与启动器给小组件加的内边距；
         * 宁可少显示一门，也不要被裁切。
         */
        private const val HEIGHT_SAFETY_MARGIN_DP = 30

        /** 顶部区域 + N 行课程（含行间距）实际占用的高度 */
        private fun contentHeightDp(rows: Int): Int =
            FIXED_TOP_DP + rows * COURSE_ROW_HEIGHT_DP + (rows - 1).coerceAtLeast(0) * COURSE_ROW_GAP_DP

        /**
         * 分档阈值由 contentHeightDp + 余量向上取整到 5dp 得到，保证与布局常量不会走散：
         * - 1 行门槛：contentHeightDp(1) = 48 + 32      = 80  → 80dp
         * - 2 行门槛：contentHeightDp(2) = 48 + 64 + 3  = 115 → +30 → 145dp
         * - 3 行门槛：contentHeightDp(3) = 48 + 96 + 6  = 150 → +30 → 180dp
         *
         * 真机实测校准（OnePlus PHB110 / Android 13 / 480dpi / ColorOS 桌面，
         * dumpsys appwidget 打印 min=(64001x30721) 即 250×120dp）：
         * 桌面上已存在的实例（id=13）可用高度就是 120dp，落在「1 门课」档；
         * 120dp 的实际预算 = 120 - 6(上 padding) - 34(顶栏) - 2 - 32(一行) - 6(下 padding) = 40dp 余量，
         * 因此该档位能完整显示「顶栏 + 1 门课」，不会被裁切。
         */
        private const val ROWS_1_MIN_HEIGHT_DP = 80
        private val ROWS_2_MIN_HEIGHT_DP = roundUpTo5(contentHeightDp(2) + HEIGHT_SAFETY_MARGIN_DP)
        private val ROWS_3_MIN_HEIGHT_DP = roundUpTo5(contentHeightDp(3) + HEIGHT_SAFETY_MARGIN_DP)

        /** 向上取整到 5dp，让阈值好读、并避免定在边界上 */
        private fun roundUpTo5(value: Int): Int = (value + 4) / 5 * 5

        /** 读不到任何尺寸信息时的兜底：与 schedule_widget_info.xml 声明的 minHeight 一致 */
        private const val FALLBACK_HEIGHT_DP = 120

        private fun offsetName(context: Context, offset: Int): String =
            context.getString(OFFSET_NAME_RES[offset] ?: R.string.widget_offset_today)

        /**
         * 按桌面实际给出的可用高度决定显示几门课（阈值最终值：80 / 145 / 180dp）：
         * - ≥ 180dp → 3 门
         * - ≥ 145dp → 2 门
         * - ≥ 80dp  → 1 门（真机 120dp 的实例落在这里，完整不裁切）
         * - < 80dp  → 0 门，只显示顶栏 + 一行提示（用户把高度压到极限时的退化表现，
         *   因为 RemoteViews 不能在运行时移除顶栏，无法进一步腾空间）
         */
        private fun visibleRowsFor(heightDp: Int): Int = when {
            heightDp >= ROWS_3_MIN_HEIGHT_DP -> 3
            heightDp >= ROWS_2_MIN_HEIGHT_DP -> 2
            heightDp >= ROWS_1_MIN_HEIGHT_DP -> 1
            else -> 0
        }
    }

    /** 一行课程对应的 RemoteViews id 组合 */
    private data class CourseRowIds(
        val container: Int,
        val time: Int,
        val title: Int,
        val desc: Int
    )

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // goAsync 让广播接收器在 onReceive 返回后仍能完成查库 + 绘制，最多约 10s
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (widgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, widgetId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widgets", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * 用户拖动改变小组件尺寸后由系统立即回调，必须在这里按新高度重算行数并重绘；
     * 否则要等到下一个 updatePeriodMillis（30 分钟）才会生效。
     *
     * 这里不使用回调入参 [newOptions]，而是在 updateAppWidget 里重新
     * getAppWidgetOptions(appWidgetId)：回调触发时新尺寸已经写回系统，
     * 统一走同一个读取路径可以避免两处对 MIN/MAX_HEIGHT 的解读不一致。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget after resize", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext as? QutApplication ?: QutApplication.instance
        when (intent.action) {
            ACTION_PREV_DAY -> shiftDay(app, -1)
            ACTION_NEXT_DAY -> shiftDay(app, +1)
            ACTION_REFRESH -> Unit // 仅按当前持久化偏移重绘
            // 跨零点 / 用户改时间或时区：回到「今天」，避免小组件继续显示昨天的日期与课程
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> app.preferences.widgetDayOffset = 0
            else -> return
        }
        refreshAllWidgets(context)
    }

    private fun shiftDay(app: QutApplication, delta: Int) {
        val current = app.preferences.widgetDayOffset.coerceIn(MIN_DAY_OFFSET, MAX_DAY_OFFSET)
        app.preferences.widgetDayOffset = (current + delta).coerceIn(MIN_DAY_OFFSET, MAX_DAY_OFFSET)
    }

    private fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
        if (ids.isEmpty()) return
        onUpdate(context, manager, ids)
    }

    private fun broadcastPending(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ScheduleWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_layout)
        val app = context.applicationContext as? QutApplication ?: QutApplication.instance
        val prefs = app.preferences

        // 偏移量持久化在 AppPreferences，进程被杀后不会归零
        val dayOffset = prefs.widgetDayOffset.coerceIn(MIN_DAY_OFFSET, MAX_DAY_OFFSET)
        // 作息时间随用户选择的校区变化，与 App 内保持一致
        val campus = prefs.campus.ifBlank { context.getString(R.string.campus_huangdao) }

        val targetDate = LocalDate.now().plusDays(dayOffset.toLong())
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy/M/d")
        val dateString = targetDate.format(dateFormatter)

        val termStartDate = runCatching { LocalDate.parse(prefs.termStartDate) }
            .getOrElse { LocalDate.of(2026, 8, 31) }
        val daysBetween = ChronoUnit.DAYS.between(termStartDate, targetDate)
        val weekNumber = if (daysBetween < 0) 1 else (daysBetween / 7).toInt() + 1

        val dayOfWeekInt = targetDate.dayOfWeek.value // 1 (周一) 至 7 (周日)
        // 学期名由开学日期推导（如 2026-2027-1），不再写死年级
        val subtitle = "${prefs.termLabel} | ${context.getString(R.string.widget_week_n, weekNumber)} ${dayNameOf(dayOfWeekInt)}"

        // 尺寸自适应：真实可用高度决定显示几门课，避免固定两块课程行在 2 格高度下被裁切
        val availableHeightDp = resolveAvailableHeightDp(context, appWidgetManager, appWidgetId)
        val visibleRows = visibleRowsFor(availableHeightDp)

        views.setTextViewText(R.id.tv_widget_date, dateString)
        views.setTextViewText(R.id.tv_widget_sub, subtitle)
        views.setImageViewResource(R.id.iv_empty_icon, R.drawable.ic_schedule_empty)
        applyDayButtons(context, views, dayOffset)

        // 数据读取失败时不能伪装成「今天没有课」，单独记录并给出可点击刷新的提示
        var loadFailed = false

        try {
            val targetCourses = app.database.courseDao().getCoursesForDay(dayOfWeekInt)
                .map { it.toModel() }
                .filter { it.isActiveInWeek(weekNumber) && !it.isPractice }
                .sortedBy { it.startPeriod }

            if (targetCourses.isEmpty()) {
                views.setViewVisibility(R.id.layout_empty_state, View.VISIBLE)
                views.setViewVisibility(R.id.layout_courses_state, View.GONE)
                views.setTextViewText(R.id.tv_empty_text, emptyTextOf(context, dayOffset))
            } else {
                views.setViewVisibility(R.id.layout_empty_state, View.GONE)
                views.setViewVisibility(R.id.layout_courses_state, View.VISIBLE)
                bindCourseRows(context, views, targetCourses, visibleRows, campus)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching courses for widget", e)
            loadFailed = true
            views.setViewVisibility(R.id.layout_empty_state, View.VISIBLE)
            views.setViewVisibility(R.id.layout_courses_state, View.GONE)
            views.setTextViewText(R.id.tv_empty_text, context.getString(R.string.widget_load_failed))
        }

        // 点击按钮切换日期（三态：昨日 / 今日 / 明日）
        views.setOnClickPendingIntent(R.id.widget_root, openAppPending(context))

        // 失败时点提示文字直接刷新小组件；正常情况点整块区域进主 App
        views.setOnClickPendingIntent(
            R.id.tv_empty_text,
            if (loadFailed) {
                broadcastPending(context, ACTION_REFRESH, REQ_REFRESH)
            } else {
                openAppPending(context)
            }
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /**
     * 把课程列表填进预置的 1~3 行里。
     *
     * 「还有 N 门课」不额外占一行：当课程数多于当前档位能显示的行数时，
     * 把最后一行让给提示（占用的仍是课程行位，高度完全一致），
     * 因此课程区总高度永远不超过档位允许的行数，不会被桌面纵向裁掉。
     */
    private fun bindCourseRows(
        context: Context,
        views: RemoteViews,
        courses: List<Course>,
        visibleRows: Int,
        campus: String
    ) {
        val rowCount = visibleRows.coerceIn(0, COURSE_ROWS.size)

        // 档位只有 1 行时，唯一一行留给当天的第一门课（比提示「还有 N 门课」有用得多）；
        // 档位有 2~3 行时，把最后一行让给提示（占用的是课程行位，高度一致）；
        // 极端情况一行都放不下（< 70dp）时，这一行只用来告诉用户还有几门课。
        val showMoreLine = when {
            rowCount >= 2 -> true
            rowCount == 0 -> true
            else -> false
        }
        val courseSlots = if (rowCount >= 2) rowCount - 1 else rowCount
        val moreCount = if (showMoreLine) courses.size - courseSlots else 0

        for (index in COURSE_ROWS.indices) {
            val row = COURSE_ROWS[index]
            val course = courses.getOrNull(index)
            if (index < courseSlots && course != null) {
                views.setViewVisibility(row.container, View.VISIBLE)
                // 时间列是单行（maxLines=1）：只放开始时间，否则 Android 会在末尾补省略号
                // （例如「10:05…」），既难看又丢信息。完整起止时间在 App 内查看。
                val (startTime, _) = CampusPeriod.getTimeRange(course.startPeriod, course.endPeriod, campus)
                views.setTextViewText(row.time, startTime)
                views.setTextViewText(row.title, course.name)
                views.setTextViewText(row.desc, "${cleanClassroom(course.classroom)} | ${course.teacher}")
            } else {
                // 必须 GONE：INVISIBLE 仍会占用行高，会在小尺寸下把内容顶出可视区
                views.setViewVisibility(row.container, View.GONE)
            }
        }

        if (moreCount > 0) {
            views.setViewVisibility(R.id.tv_course_more, View.VISIBLE)
            views.setTextViewText(
                R.id.tv_course_more,
                context.getString(R.string.widget_more_courses, moreCount)
            )
        } else {
            views.setViewVisibility(R.id.tv_course_more, View.GONE)
        }
    }

    /**
     * 读取桌面实际分配给该实例的可用高度（dp）。
     * 优先 MIN_HEIGHT；为 0（部分启动器只给 MAX）时退回 MAX_HEIGHT；取两者较大值，
     * 兜底 FALLBACK_HEIGHT_DP（与 info 里声明的 minHeight 一致，真机实测该实例就是 120dp）。
     * 每个实例独立读取，因此同一桌面上高低不同的实例会显示不同行数。
     */
    private fun resolveAvailableHeightDp(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ): Int = runCatching {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        val declaredHeight = runCatching {
            appWidgetManager.getAppWidgetInfo(appWidgetId)?.minHeight
        }.getOrNull() ?: 0
        max(max(minHeight, maxHeight), declaredHeight)
    }.getOrDefault(0).takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP

    /**
     * 三个状态下的按钮布局：
     * - offset = -1（昨日）：左侧隐藏，右侧「今日 →」
     * - offset =  0（今日）：左侧「← 昨日」，右侧「明日 →」
     * - offset =  1（明日）：左侧「← 今日」，右侧隐藏
     * 文案统一描述「点下去会到哪一天」，避免用户误解当前展示的是哪一天。
     */
    private fun applyDayButtons(context: Context, views: RemoteViews, dayOffset: Int) {
        val prevTarget = (dayOffset - 1).coerceAtLeast(MIN_DAY_OFFSET)
        val nextTarget = (dayOffset + 1).coerceAtMost(MAX_DAY_OFFSET)

        // 无论是否可见都重新绑定点击 Intent，防止上一次更新残留的 PendingIntent 生效
        views.setOnClickPendingIntent(
            R.id.btn_widget_prev_day,
            broadcastPending(context, ACTION_PREV_DAY, REQ_PREV_DAY)
        )
        views.setOnClickPendingIntent(
            R.id.btn_widget_toggle_day,
            broadcastPending(context, ACTION_NEXT_DAY, REQ_NEXT_DAY)
        )

        if (prevTarget == dayOffset) {
            views.setViewVisibility(R.id.btn_widget_prev_day, View.INVISIBLE)
        } else {
            views.setViewVisibility(R.id.btn_widget_prev_day, View.VISIBLE)
            views.setTextViewText(
                R.id.btn_widget_prev_day,
                context.getString(R.string.widget_button_prev_to, offsetName(context, prevTarget))
            )
        }

        if (nextTarget == dayOffset) {
            views.setViewVisibility(R.id.btn_widget_toggle_day, View.INVISIBLE)
        } else {
            views.setViewVisibility(R.id.btn_widget_toggle_day, View.VISIBLE)
            views.setTextViewText(
                R.id.btn_widget_toggle_day,
                context.getString(R.string.widget_button_next_to, offsetName(context, nextTarget))
            )
        }
    }

    private fun emptyTextOf(context: Context, dayOffset: Int): String = when (dayOffset) {
        -1 -> context.getString(R.string.widget_no_courses_yesterday)
        1 -> context.getString(R.string.widget_no_courses_tomorrow)
        else -> context.getString(R.string.no_courses_today)
    }

    private fun openAppPending(context: Context): PendingIntent {
        val appIntent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, REQ_OPEN_APP, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
