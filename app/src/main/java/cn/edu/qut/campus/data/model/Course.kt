package cn.edu.qut.campus.data.model

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
                        val start = range[0].toIntOrNull() ?: 1
                        val end = range[1].toIntOrNull() ?: start
                        for (w in start..end) {
                            if (w % 2 != 0) result.add(w)
                        }
                    }
                } else if (trimmed.contains("(双)")) {
                    val range = trimmed.replace("(双)", "").split("-")
                    if (range.size == 2) {
                        val start = range[0].toIntOrNull() ?: 1
                        val end = range[1].toIntOrNull() ?: start
                        for (w in start..end) {
                            if (w % 2 == 0) result.add(w)
                        }
                    }
                } else if (trimmed.contains("-")) {
                    val range = trimmed.split("-")
                    if (range.size == 2) {
                        val start = range[0].toIntOrNull() ?: 1
                        val end = range[1].toIntOrNull() ?: start
                        for (w in start..end) {
                            result.add(w)
                        }
                    }
                } else {
                    val single = trimmed.replace("第", "").toIntOrNull()
                    if (single != null) result.add(single)
                }
            }
            return result.sorted()
        }
    }
}
