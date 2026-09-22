package cn.edu.qut.campus.data.model

import java.time.LocalDateTime
import java.util.regex.Pattern

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
                val dateMatcher = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(examTime)
                if (!dateMatcher.find()) return false
                val dateStr = dateMatcher.group(1)

                val timeMatcher = Pattern.compile("-(\\d{2}:\\d{2})").matcher(examTime)
                val endTimeStr = if (timeMatcher.find()) timeMatcher.group(1) else "23:59"

                val endDateTime = LocalDateTime.parse("${dateStr}T${endTimeStr}:00")
                endDateTime.isBefore(LocalDateTime.now())
            } catch (e: Exception) {
                false
            }
        }
}
