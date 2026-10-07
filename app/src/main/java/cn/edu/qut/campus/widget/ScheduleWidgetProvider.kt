package cn.edu.qut.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import cn.edu.qut.campus.QutApplication
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.model.CampusPeriod
import cn.edu.qut.campus.data.model.cleanClassroom
import cn.edu.qut.campus.data.model.dayNameOf
import cn.edu.qut.campus.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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

        private fun offsetName(context: Context, offset: Int): String =
            context.getString(OFFSET_NAME_RES[offset] ?: R.string.widget_offset_today)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
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

                // 第一门课
                val c1 = targetCourses[0]
                val (s1, e1) = CampusPeriod.getTimeRange(c1.startPeriod, c1.endPeriod, campus)
                views.setTextViewText(R.id.tv_course1_time, "$s1\n$e1")
                views.setTextViewText(R.id.tv_course1_title, c1.name)
                views.setTextViewText(
                    R.id.tv_course1_desc,
                    "${cleanClassroom(c1.classroom)} | ${c1.teacher}"
                )

                // 第二门课
                if (targetCourses.size > 1) {
                    views.setViewVisibility(R.id.layout_course2, View.VISIBLE)
                    val c2 = targetCourses[1]
                    val (s2, _) = CampusPeriod.getTimeRange(c2.startPeriod, c2.endPeriod, campus)
                    views.setTextViewText(R.id.tv_course2_time, s2)
                    views.setTextViewText(
                        R.id.tv_course2_info,
                        "${c2.name} | ${cleanClassroom(c2.classroom)}"
                    )
                } else {
                    views.setViewVisibility(R.id.layout_course2, View.GONE)
                }

                // 超过两门课时给出剩余数量提示，避免用户以为当天只有两门课
                val restCount = targetCourses.size - 2
                if (restCount > 0) {
                    views.setViewVisibility(R.id.tv_course_more, View.VISIBLE)
                    views.setTextViewText(
                        R.id.tv_course_more,
                        context.getString(R.string.widget_more_courses, restCount)
                    )
                } else {
                    views.setViewVisibility(R.id.tv_course_more, View.GONE)
                }
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
