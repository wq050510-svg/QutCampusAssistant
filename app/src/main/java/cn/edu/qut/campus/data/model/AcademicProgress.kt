package cn.edu.qut.campus.data.model

data class AcademicOverview(
    val studentName: String = "",
    val officialGpa: Double = 0.0,
    val compulsoryGpa: Double = 0.0,
    val electiveGpa: Double = 0.0,
    val totalPlannedCourses: Int = 0,
    val passedCourses: Int = 0,
    val failedCourses: Int = 0,
    val unstudiedCourses: Int = 0,
    val studyingCourses: Int = 0,
    val totalRequiredCredits: Double = 0.0,
    val totalEarnedCredits: Double = 0.0,
    val totalRemainingCredits: Double = 0.0,
    val auditTime: String = ""
)

data class AcademicModule(
    val name: String,
    val requiredCredits: Double,
    val earnedCredits: Double,
    val unearnedCredits: Double
) {
    val isCompleted: Boolean
        get() = unearnedCredits <= 0.0 || (requiredCredits > 0 && earnedCredits >= requiredCredits)

    val progressPercentage: Float
        get() = if (requiredCredits > 0) ((earnedCredits / requiredCredits) * 100).toFloat().coerceIn(0f, 100f) else 100f
}

data class AcademicProgress(
    val overview: AcademicOverview = AcademicOverview(),
    val modules: List<AcademicModule> = emptyList()
)
