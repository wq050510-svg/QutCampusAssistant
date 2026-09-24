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
        get() {
            val s = score.trim()
            if (s.isEmpty()) return false
            // 明确的挂科、未通过、缺考或违纪关键字
            val failKeywords = listOf("不及格", "不合格", "未通过", "缺考", "作弊", "违纪", "不达标", "缓考", "F")
            if (failKeywords.any { s.contains(it, ignoreCase = true) }) {
                return false
            }
            // 若为纯数字格式成绩
            val num = scoreNumber
            if (num > 0.0) {
                return num >= 60.0
            }
            if (s == "0" || s == "0.0" || s == "00") {
                return false
            }
            // 针对非数字中文字等级（如 合格、良好、中等、优秀、通过、及格、达标、免修、优、良、中 等）：
            // 只要未变红（无挂科关键字且非零），均智能识别为考核通过
            return true
        }

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
                    // 提取通过记录中成绩最高或重修通过的最新记录
                    val best = passedRecords.maxWithOrNull(
                        compareBy<Grade> { it.scoreNumber }
                            .thenBy { it.examNature.contains("重修") }
                            .thenBy { it.academicYear }
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
