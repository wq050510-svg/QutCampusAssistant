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
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.Cipher

class ZhengFangClient {

    private val cookieJar = SimpleCookieJar()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // 单次请求总超时：避免弱网下 connect/read 各自不超时却整体卡死
        .callTimeout(30, TimeUnit.SECONDS)
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
        // 不能用 Math.abs(hashCode())：hashCode 为 Int.MIN_VALUE 时 abs 仍是负数，
        // 会造成负数下标越界，进而让整次课表同步失败
        val index = (name.hashCode() % COURSE_COLORS.size + COURSE_COLORS.size) % COURSE_COLORS.size
        return COURSE_COLORS[index]
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
            val html = client.newCall(loginPageReq).execute().use { res ->
                res.body?.string().orEmpty()
            }

            val tokenMatcher = Pattern.compile("id=\"csrftoken\" value=\"([^\"]+)\"").matcher(html)
            val csrfToken = if (tokenMatcher.find()) tokenMatcher.group(1) else ""

            // 2. 获取 RSA 公钥
            val time = System.currentTimeMillis()
            val keyReq = Request.Builder()
                .url("$BASE_URL/xtgl/login_getPublicKey.html?time=$time")
                .build()
            val keyJson = client.newCall(keyReq).execute().use { res ->
                gson.fromJson(res.body?.string(), JsonObject::class.java)
            } ?: return@withContext Result.failure(IllegalStateException("未获取到登录加密公钥，请稍后重试"))
            val modulus = keyJson.get("modulus")?.asString.orEmpty()
            val exponent = keyJson.get("exponent")?.asString.orEmpty()
            if (modulus.isBlank() || exponent.isBlank()) {
                // 拿不到公钥时绝不退化成明文提交密码
                return@withContext Result.failure(IllegalStateException("登录加密公钥异常，请稍后重试"))
            }

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

            val (statusCode, location) = client.newCall(loginReq).execute().use { res ->
                res.code to res.header("Location").orEmpty()
            }

            if (statusCode in 301..302 || location.contains("index_initMenu")) {
                // 登录成功，访问主页初始化 Session
                val fullLoc = if (location.startsWith("http")) location else "https://jxgl.qut.edu.cn$location"
                val homeReq = Request.Builder().url(fullLoc).build()
                client.newCall(homeReq).execute().close()

                // 姓名/班级/专业由学业模块抓取后回写（此处不再填「青理同学」这类占位名，
                // 之前写死的名字会让用户以为身份信息根本没同步成功）
                val user = User(
                    studentId = studentId,
                    name = "",
                    className = "",
                    major = "",
                    grade = "",
                    campus = ""
                )

                Result.success(user)
            } else {
                Result.failure(Exception("登录失败，请检查学号与密码是否正确"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 统一身份认证直连登录 (sso.qut.edu.cn)
    suspend fun loginViaSSO(username: String, password: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            // 1. 请求教务系统的统一身份认证入口
            val entryUrl = "https://jxgl.qut.edu.cn/sso/ktiotlogin"
            var currentReq = Request.Builder()
                .url(entryUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .build()
            var currentRes = client.newCall(currentReq).execute()

            // 跟踪重定向直到到达 SSO 登录表单页 (200 OK)
            var redirectCount = 0
            while (currentRes.isRedirect && redirectCount < 8) {
                val loc = currentRes.header("Location") ?: break
                val nextUrl = currentRes.request.url.resolve(loc) ?: break
                currentRes.close()
                currentReq = Request.Builder()
                    .url(nextUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .header("Referer", currentReq.url.toString())
                    .get()
                    .build()
                currentRes = client.newCall(currentReq).execute()
                redirectCount++
            }

            val loginPageHtml = currentRes.use { it.body?.string().orEmpty() }
            val loginPageUrl = currentRes.request.url

            // 2. 从表单页面中提取 pid 和 publicKey
            val pidMatcher = Pattern.compile("name=[\"']?pid[\"']?[^>]*value=[\"']?([^\"'\\s>]+)[\"']?|value=[\"']?([^\"'\\s>]+)[\"']?[^>]*name=[\"']?pid[\"']?", Pattern.CASE_INSENSITIVE).matcher(loginPageHtml)
            val pid = if (pidMatcher.find()) {
                val g1 = pidMatcher.group(1)
                if (!g1.isNullOrEmpty()) g1 else pidMatcher.group(2).orEmpty()
            } else ""

            val keyMatcher = Pattern.compile("name=[\"']?publicKey[\"']?[^>]*value=[\"']?([^\"'\\s>]*)[\"']?|value=[\"']?([^\"'\\s>]*)[\"']?[^>]*name=[\"']?publicKey[\"']?", Pattern.CASE_INSENSITIVE).matcher(loginPageHtml)
            val publicKey = if (keyMatcher.find()) {
                val g1 = keyMatcher.group(1)
                if (!g1.isNullOrEmpty()) g1 else keyMatcher.group(2).orEmpty()
            } else ""

            // 3. 密码处理：
            //    页面提供 RSA 公钥时必须加密提交；加密失败则中止（绝不退回明文）。
            //    页面未提供公钥时，按原协议提交，但**仅允许在 https 通道下**，
            //    避免把教务密码发到明文连接上。
            val finalPassword = if (publicKey.isNotBlank()) {
                encryptSsoPassword(password, publicKey)
                    ?: return@withContext Result.failure(
                        IllegalStateException("密码加密失败，为保护密码已中止登录，请稍后重试或改用教务直接登录")
                    )
            } else {
                if (loginPageUrl.scheme != "https") {
                    return@withContext Result.failure(
                        IllegalStateException("统一身份认证页面不是安全连接，为保护密码已中止登录，请稍后重试")
                    )
                }
                password
            }

            // 4. POST 提交统一身份认证表单
            val formBody = FormBody.Builder()
                .add("username", username.trim())
                .add("password", finalPassword)
                .add("pid", pid)
                .build()

            val postReq = Request.Builder()
                .url(loginPageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Referer", loginPageUrl.toString())
                .post(formBody)
                .build()

            var postRes = client.newCall(postReq).execute()

            // 如果返回 200，说明仍在登录页面（通常提示密码错误或验证码）
            if (postRes.code == 200) {
                val errHtml = postRes.use { it.body?.string().orEmpty() }
                val errMatcher = Pattern.compile("id=[\"']?errormes[\"']?[^>]*value=[\"']?([^\"'>]*)[\"']?", Pattern.CASE_INSENSITIVE).matcher(errHtml)
                val errMsg = if (errMatcher.find()) errMatcher.group(1)?.trim().orEmpty() else ""
                val errFinal = if (errMsg.isNotBlank()) errMsg else "统一身份认证失败，请检查账号密码"
                return@withContext Result.failure(Exception(errFinal))
            }

            // 登录成功时会返回 302/303 重定向回教务系统
            if (postRes.isRedirect) {
                var loginRedirectCount = 0
                var currFollowReq = postReq
                while (postRes.isRedirect && loginRedirectCount < 10) {
                    val loc = postRes.header("Location") ?: break
                    val nextUrl = postRes.request.url.resolve(loc) ?: break
                    postRes.close()
                    currFollowReq = Request.Builder()
                        .url(nextUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                        .header("Referer", currFollowReq.url.toString())
                        .get()
                        .build()
                    postRes = client.newCall(currFollowReq).execute()
                    loginRedirectCount++
                }
                postRes.close()

                // 访问教务系统主页以初始化 Session
                val homeReq = Request.Builder()
                    .url("$BASE_URL/xtgl/index_initMenu.html")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .build()
                client.newCall(homeReq).execute().close()

                val user = User(
                    studentId = username,
                    name = "",
                    className = "",
                    major = "",
                    grade = "",
                    campus = ""
                )
                Result.success(user)
            } else {
                Result.failure(Exception("登录响应异常(HTTP ${postRes.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encryptSsoPassword(password: String, pubKeyPem: String): String? {
        return try {
            val cleanKey = pubKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replace("\\s+".toRegex(), "")
            val keyBytes = Base64.decode(cleanKey, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val pubKey = keyFactory.generatePublic(spec)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pubKey)
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            // 加密失败必须让调用方感知并中止，绝不能回退成明文密码
            null
        }
    }

    // 抓取全量课表（常规课 kbList + 实践环节 sjkList）
    // xnm/xqm 由调用方从用户设置传入（不再写死 2026 / 3，否则下一学年会永远抓到空课表）
    suspend fun fetchSchedule(xnm: String, xqm: String): Result<List<Course>> = withContext(Dispatchers.IO) {
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

            val jsonStr = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (!jsonStr.trim().startsWith("{")) {
                return@withContext Result.failure(IllegalStateException("课表获取失败，教务系统会话可能已过期"))
            }
            val root = try {
                gson.fromJson(jsonStr, JsonObject::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext Result.failure(IllegalStateException("课表数据格式错误"))

            // 响应里既没有常规课表也没有实践环节 → 这不是预期的课表结构。
            // 必须报错：否则上层会把「空列表」当成「本学期没课」，从而清空已缓存的课表。
            if (!root.has("kbList") && !root.has("sjkList")) {
                return@withContext Result.failure(
                    IllegalStateException("教务系统未返回课表数据，可能接口已调整或登录已过期")
                )
            }

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

            val jsonStr = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (!jsonStr.trim().startsWith("{")) {
                return@withContext Result.failure(IllegalStateException("成绩获取失败，教务系统会话可能已过期"))
            }
            val root = try {
                gson.fromJson(jsonStr, JsonObject::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext Result.failure(IllegalStateException("成绩数据格式错误"))

            // 既无明细也无总数 → 不是预期结构，报错而不是当作「没有成绩」
            if (!root.has("items") && !root.has("totalResult")) {
                return@withContext Result.failure(
                    IllegalStateException("教务系统未返回成绩数据，可能接口已调整或登录已过期")
                )
            }
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
            val htmlContent = client.newCall(mainReq).execute().use { it.body?.string().orEmpty() }
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
            val kczxJson = client.newCall(kczxReq).execute().use { it.body?.string().orEmpty() }

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
    suspend fun fetchExams(xnm: String, xqm: String): Result<List<Exam>> = withContext(Dispatchers.IO) {
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

            val jsonStr = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (!jsonStr.trim().startsWith("{")) {
                return@withContext Result.failure(IllegalStateException("考试安排获取失败，教务系统会话可能已过期"))
            }
            val root = try {
                gson.fromJson(jsonStr, JsonObject::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext Result.failure(IllegalStateException("考试数据格式错误"))

            // 同上：结构不符时报错，避免「同步成功但清空了考试数据」
            if (!root.has("items") && !root.has("totalResult")) {
                return@withContext Result.failure(
                    IllegalStateException("教务系统未返回考试数据，可能接口已调整或登录已过期")
                )
            }
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
    private val lock = Any()
    private val cookieStore = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            cookieStore.removeAll { old ->
                cookies.any { new -> new.name == old.name && new.domain == old.domain && new.path == old.path }
            }
            cookieStore.addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // 清掉已过期的会话 Cookie，避免拿着失效 Cookie 反复请求
            cookieStore.removeAll { it.expiresAt < now }
            return cookieStore.filter { it.matches(url) }
        }
    }
}
