package cn.edu.qut.campus.service

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import cn.edu.qut.campus.data.local.AppPreferences
import cn.edu.qut.campus.data.model.CampusPeriod
import cn.edu.qut.campus.data.model.Course
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class CalendarSyncManager(private val context: Context) {

    companion object {
        private const val CALENDAR_ACCOUNT_NAME = "qut_schedule_calendar"
        private const val CALENDAR_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL

        /**
         * 开学日期默认值仍由 AppPreferences 兜底，避免出现两套写死的学期参数。
         */
        val DEFAULT_TERM_START: String = AppPreferences.DEFAULT_TERM_START
    }

    /** 只读取提醒分钟数等偏好，使用独立实例即可（SharedPreferences 本身是进程内共享的） */
    private val prefs: AppPreferences by lazy { AppPreferences(context.applicationContext) }

    /** 一条待写入日历的课程事件（纯数据，不含任何 I/O 结果） */
    private data class CalendarEvent(
        val title: String,
        val location: String,
        val description: String,
        val startMillis: Long,
        val endMillis: Long
    )

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
        } catch (e: CancellationException) {
            // 协程取消必须继续向上传播，不能被当成普通的「清空失败」
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // 批量同步课程至系统日历
    suspend fun syncCoursesToCalendar(
        courses: List<Course>,
        termStartDate: String = DEFAULT_TERM_START,
        reminderMinutes: Int = AppPreferences.DEFAULT_REMINDER_MINUTES,
        campus: String = "黄岛校区"
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // 提醒分钟数以偏好设置为准（调用方传参仅作为偏好不可用时的兜底）
            val effectiveReminder = prefs.calendarReminderMinutes.takeIf { it > 0 }
                ?: reminderMinutes.takeIf { it > 0 }
                ?: AppPreferences.DEFAULT_REMINDER_MINUTES

            val calId = getOrCreateCalendarId(campus)
            if (calId == -1L) {
                return@withContext Result.failure(Exception("无法访问或创建系统日历账户"))
            }

            // ---------- 第一步：纯计算，先把所有事件构建好（此阶段不产生任何写入） ----------
            val baseStartDate = LocalDate.parse(termStartDate)
            val zone = ZoneId.systemDefault()
            val timezone = TimeZone.getDefault().id
            val pendingEvents = ArrayList<CalendarEvent>(courses.size * 4)

            for (course in courses) {
                if (course.isPractice) continue // 实践环节不入定时日历

                val (startTimeStr, endTimeStr) = CampusPeriod.getTimeRange(course.startPeriod, course.endPeriod, campus)
                val startParts = startTimeStr.split(":")
                val endParts = endTimeStr.split(":")
                val startHour = startParts.getOrNull(0)?.toIntOrNull() ?: continue
                val startMinute = startParts.getOrNull(1)?.toIntOrNull() ?: 0
                val endHour = endParts.getOrNull(0)?.toIntOrNull() ?: continue
                val endMinute = endParts.getOrNull(1)?.toIntOrNull() ?: 0

                for (week in course.weeksList) {
                    // week 1, dayOfWeek 1 -> baseStartDate
                    val eventDate = baseStartDate.plusWeeks((week - 1).toLong()).plusDays((course.dayOfWeek - 1).toLong())

                    val startDateTime = LocalDateTime.of(
                        eventDate.year, eventDate.month, eventDate.dayOfMonth, startHour, startMinute
                    )
                    val endDateTime = LocalDateTime.of(
                        eventDate.year, eventDate.month, eventDate.dayOfMonth, endHour, endMinute
                    )

                    pendingEvents += CalendarEvent(
                        title = "[青理] ${course.name}",
                        location = "${course.classroom} (${course.teacher})",
                        description = "校区：$campus\n教室：${course.classroom}\n教师：${course.teacher}\n" +
                            "节次：第${course.startPeriod}-${course.endPeriod}节\n学分：${course.credit}",
                        startMillis = startDateTime.atZone(zone).toInstant().toEpochMilli(),
                        endMillis = endDateTime.atZone(zone).toInstant().toEpochMilli()
                    )
                }
            }

            // ---------- 第二步：先删后插。写入前若被取消，日历仍是上一次的完整内容 ----------
            // 取舍说明：系统日历的 ContentProvider 不提供跨删/插的事务 API，因此无法做到真正原子。
            // 这里采用「全部构建成功后才删除，删除后立刻在同一个临界段内把新事件全部写完」，
            // 把「日历半空」的窗口压到只剩删除与首次插入之间的极短时间；
            // 并且插入循环内部不因单条失败而中断，避免留下「删了一半、写了一部分」的状态。
            var eventCount = 0
            var calendarCleared = false
            // 已成功写入的事件索引：一旦协程被取消，只补写这些索引之外的事件，避免重复
            val writtenIndices = HashSet<Int>()

            try {
                context.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(calId.toString())
                )
                calendarCleared = true

                pendingEvents.forEachIndexed { index, event ->
                    val eventId = runCatching { insertEvent(calId, event, timezone) }.getOrNull()
                    if (eventId == null) {
                        // 单条写入失败只跳过，不中断整体同步，避免留下「写了一半」的日历
                        return@forEachIndexed
                    }
                    writtenIndices += index
                    eventCount++
                    if (effectiveReminder > 0) {
                        runCatching { insertReminder(eventId, effectiveReminder) }
                    }
                }
            } catch (e: CancellationException) {
                // 用户切走页面导致的取消：把还没写进去的事件尽力补齐，避免留下空日历，然后继续传播取消
                if (calendarCleared) {
                    makeUpPartialWrite(calId, pendingEvents, writtenIndices, effectiveReminder, timezone)
                }
                throw e
            }

            if (eventCount == 0 && pendingEvents.isNotEmpty()) {
                Result.failure(Exception("课程事件写入系统日历失败，请检查日历权限"))
            } else {
                Result.success(eventCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 写入单条事件，返回系统日历中的事件 id */
    private fun insertEvent(calId: Long, event: CalendarEvent, timezone: String): Long? {
        val eventValues = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        }
        val eventUri: Uri? = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
        return eventUri?.lastPathSegment?.toLongOrNull()?.takeIf { it > 0L }
    }

    /** 为事件添加课前提醒 */
    private fun insertReminder(eventId: Long, minutes: Int): Boolean {
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        val uri: Uri? = context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        return uri != null
    }

    /**
     * 取消后尽力补齐：把「已删除旧事件、但还没写进去」的那部分事件补上，
     * 用 NonCancellable 保证补写本身不会再次被取消，避免用户看到空日历。
     * 已成功写入的索引会被跳过，因此不会产生重复事件。
     */
    private suspend fun makeUpPartialWrite(
        calId: Long,
        events: List<CalendarEvent>,
        alreadyWritten: Set<Int>,
        reminderMinutes: Int,
        timezone: String
    ) {
        withContext(NonCancellable) {
            events.forEachIndexed { index, event ->
                if (index in alreadyWritten) return@forEachIndexed
                val eventId = runCatching { insertEvent(calId, event, timezone) }.getOrNull()
                    ?: return@forEachIndexed
                if (reminderMinutes > 0) {
                    runCatching { insertReminder(eventId, reminderMinutes) }
                }
            }
        }
    }
}
