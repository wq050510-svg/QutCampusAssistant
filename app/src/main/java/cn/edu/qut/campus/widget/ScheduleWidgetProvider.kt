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
import cn.edu.qut.campus.data.model.Course
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

        // 桌面展示偏移天数（0 表示今天，1 表示明天）
        var dayOffset = 0
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
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NEXT_DAY -> {
                dayOffset = if (dayOffset == 0) 1 else 0
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
                onUpdate(context, manager, ids)
            }
            ACTION_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
                onUpdate(context, manager, ids)
            }
        }
    }

    private suspend fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_layout)
        val app = context.applicationContext as? QutApplication ?: QutApplication.instance

        val targetDate = LocalDate.now().plusDays(dayOffset.toLong())
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy/M/d")
        val dateString = targetDate.format(dateFormatter)

        val termStartDateStr = app.preferences.termStartDate.ifBlank { "2026-08-31" }
        val termStartDate = try {
            LocalDate.parse(termStartDateStr)
        } catch (e: Exception) {
            LocalDate.of(2026, 8, 31)
        }
        val daysBetween = ChronoUnit.DAYS.between(termStartDate, targetDate)
        val weekNumber = if (daysBetween < 0) 1 else (daysBetween / 7).toInt() + 1

        val dayOfWeekMap = mapOf(
            1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日"
        )
        val dayOfWeekInt = targetDate.dayOfWeek.value // 1 (Mon) to 7 (Sun)
        val dayName = dayOfWeekMap[dayOfWeekInt] ?: "周日"
        val subtitle = "大四上 | 第${weekNumber}周 $dayName"

        views.setTextViewText(R.id.tv_widget_date, dateString)
        views.setTextViewText(R.id.tv_widget_sub, subtitle)
        views.setTextViewText(R.id.btn_widget_toggle_day, if (dayOffset == 0) "明日 →" else "← 今日")
        views.setImageViewResource(R.id.iv_empty_icon, R.drawable.ic_schedule_empty)

        // 查询今日课程
        try {
            val entities = app.database.courseDao().getCoursesForDay(dayOfWeekInt)
            val todayCourses = entities
                .map { it.toModel() }
                .filter { it.isActiveInWeek(weekNumber) && !it.isPractice }
                .sortedBy { it.startPeriod }

            if (todayCourses.isEmpty()) {
                views.setViewVisibility(R.id.layout_empty_state, View.VISIBLE)
                views.setViewVisibility(R.id.layout_courses_state, View.GONE)
                views.setTextViewText(R.id.tv_empty_text, if (dayOffset == 0) "今天没有课哦" else "明天也没有课哦")
            } else {
                views.setViewVisibility(R.id.layout_empty_state, View.GONE)
                views.setViewVisibility(R.id.layout_courses_state, View.VISIBLE)

                // 第一门课
                val c1 = todayCourses[0]
                val (s1, e1) = CampusPeriod.getTimeRange(c1.startPeriod, c1.endPeriod)
                views.setTextViewText(R.id.tv_course1_time, "$s1\n$e1")
                views.setTextViewText(R.id.tv_course1_title, c1.name)
                views.setTextViewText(R.id.tv_course1_desc, "${c1.classroom} | ${c1.teacher}")

                // 第二门课
                if (todayCourses.size > 1) {
                    views.setViewVisibility(R.id.layout_course2, View.VISIBLE)
                    val c2 = todayCourses[1]
                    val (s2, _) = CampusPeriod.getTimeRange(c2.startPeriod, c2.endPeriod)
                    views.setTextViewText(R.id.tv_course2_time, s2)
                    views.setTextViewText(R.id.tv_course2_info, "${c2.name} | ${c2.classroom}")
                } else {
                    views.setViewVisibility(R.id.layout_course2, View.GONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching courses for widget", e)
            views.setViewVisibility(R.id.layout_empty_state, View.VISIBLE)
            views.setViewVisibility(R.id.layout_courses_state, View.GONE)
            views.setTextViewText(R.id.tv_empty_text, "今天没有课哦")
        }

        // 点击切换按钮 Intent
        val toggleIntent = Intent(context, ScheduleWidgetProvider::class.java).apply {
            action = ACTION_NEXT_DAY
        }
        val togglePending = PendingIntent.getBroadcast(
            context, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_widget_toggle_day, togglePending)

        // 点击小组件进入主 App
        val appIntent = Intent(context, MainActivity::class.java)
        val appPending = PendingIntent.getActivity(
            context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, appPending)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
