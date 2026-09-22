package cn.edu.qut.campus.data.model

data class User(
    val studentId: String,
    val name: String,
    val className: String,
    val major: String,
    val grade: String,
    val campus: String = "黄岛校区",
    val currentTerm: String = "2026-2027-1"
)
