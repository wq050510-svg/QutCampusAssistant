package cn.edu.qut.campus.data.model

data class CampusPeriod(
    val periodNumber: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    val startTimeFormatted: String
        get() = String.format("%02d:%02d", startHour, startMinute)

    val endTimeFormatted: String
        get() = String.format("%02d:%02d", endHour, endMinute)

    companion object {
        // 青岛理工大学黄岛校区常规与错峰作息
        val HUANGDAO_PERIODS = listOf(
            CampusPeriod(1, 8, 0, 8, 50),
            CampusPeriod(2, 9, 0, 9, 50),
            CampusPeriod(3, 10, 5, 10, 55),
            CampusPeriod(4, 11, 5, 11, 55),
            CampusPeriod(5, 14, 0, 14, 50),
            CampusPeriod(6, 15, 0, 15, 50),
            CampusPeriod(7, 16, 5, 16, 55),
            CampusPeriod(8, 17, 5, 17, 55),
            CampusPeriod(9, 19, 0, 19, 50),
            CampusPeriod(10, 20, 0, 20, 50)
        )

        // 青岛理工大学市北校区常规作息（抚顺路校区，与黄岛校区统一作息规范）
        val SHIBEI_PERIODS = listOf(
            CampusPeriod(1, 8, 0, 8, 50),
            CampusPeriod(2, 9, 0, 9, 50),
            CampusPeriod(3, 10, 5, 10, 55),
            CampusPeriod(4, 11, 5, 11, 55),
            CampusPeriod(5, 14, 0, 14, 50),
            CampusPeriod(6, 15, 0, 15, 50),
            CampusPeriod(7, 16, 5, 16, 55),
            CampusPeriod(8, 17, 5, 17, 55),
            CampusPeriod(9, 19, 0, 19, 50),
            CampusPeriod(10, 20, 0, 20, 50)
        )

        val ALL_CAMPUSES = listOf("黄岛校区", "市北校区")

        fun getPeriods(campus: String = "黄岛校区"): List<CampusPeriod> {
            return if (campus.contains("市北")) SHIBEI_PERIODS else HUANGDAO_PERIODS
        }

        fun getTimeRange(startPeriod: Int, endPeriod: Int, campus: String = "黄岛校区"): Pair<String, String> {
            val list = getPeriods(campus)
            val start = list.getOrNull(startPeriod - 1) ?: list.first()
            val end = list.getOrNull(endPeriod - 1) ?: list.last()
            return Pair(start.startTimeFormatted, end.endTimeFormatted)
        }
    }
}
