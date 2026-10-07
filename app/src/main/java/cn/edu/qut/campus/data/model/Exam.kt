package cn.edu.qut.campus.data.model

import androidx.compose.runtime.Immutable
import java.time.LocalDateTime
import java.util.regex.Pattern

/** 预编译正则：原实现每次读取 isFinished 都要 Pattern.compile 两次 */
private val EXAM_DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})")
private val EXAM_END_TIME_PATTERN = Pattern.compile("-(\\d{2}:\\d{2})")

@Immutable
data class Exam(
    val id: String,
    val courseName: String,
    val examTime: String, // 如 2026-09-09(14:00-15:50)
    val classroom: String, // 如 B205
    val seatNumber: String, // 如 2
    val campus: String = "黄岛校区"
) {
    // 判断考试是否已结束
    val isFinished: Boolean
        get() {
            return try {
                val dateMatcher = EXAM_DATE_PATTERN.matcher(examTime)
                if (!dateMatcher.find()) return false
                val dateStr = dateMatcher.group(1)

                val timeMatcher = EXAM_END_TIME_PATTERN.matcher(examTime)
                val endTimeStr = if (timeMatcher.find()) timeMatcher.group(1) else "23:59"

                val endDateTime = LocalDateTime.parse("${dateStr}T${endTimeStr}:00")
                endDateTime.isBefore(LocalDateTime.now())
            } catch (e: Exception) {
                false
            }
        }
}
