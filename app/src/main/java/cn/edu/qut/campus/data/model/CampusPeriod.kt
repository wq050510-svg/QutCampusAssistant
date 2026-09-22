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

        fun getTimeRange(startPeriod: Int, endPeriod: Int): Pair<String, String> {
            val start = HUANGDAO_PERIODS.getOrNull(startPeriod - 1) ?: HUANGDAO_PERIODS.first()
            val end = HUANGDAO_PERIODS.getOrNull(endPeriod - 1) ?: HUANGDAO_PERIODS.last()
            return Pair(start.startTimeFormatted, end.endTimeFormatted)
        }
    }
}
