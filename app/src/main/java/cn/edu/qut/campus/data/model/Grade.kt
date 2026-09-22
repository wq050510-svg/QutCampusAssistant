package cn.edu.qut.campus.data.model

data class Grade(
    val id: String,
    val courseName: String,
    val score: String, // 68 或 优秀
    val scoreNumber: Double, // 68.0
    val gradePoint: Double, // 绩点 2.30
    val credit: Double, // 学分
    val courseType: String, // 必修 / 选修 / 通识
    val academicYear: String, // 2023-2024
    val semester: String, // 1 或 2
    val examNature: String = "正常考试", // 考试性质: 正常考试 / 补考一 / 重修 / 重修补考
    val college: String = ""
) {
    val isPassed: Boolean
        get() = gradePoint > 0.0 || scoreNumber >= 60.0 || score in listOf("及格", "中等", "良好", "优秀", "合格", "通过", "免修")

    companion object {
        /**
         * 成绩去重规则：
         * 同一门课程若有多次成绩记录（如正常考试挂科、补考、重修考试、重修补考）：
         * 1. 若重修/补考通过了，只保留通过后的最高成绩/最终有效成绩，剔除历史未通过记录；
         * 2. 若均未通过，保留历史最高成绩作为 1 条未通过记录，防止重复计算多门挂科与学分。
         */
        fun deduplicateGrades(allGrades: List<Grade>): List<Grade> {
            val grouped = allGrades.groupBy { it.courseName }
            val result = mutableListOf<Grade>()
            for ((_, list) in grouped) {
                val passedRecords = list.filter { it.isPassed }
                if (passedRecords.isNotEmpty()) {
                    // 提取通过记录中绩点最高或成绩最高的那条
                    val best = passedRecords.maxWithOrNull(
                        compareBy<Grade> { it.gradePoint }
                            .thenBy { it.scoreNumber }
                    ) ?: passedRecords.first()
                    result.add(best)
                } else {
                    // 均未通过，只保留最高分作为 1 条未通过记录
                    val best = list.maxWithOrNull(
                        compareBy<Grade> { it.scoreNumber }
                    ) ?: list.first()
                    result.add(best)
                }
            }
            return result.sortedWith(
                compareByDescending<Grade> { it.academicYear }
                    .thenByDescending { it.semester }
                    .thenBy { it.courseName }
            )
        }
    }
}
