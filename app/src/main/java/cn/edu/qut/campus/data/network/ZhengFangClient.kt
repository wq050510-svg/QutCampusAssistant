package cn.edu.qut.campus.data.network

import android.util.Base64
import cn.edu.qut.campus.data.model.AcademicModule
import cn.edu.qut.campus.data.model.AcademicOverview
import cn.edu.qut.campus.data.model.AcademicProgress
import cn.edu.qut.campus.data.model.Course
import cn.edu.qut.campus.data.model.Exam
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.model.User
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.Cipher

class ZhengFangClient {

    private val cookieJar = SimpleCookieJar()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val gson = Gson()

    companion object {
        const val BASE_URL = "https://jxgl.qut.edu.cn/jwglxt"

        // 莫兰迪/马卡龙护眼课程柔和配色
        val COURSE_COLORS = listOf(
            "#4DB6AC", // 湖水青
            "#64B5F6", // 天空蓝
            "#9575CD", // 优雅紫
            "#FF8A65", // 珊瑚橘
            "#81C784", // 薄荷绿
            "#FFB74D", // 温暖杏
            "#BA68C8", // 丁香紫
            "#4DD0E1", // 清透青
            "#A1887F", // 柔和棕
            "#F06292"  // 樱花粉
        )
    }

    private fun getColorForCourse(name: String): String {
        val hash = Math.abs(name.hashCode())
        return COURSE_COLORS[hash % COURSE_COLORS.size]
    }

    // 原生 RSA 加密
    private fun encryptPassword(password: String, modulusB64: String, exponentB64: String): String {
        val modulusBytes = Base64.decode(modulusB64, Base64.DEFAULT)
        val exponentBytes = Base64.decode(exponentB64, Base64.DEFAULT)
        val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), BigInteger(1, exponentBytes))
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    suspend fun login(studentId: String, password: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            // 1. 获取登录页 csrftoken
            val loginPageReq = Request.Builder()
                .url("$BASE_URL/xtgl/login_slogin.html")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .build()
            val loginPageRes = client.newCall(loginPageReq).execute()
            val html = loginPageRes.body?.string().orEmpty()

            val tokenMatcher = Pattern.compile("id=\"csrftoken\" value=\"([^\"]+)\"").matcher(html)
            val csrfToken = if (tokenMatcher.find()) tokenMatcher.group(1) else ""

            // 2. 获取 RSA 公钥
            val time = System.currentTimeMillis()
            val keyReq = Request.Builder()
                .url("$BASE_URL/xtgl/login_getPublicKey.html?time=$time")
                .build()
            val keyRes = client.newCall(keyReq).execute()
            val keyJson = gson.fromJson(keyRes.body?.string(), JsonObject::class.java)
            val modulus = keyJson.get("modulus").asString
            val exponent = keyJson.get("exponent").asString

            // 3. 执行加密
            val encryptedPassword = encryptPassword(password, modulus, exponent)

            // 4. POST 提交登录
            val formBody = FormBody.Builder()
                .add("csrftoken", csrfToken)
                .add("language", "zh_CN")
                .add("yhm", studentId)
                .add("mm", encryptedPassword)
                .add("hidMm", "")
                .add("ydType", "")
                .build()

            val loginReq = Request.Builder()
                .url("$BASE_URL/xtgl/login_slogin.html")
                .header("Referer", "$BASE_URL/xtgl/login_slogin.html")
                .post(formBody)
                .build()

            val loginRes = client.newCall(loginReq).execute()
            val statusCode = loginRes.code
            val location = loginRes.header("Location").orEmpty()

            if (statusCode in 301..302 || location.contains("index_initMenu")) {
                // 登录成功，访问主页初始化 Session
                val fullLoc = if (location.startsWith("http")) location else "https://jxgl.qut.edu.cn$location"
                val homeReq = Request.Builder().url(fullLoc).build()
                client.newCall(homeReq).execute()

                // 拉取课表初始信息提取用户详情
                val scheduleResult = fetchSchedule("2026", "3")
                val user = User(
                    studentId = studentId,
                    name = "青理同学",
                    className = "",
                    major = "",
                    grade = "",
                    campus = "黄岛校区"
                )

                Result.success(user)
            } else {
                Result.failure(Exception("登录失败，请检查学号与密码是否正确"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 抓取全量课表（常规课 kbList + 实践环节 sjkList）
    suspend fun fetchSchedule(xnm: String = "2026", xqm: String = "3"): Result<List<Course>> = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .add("kzlx", "ck")
                .build()

            val req = Request.Builder()
                .url("$BASE_URL/kbcx/xskbcx_cxXsKb.html?gnmkpath=N2151")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE_URL/kbcx/xskbcx_cxXskbcxIndex.html")
                .post(form)
                .build()

            val res = client.newCall(req).execute()
            val jsonStr = res.body?.string().orEmpty()
            if (!jsonStr.trim().startsWith("{")) {
                return@withContext Result.failure(IllegalStateException("课表获取失败，教务系统会话可能已过期"))
            }
            val root = try {
                gson.fromJson(jsonStr, JsonObject::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext Result.failure(IllegalStateException("课表数据格式错误"))

            val courses = mutableListOf<Course>()

            // 1. 常规教学楼排课
            val kbList = root.getAsJsonArray("kbList")
            if (kbList != null) {
                for (elem in kbList) {
                    val obj = elem.asJsonObject
                    val name = obj.get("kcmc")?.asString.orEmpty()
                    val room = obj.get("cdmc")?.asString.orEmpty()
                    val teacher = obj.get("xm")?.asString.orEmpty()
                    val dayOfWeek = obj.get("xqj")?.asInt ?: 1
                    val jcs = obj.get("jcs")?.asString.orEmpty() // 如 "5-6"
                    val periodParts = jcs.split("-")
                    val startPeriod = periodParts.getOrNull(0)?.toIntOrNull() ?: 1
                    val endPeriod = periodParts.getOrNull(1)?.toIntOrNull() ?: startPeriod
                    val zcd = obj.get("zcd")?.asString.orEmpty() // 如 "4-6周,8-12周(双)"
                    val xf = obj.get("xf")?.asDouble ?: 0.0
                    val cxbj = obj.get("cxbj")?.asString == "1"

                    courses.add(
                        Course(
                            id = "${name}_${dayOfWeek}_${jcs}_${zcd}",
                            name = name,
                            classroom = room,
                            teacher = teacher,
                            dayOfWeek = dayOfWeek,
                            startPeriod = startPeriod,
                            endPeriod = endPeriod,
                            weeksDescription = zcd,
                            weeksList = Course.parseWeeks(zcd),
                            credit = xf,
                            isRetake = cxbj,
                            isPractice = false,
                            colorHex = getColorForCourse(name)
                        )
                    )
                }
            }

            // 2. 实践短学期课程 (如《形势与政策7》)
            val sjkList = root.getAsJsonArray("sjkList")
            if (sjkList != null) {
                for (elem in sjkList) {
                    val obj = elem.asJsonObject
                    val name = obj.get("kcmc")?.asString.orEmpty()
                    val teacher = obj.get("jsxm")?.asString.orEmpty()
                    val zcd = obj.get("qsjsz")?.asString.orEmpty() // 如 "1-4周"
                    val xf = obj.get("xf")?.asDouble ?: 0.0

                    courses.add(
                        Course(
                            id = "practice_${name}_$zcd",
                            name = name,
                            classroom = "实践/线上环节",
                            teacher = teacher,
                            dayOfWeek = 7, // 默认放置于周日或全局栏
                            startPeriod = 11,
                            endPeriod = 12,
                            weeksDescription = zcd,
                            weeksList = Course.parseWeeks(zcd),
                            credit = xf,
                            isRetake = false,
                            isPractice = true,
                            colorHex = getColorForCourse(name)
                        )
                    )
                }
            }

            Result.success(courses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 抓取历年成绩（支持全部记录，含考试性质）
    suspend fun fetchGrades(): Result<List<Grade>> = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("xnm", "")
                .add("xqm", "")
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "500")
                .add("queryModel.currentPage", "1")
                .add("queryModel.sortName", "")
                .add("queryModel.sortOrder", "asc")
                .add("time", "0")
                .build()

            val req = Request.Builder()
                .url("$BASE_URL/cjcx/cjcx_cxDgXscj.html?doType=query&gnmkpath=N305005")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE_URL/cjcx/cjcx_cxDgXscj.html?gnmkpath=N305005&layout=default")
                .post(form)
                .build()

            val res = client.newCall(req).execute()
            val jsonStr = res.body?.string().orEmpty()
            if (!jsonStr.trim().startsWith("{")) {
                return@withContext Result.failure(IllegalStateException("成绩获取失败，教务系统会话可能已过期"))
            }
            val root = try {
                gson.fromJson(jsonStr, JsonObject::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext Result.failure(IllegalStateException("成绩数据格式错误"))
            val items = root.getAsJsonArray("items")

            val grades = mutableListOf<Grade>()
            if (items != null) {
                for (elem in items) {
                    val obj = elem.asJsonObject
                    val name = obj.get("kcmc")?.asString.orEmpty()
                    val cj = obj.get("bfzcj")?.asString ?: obj.get("cj")?.asString.orEmpty()
                    val scoreNum = cj.toDoubleOrNull() ?: 0.0
                    val jd = obj.get("jd")?.asDouble ?: 0.0
                    val xf = obj.get("xf")?.asDouble ?: 0.0
                    val kcxzmc = obj.get("kcxzmc")?.asString.orEmpty()
                    val xnmmc = obj.get("xnmmc")?.asString.orEmpty()
                    val xqmmc = obj.get("xqmmc")?.asString.orEmpty()
                    val ksxz = obj.get("ksxz")?.asString.orEmpty().ifEmpty { "正常考试" }
                    val jgmc = obj.get("jgmc")?.asString.orEmpty()

                    grades.add(
                        Grade(
                            id = "${name}_${xnmmc}_${xqmmc}_$ksxz",
                            courseName = name,
                            score = cj,
                            scoreNumber = scoreNum,
                            gradePoint = jd,
                            credit = xf,
                            courseType = kcxzmc,
                            academicYear = xnmmc,
                            semester = xqmmc,
                            examNature = ksxz,
                            college = jgmc
                        )
                    )
                }
            }

            Result.success(grades)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 抓取学生学业情况查询（官方 GPA、毕业学分进度、各平台模块要求）
    suspend fun fetchAcademicProgress(studentId: String): Result<AcademicProgress> = withContext(Dispatchers.IO) {
        try {
            // 1. 激活功能模块权限
            val bczjReq = Request.Builder()
                .url("$BASE_URL/xtgl/index_cxBczjsygnmk.html")
                .post(FormBody.Builder().add("gndm", "N105515").build())
                .build()
            client.newCall(bczjReq).execute().close()

            // 2. 获取学业情况主页面（含 alertBox 统计卡片与模块学分树）
            val mainUrl = "$BASE_URL/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default"
            val mainReq = Request.Builder()
                .url(mainUrl)
                .header("Referer", "$BASE_URL/xtgl/index_initMenu.html?jsdm=xs")
                .get()
                .build()
            val mainRes = client.newCall(mainReq).execute()
            val htmlContent = mainRes.body?.string().orEmpty()
            if (!htmlContent.contains("alertBox") && !htmlContent.contains("xsxyqk")) {
                return@withContext Result.failure(IllegalStateException("学业情况获取失败，教务系统会话可能已过期"))
            }

            // 3. 查询必修与选修的分项官方 GPA
            val kczxReq = Request.Builder()
                .url("$BASE_URL/xsxy/xsxyqk_cxKczxAllIndex.html?doType=query&gnmkdm=N105515")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", mainUrl)
                .post(FormBody.Builder().add("xh_id", studentId).build())
                .build()
            val kczxRes = client.newCall(kczxReq).execute()
            val kczxJson = kczxRes.body?.string().orEmpty()

            val progress = parseAcademicProgress(htmlContent, kczxJson)
            if (progress.overview.officialGpa == 0.0 && progress.modules.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("学业数据解析为空，会话可能已过期"))
            }
            Result.success(progress)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseAcademicProgress(html: String, kczxJson: String): AcademicProgress {
        var studentName = ""
        var auditTime = ""
        var officialGpa = 0.0
        var totalPlannedCourses = 0
        var passedCourses = 0
        var failedCourses = 0
        var unstudiedCourses = 0
        var studyingCourses = 0
        var totalRequiredCredits = 0.0
        var totalEarnedCredits = 0.0
        var totalRemainingCredits = 0.0

        // 解析 alertBox 卡片
        (Regex("""(?:style="[^"]*">|>)\s*([^<>\s\u00a0&]+)\s*(?:&nbsp;)?\s*同学""").find(html)
            ?: Regex("""([^<>\s\u00a0&]+)\s*同学""").find(html))?.let {
            studentName = it.groupValues[1].replace("\u00a0", "").replace("&nbsp;", "").trim()
        }
        Regex("""统计时间[^\d]*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})""").find(html)?.let {
            auditTime = it.groupValues[1]
        }
        (Regex("""GPA[）\)]\s*[:：]?\s*<font[^>]*>\s*([0-9\.]+)""").find(html)
            ?: Regex("""GPA[）\)]\s*[:：]?\s*([0-9\.]+)""").find(html))?.let {
            officialGpa = it.groupValues[1].toDoubleOrNull() ?: 0.0
        }
        Regex("""计划总课程[^\d]*(\d+)""").find(html)?.let {
            totalPlannedCourses = it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("""通过[^\d]*(\d+)""").find(html)?.let {
            passedCourses = it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("""未通过[^\d]*(\d+)""").find(html)?.let {
            failedCourses = it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("""未修[^\d]*(\d+)""").find(html)?.let {
            unstudiedCourses = it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("""在读[^\d]*(\d+)""").find(html)?.let {
            studyingCourses = it.groupValues[1].toIntOrNull() ?: 0
        }

        // 解析各模块学分
        val modulePattern = Regex(""""([^"&]+)&nbsp;"\s*\+\s*\$\.i18n\.get\('yqxf'\)[^:]*:([0-9\.]*)&nbsp;"\s*\+\s*\$\.i18n\.get\('hdxf'\)[^:]*:([0-9\.]*)&nbsp;&nbsp;"\s*\+\s*\$\.i18n\.get\('whdxf'\)[^:]*:([0-9\.]*)&nbsp;"""")
        val modules = mutableListOf<AcademicModule>()
        val seen = mutableSetOf<String>()

        for (match in modulePattern.findAll(html)) {
            val name = match.groupValues[1].replace("\u00a0", "").trim()
            val yq = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val hd = match.groupValues[3].toDoubleOrNull() ?: 0.0
            val whd = match.groupValues[4].toDoubleOrNull() ?: 0.0

            if (name.isNotEmpty() && seen.add(name)) {
                if (name.contains("指导教学计划") || name.contains("智能建造")) {
                    if (yq > 0) totalRequiredCredits = yq
                    if (hd > 0) totalEarnedCredits = hd
                    if (whd >= 0) totalRemainingCredits = whd
                }
                modules.add(AcademicModule(name, yq, hd, whd))
            }
        }

        // 解析必修 / 选修分项 GPA
        var compulsoryGpa = 0.0
        var electiveGpa = 0.0
        if (kczxJson.isNotEmpty()) {
            try {
                val root = gson.fromJson(kczxJson, JsonObject::class.java)
                val items = root?.getAsJsonArray("items")
                if (items != null) {
                    for (el in items) {
                        val obj = el.asJsonObject
                        val mc = obj.get("kcxzmc")?.asString.orEmpty()
                        val gpaVal = obj.get("gpa")?.asDouble ?: 0.0
                        if (mc == "必修") compulsoryGpa = gpaVal
                        if (mc == "选修") electiveGpa = gpaVal
                    }
                }
            } catch (e: Exception) {
                // ignore json error
            }
        }

        val overview = AcademicOverview(
            studentName = studentName,
            officialGpa = officialGpa,
            compulsoryGpa = compulsoryGpa,
            electiveGpa = electiveGpa,
            totalPlannedCourses = totalPlannedCourses,
            passedCourses = passedCourses,
            failedCourses = failedCourses,
            unstudiedCourses = unstudiedCourses,
            studyingCourses = studyingCourses,
            totalRequiredCredits = totalRequiredCredits,
            totalEarnedCredits = totalEarnedCredits,
            totalRemainingCredits = totalRemainingCredits,
            auditTime = auditTime
        )

        return AcademicProgress(overview = overview, modules = modules)
    }


    // 抓取考试安排（包含考场地点与座位号）
    suspend fun fetchExams(xnm: String = "2026", xqm: String = "3"): Result<List<Exam>> = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "50")
                .add("queryModel.currentPage", "1")
                .add("queryModel.sortName", "")
                .add("queryModel.sortOrder", "asc")
                .build()

            val req = Request.Builder()
                .url("$BASE_URL/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkpath=N358105")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE_URL/kwgl/kscx_cxXsksxxIndex.html?gnmkpath=N358105&layout=default")
                .post(form)
                .build()

            val res = client.newCall(req).execute()
            val jsonStr = res.body?.string().orEmpty()
            if (!jsonStr.trim().startsWith("{")) {
                return@withContext Result.failure(IllegalStateException("考试安排获取失败，教务系统会话可能已过期"))
            }
            val root = try {
                gson.fromJson(jsonStr, JsonObject::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext Result.failure(IllegalStateException("考试数据格式错误"))
            val items = root.getAsJsonArray("items")

            val exams = mutableListOf<Exam>()
            if (items != null) {
                for (elem in items) {
                    val obj = elem.asJsonObject
                    val name = obj.get("kcmc")?.asString.orEmpty()
                    val time = obj.get("kssj")?.asString.orEmpty()
                    val room = obj.get("cdmc")?.asString.orEmpty()
                    val seat = obj.get("zwh")?.asString.orEmpty()

                    exams.add(
                        Exam(
                            id = "${name}_$time",
                            courseName = name,
                            examTime = time,
                            classroom = room,
                            seatNumber = seat
                        )
                    )
                }
            }

            Result.success(exams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// 内存 CookieJar 管理 Session
private class SimpleCookieJar : CookieJar {
    private val cookieStore = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookieStore.removeAll { old -> cookies.any { new -> new.name == old.name } }
        cookieStore.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore.filter { it.matches(url) }
    }
}
