package cn.edu.qut.campus.data.model

import androidx.compose.runtime.Immutable

/**
 * 课程模型。
 * 标注 [Immutable] 是给 Compose 编译器的契约：全部字段为 val、且 [weeksList] 只读，
 * 这样 CourseCard / MiniCourseCard 等才能被正确跳过重组（否则每帧都重建整个课表格子）。
 */
@Immutable
data class Course(
    val id: String,
    val name: String,
    val classroom: String,
    val teacher: String,
    val dayOfWeek: Int, // 1 (Mon) to 7 (Sun)
    val startPeriod: Int,
    val endPeriod: Int,
    val weeksDescription: String,
    val weeksList: List<Int>,
    val credit: Double = 0.0,
    val isRetake: Boolean = false,
    val isPractice: Boolean = false,
    val colorHex: String = "#80CBC4"
) {
    fun isActiveInWeek(weekNumber: Int): Boolean {
        return weeksList.contains(weekNumber)
    }

    companion object {
        /** 学期最多按 30 周处理，避免教务返回异常数值时构造超大区间 */
        private const val MAX_WEEK = 30

        // 解析正方周次描述，如 "1-16周", "4-6周,8-12周(双)", "7-13周(单)", "第3周"
        fun parseWeeks(raw: String): List<Int> {
            val result = mutableSetOf<Int>()
            if (raw.isBlank()) return emptyList()

            val parts = raw.replace("周", "").split(",")
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.contains("(单)")) {
                    val range = trimmed.replace("(单)", "").split("-")
                    if (range.size == 2) {
                        val start = (range[0].toIntOrNull() ?: 1).coerceIn(1, MAX_WEEK)
                        val end = (range[1].toIntOrNull() ?: start).coerceIn(1, MAX_WEEK)
                        for (w in start..end) {
                            if (w % 2 != 0) result.add(w)
                        }
                    }
                } else if (trimmed.contains("(双)")) {
                    val range = trimmed.replace("(双)", "").split("-")
                    if (range.size == 2) {
                        val start = (range[0].toIntOrNull() ?: 1).coerceIn(1, MAX_WEEK)
                        val end = (range[1].toIntOrNull() ?: start).coerceIn(1, MAX_WEEK)
                        for (w in start..end) {
                            if (w % 2 == 0) result.add(w)
                        }
                    }
                } else if (trimmed.contains("-")) {
                    val range = trimmed.split("-")
                    if (range.size == 2) {
                        val start = (range[0].toIntOrNull() ?: 1).coerceIn(1, MAX_WEEK)
                        val end = (range[1].toIntOrNull() ?: start).coerceIn(1, MAX_WEEK)
                        for (w in start..end) {
                            result.add(w)
                        }
                    }
                } else {
                    val single = trimmed.replace("第", "").toIntOrNull()
                    if (single != null && single in 1..MAX_WEEK) result.add(single)
                }
            }
            return result.sorted()
        }
    }
}

/** 全 App 统一的星期中文名下标：0=周一 … 6=周日 */
val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 取星期中文名，越界时回退到「周一」 */
fun dayNameOf(dayOfWeek: Int): String = DAY_NAMES.getOrElse(dayOfWeek - 1) { DAY_NAMES[0] }

/**
 * 去掉教务系统返回的校区前缀，例如 "黄岛校区-B203" → "B203"。
 * 之前课表页、考试页、小组件各自实现了一份，行为不一致，这里统一。
 */
fun cleanClassroom(raw: String): String {
    if (raw.isBlank()) return raw
    return raw
        .replace("黄岛校区-", "")
        .replace("市北校区-", "")
        .replace("临沂校区-", "")
        .replace("嘉陵江路校区-", "")
        .replace("黄岛校区", "")
        .replace("市北校区", "")
        .trim()
}
