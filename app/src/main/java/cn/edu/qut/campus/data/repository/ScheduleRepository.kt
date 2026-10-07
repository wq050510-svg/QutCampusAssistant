package cn.edu.qut.campus.data.repository

import cn.edu.qut.campus.QutApplication
import cn.edu.qut.campus.data.local.AppPreferences
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 数据仓库：网络抓取 + Room 缓存 + 偏好读写。
 *
 * 本版本重点解决三类问题：
 * 1. **不会再把本地数据清空**：教务返回空列表或结构异常时保留旧数据；
 * 2. **清空与写入在同一事务内**（DAO 的 replaceAll），中途失败不会留下空表；
 * 3. **并发登录收敛为一次**：首屏多个数据源同时发现会话失效时不再并排重登。
 */
class ScheduleRepository(
    private val client: ZhengFangClient = ZhengFangClient(),
    private val app: QutApplication = QutApplication.instance
) {
    private val db = app.database
    val prefs: AppPreferences = app.preferences
    private val gson = Gson()

    private val loginMutex = Mutex()

    @Volatile
    private var lastLoginAt = 0L

    /** 学期参数不再写死：跟随用户设置（默认由开学日期推导） */
    private val xnm: String get() = prefs.termYear.toString()
    private val xqm: String get() = prefs.termSemesterCode.toString()

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

    /**
     * 自动重登以确保会话有效。
     * 登录窗口期内的重复调用会直接复用刚建立的会话，
     * 避免首屏「成绩 + 学业 + 课表 + 考试」同时发现会话失效后并发登录 4 次。
     */
    suspend fun ensureLoggedIn(): Boolean {
        // 凭据读取包含一次 Keystore 解密，放到 IO 线程避免占用主线程
        val (uid, pwd) = withContext(Dispatchers.IO) { prefs.studentId to prefs.password }
        if (uid.isEmpty() || pwd.isEmpty()) return false
        if (System.currentTimeMillis() - lastLoginAt < LOGIN_REUSE_WINDOW_MS) return true
        return loginMutex.withLock {
            if (System.currentTimeMillis() - lastLoginAt < LOGIN_REUSE_WINDOW_MS) return@withLock true
            val result = try {
                if (prefs.loginType == "sso") client.loginViaSSO(uid, pwd) else client.login(uid, pwd)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val ok = result?.isSuccess == true
            if (ok) lastLoginAt = System.currentTimeMillis()
            ok
        }
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
            val data = res.getOrThrow()
            val cleanName = data.overview.studentName
                .replace("bold\">", "")
                .replace("&nbsp;", "")
                .replace(">", "")
                .replace("\"", "")
                .trim()
            val finalData = if (cleanName.isNotEmpty()) {
                prefs.studentName = cleanName
                data.copy(overview = data.overview.copy(studentName = cleanName))
            } else {
                data
            }
            if (finalData.overview.officialGpa > 0.0 || finalData.modules.isNotEmpty()) {
                prefs.academicProgressJson = gson.toJson(finalData)
                _academicProgressFlow.value = finalData
                prefs.lastSyncAt = System.currentTimeMillis()
            }
            return Result.success(finalData)
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
        val grades = gradesRes.getOrNull()
        // 关键：只有成功且拿到非空数据才覆盖本地缓存。
        // 教务返回空列表/结构异常时保留旧成绩，避免「同步成功」把成绩清空。
        if (gradesRes.isSuccess && !grades.isNullOrEmpty()) {
            db.gradeDao().replaceAll(grades.map { GradeEntity.fromModel(it) })
            prefs.lastSyncAt = System.currentTimeMillis()
        }
        return gradesRes
    }

    suspend fun syncSchedule(): Result<List<Course>> {
        var res = client.fetchSchedule(xnm, xqm)
        if (res.isFailure) {
            if (ensureLoggedIn()) {
                res = client.fetchSchedule(xnm, xqm)
            }
        }
        val courses = res.getOrNull()
        if (res.isSuccess && !courses.isNullOrEmpty()) {
            db.courseDao().replaceAll(courses.map { CourseEntity.fromModel(it) })
            prefs.lastSyncAt = System.currentTimeMillis()
        }
        return res
    }

    suspend fun syncExams(): Result<List<Exam>> {
        var res = client.fetchExams(xnm, xqm)
        if (res.isFailure) {
            if (ensureLoggedIn()) {
                res = client.fetchExams(xnm, xqm)
            }
        }
        val exams = res.getOrNull()
        if (res.isSuccess && !exams.isNullOrEmpty()) {
            db.examDao().replaceAll(exams.map { ExamEntity.fromModel(it) })
            prefs.lastSyncAt = System.currentTimeMillis()
        }
        return res
    }

    /**
     * 登录后首次进入时补齐本地缺失的数据。
     * 只同步「本地为空」的部分，避免每次冷启动都发一轮全量请求（耗电且可能触发教务风控）。
     */
    suspend fun autoSyncIfEmpty() {
        if (!prefs.isLoggedIn) return
        val count = db.gradeDao().getCount()
        if (count == 0) {
            syncGrades()
        }
        if (_academicProgressFlow.value == null) {
            syncAcademicProgress()
        }
    }

    suspend fun loginAndSyncAll(studentId: String, password: String, isSso: Boolean = true): Result<User> {
        val loginResult = if (isSso) {
            client.loginViaSSO(studentId, password)
        } else {
            client.login(studentId, password)
        }
        if (loginResult.isFailure) {
            return loginResult
        }

        val user = loginResult.getOrThrow()
        withContext(Dispatchers.IO) {
            prefs.studentId = studentId
            // 密码写入会做一次 Keystore 加密，同样放到 IO 线程
            prefs.password = password
            prefs.loginType = if (isSso) "sso" else "zf"
            // 只有真的拿到信息才落盘，避免用占位值覆盖已有姓名/班级/专业
            if (user.name.isNotBlank()) prefs.studentName = user.name
            if (user.className.isNotBlank()) prefs.studentClass = user.className
            if (user.major.isNotBlank()) prefs.studentMajor = user.major
            prefs.isLoggedIn = true
        }
        lastLoginAt = System.currentTimeMillis()

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

    /**
     * 退出登录时清空本地缓存：课表 / 考试 / 成绩 / 学业画像。
     * 界面上的退出提示承诺了「清除所有离线缓存」，之前只清了偏好设置，Room 数据仍在。
     */
    suspend fun clearAllLocalData() = withContext(Dispatchers.IO) {
        runCatching { db.courseDao().clearAll() }
        runCatching { db.examDao().clearAll() }
        runCatching { db.gradeDao().clearAll() }
        prefs.academicProgressJson = ""
        _academicProgressFlow.value = null
        prefs.lastSyncAt = 0L
        lastLoginAt = 0L
    }

    // 根据开学日期计算当前是第几周
    fun calculateCurrentWeek(): Int {
        return try {
            val start = LocalDate.parse(prefs.termStartDate)
            val today = LocalDate.now()
            val daysBetween = ChronoUnit.DAYS.between(start, today)
            if (daysBetween < 0) 1 else ((daysBetween / 7).toInt() + 1).coerceIn(1, MAX_WEEK)
        } catch (e: Exception) {
            1
        }
    }

    companion object {
        /** 学期最多 30 周，防止异常数据导致 UI 越界 */
        const val MAX_WEEK = 30

        /** 该窗口内认为会话仍然可用，避免并发重复登录 */
        private const val LOGIN_REUSE_WINDOW_MS = 60_000L
    }
}
