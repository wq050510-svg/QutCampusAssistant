package cn.edu.qut.campus.service

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import cn.edu.qut.campus.data.model.CampusPeriod
import cn.edu.qut.campus.data.model.Course
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class CalendarSyncManager(private val context: Context) {

    companion object {
        private const val CALENDAR_ACCOUNT_NAME = "qut_schedule_calendar"
        private const val CALENDAR_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
    }

    // 获取或创建青岛理工专属日历账户
    private fun getOrCreateCalendarId(campus: String = "黄岛校区"): Long {
        val calendarDisplayName = "青岛理工大学($campus)课表"
        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "(${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?)"
        val selectionArgs = arrayOf(CALENDAR_ACCOUNT_NAME, CALENDAR_ACCOUNT_TYPE)

        val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }

        // 创建新日历账户
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, calendarDisplayName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, calendarDisplayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF006494.toInt()) // 青岛理工蓝
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, CALENDAR_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }

        val syncUri = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            .build()

        val insertedUri = context.contentResolver.insert(syncUri, values)
        return insertedUri?.lastPathSegment?.toLongOrNull() ?: -1L
    }

    // 清空旧课表事件
    suspend fun clearCalendar(campus: String = "黄岛校区"): Boolean = withContext(Dispatchers.IO) {
        try {
            val calId = getOrCreateCalendarId(campus)
            if (calId != -1L) {
                context.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(calId.toString())
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // 批量同步课程至系统日历
    suspend fun syncCoursesToCalendar(
        courses: List<Course>,
        termStartDate: String = "2026-08-31",
        reminderMinutes: Int = 20,
        campus: String = "黄岛校区"
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val calId = getOrCreateCalendarId(campus)
            if (calId == -1L) {
                return@withContext Result.failure(Exception("无法访问或创建系统日历账户"))
            }

            // 先安全抹除旧课程，避免重复
            clearCalendar(campus)

            val baseStartDate = LocalDate.parse(termStartDate)
            var eventCount = 0

            for (course in courses) {
                if (course.isPractice) continue // 实践环节不入定时日历

                val (startTimeStr, endTimeStr) = CampusPeriod.getTimeRange(course.startPeriod, course.endPeriod, campus)
                val startParts = startTimeStr.split(":")
                val endParts = endTimeStr.split(":")

                for (week in course.weeksList) {
                    // 计算具体日期的公历日期
                    // week 1, dayOfWeek 1 -> baseStartDate
                    val eventDate = baseStartDate.plusWeeks((week - 1).toLong()).plusDays((course.dayOfWeek - 1).toLong())

                    val startDateTime = LocalDateTime.of(
                        eventDate.year, eventDate.month, eventDate.dayOfMonth,
                        startParts[0].toInt(), startParts[1].toInt()
                    )
                    val endDateTime = LocalDateTime.of(
                        eventDate.year, eventDate.month, eventDate.dayOfMonth,
                        endParts[0].toInt(), endParts[1].toInt()
                    )

                    val startMillis = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val endMillis = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                    val eventValues = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, "[青理] ${course.name}")
                        put(CalendarContract.Events.EVENT_LOCATION, "${course.classroom} (${course.teacher})")
                        put(
                            CalendarContract.Events.DESCRIPTION,
                            "校区：$campus\n教室：${course.classroom}\n教师：${course.teacher}\n节次：第${course.startPeriod}-${course.endPeriod}节\n学分：${course.credit}"
                        )
                        put(CalendarContract.Events.DTSTART, startMillis)
                        put(CalendarContract.Events.DTEND, endMillis)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                    }

                    val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
                    val eventId = eventUri?.lastPathSegment?.toLongOrNull()

                    // 添加闹钟提醒
                    if (eventId != null && reminderMinutes > 0) {
                        val reminderValues = ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, eventId)
                            put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        }
                        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                    }

                    eventCount++
                }
            }

            Result.success(eventCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
