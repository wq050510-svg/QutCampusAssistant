package cn.edu.qut.campus.data.repository

import cn.edu.qut.campus.QutApplication
import cn.edu.qut.campus.data.local.CourseEntity
import cn.edu.qut.campus.data.local.ExamEntity
import cn.edu.qut.campus.data.local.GradeEntity
import cn.edu.qut.campus.data.model.AcademicProgress
import cn.edu.qut.campus.data.model.Course
import cn.edu.qut.campus.data.model.Exam
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.model.User
import cn.edu.qut.campus.data.network.ZhengFangClient
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class ScheduleRepository(
    private val client: ZhengFangClient = ZhengFangClient(),
    private val app: QutApplication = QutApplication.instance
) {
    private val db = app.database
    private val prefs = app.preferences
    private val gson = Gson()

    val coursesFlow: Flow<List<Course>> = db.courseDao().getAllCourses().map { list ->
        list.map { it.toModel() }
    }

    val examsFlow: Flow<List<Exam>> = db.examDao().getAllExams().map { list ->
        list.map { it.toModel() }
    }

    val gradesFlow: Flow<List<Grade>> = db.gradeDao().getAllGrades().map { list ->
        list.map { it.toModel() }
    }

    private val _academicProgressFlow = MutableStateFlow<AcademicProgress?>(loadAcademicProgressFromPrefs())
    val academicProgressFlow: StateFlow<AcademicProgress?> = _academicProgressFlow.asStateFlow()

    private fun loadAcademicProgressFromPrefs(): AcademicProgress? {
        val json = prefs.academicProgressJson
        if (json.isEmpty()) return null
        return try {
            val res = gson.fromJson(json, AcademicProgress::class.java)
            if (res != null && (res.overview.officialGpa > 0.0 || res.modules.isNotEmpty())) {
                res
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // 自动重登以确保会话有效
    suspend fun ensureLoggedIn(): Boolean {
        val uid = prefs.studentId
        val pwd = prefs.password
        if (uid.isEmpty() || pwd.isEmpty()) return false
        val res = client.login(uid, pwd)
        return res.isSuccess
    }

    suspend fun syncAcademicProgress(): Result<AcademicProgress> {
        var res = client.fetchAcademicProgress(prefs.studentId)
        if (res.isFailure) {
            // 会话失效，自动重新登录后重试一次
            if (ensureLoggedIn()) {
                res = client.fetchAcademicProgress(prefs.studentId)
            }
        }
        if (res.isSuccess) {
            var data = res.getOrThrow()
            var cleanName = data.overview.studentName
                .replace("bold\">", "")
                .replace("&nbsp;", "")
                .replace(">", "")
                .replace("\"", "")
                .trim()
            if (cleanName.isEmpty()) {
                cleanName = prefs.studentName.ifEmpty { "青理学子" }
            } else {
                prefs.studentName = cleanName
            }
            data = data.copy(overview = data.overview.copy(studentName = cleanName))
            if (data.overview.officialGpa > 0.0 || data.modules.isNotEmpty()) {
                prefs.academicProgressJson = gson.toJson(data)
                _academicProgressFlow.value = data
            }
        }
        return res
    }

    suspend fun syncGrades(): Result<List<Grade>> {
        var gradesRes = client.fetchGrades()
        if (gradesRes.isFailure) {
            // 会话失效，自动重新登录后重试一次
            if (ensureLoggedIn()) {
                gradesRes = client.fetchGrades()
            }
        }
        if (gradesRes.isSuccess) {
            val grades = gradesRes.getOrNull() ?: emptyList()
            db.gradeDao().clearAll()
            db.gradeDao().insertGrades(grades.map { GradeEntity.fromModel(it) })
        }
        return gradesRes
    }

    suspend fun syncSchedule(): Result<List<Course>> {
        var res = client.fetchSchedule()
        if (res.isFailure) {
            if (ensureLoggedIn()) {
                res = client.fetchSchedule()
            }
        }
        if (res.isSuccess) {
            val courses = res.getOrNull() ?: emptyList()
            db.courseDao().clearAll()
            db.courseDao().insertCourses(courses.map { CourseEntity.fromModel(it) })
        }
        return res
    }

    suspend fun syncExams(): Result<List<Exam>> {
        var res = client.fetchExams()
        if (res.isFailure) {
            if (ensureLoggedIn()) {
                res = client.fetchExams()
            }
        }
        if (res.isSuccess) {
            val exams = res.getOrNull() ?: emptyList()
            db.examDao().clearAll()
            db.examDao().insertExams(exams.map { ExamEntity.fromModel(it) })
        }
        return res
    }

    suspend fun autoSyncIfEmpty() {
        if (prefs.isLoggedIn) {
            val count = db.gradeDao().getCount()
            if (count == 0) {
                syncGrades()
            }
            if (_academicProgressFlow.value == null) {
                syncAcademicProgress()
            }
        }
    }

    suspend fun loginAndSyncAll(studentId: String, password: String): Result<User> {
        val loginResult = client.login(studentId, password)
        if (loginResult.isFailure) {
            return loginResult
        }

        val user = loginResult.getOrThrow()
        prefs.studentId = user.studentId
        prefs.password = password
        prefs.studentName = user.name
        prefs.studentClass = user.className
        prefs.studentMajor = user.major
        prefs.isLoggedIn = true

        // 刷新课表
        syncSchedule()

        // 刷新考试
        syncExams()

        // 刷新成绩
        syncGrades()

        // 刷新学业表现
        syncAcademicProgress()

        return Result.success(user)
    }

    // 根据开学日期计算当前是第几周 (2026-08-31 为第 1 周)
    fun calculateCurrentWeek(): Int {
        return try {
            val start = LocalDate.parse(prefs.termStartDate)
            val today = LocalDate.now()
            val daysBetween = ChronoUnit.DAYS.between(start, today)
            if (daysBetween < 0) 1 else (daysBetween / 7).toInt() + 1
        } catch (e: Exception) {
            3 // 默认兜底第 3 周
        }
    }
}
