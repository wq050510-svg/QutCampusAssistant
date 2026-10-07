package cn.edu.qut.campus.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("qut_campus_prefs", Context.MODE_PRIVATE)

    private val _darkModeFlow = MutableStateFlow(prefs.getInt("key_dark_mode_option", 0))
    val darkModeFlow: StateFlow<Int> = _darkModeFlow.asStateFlow()

    private val _campusFlow = MutableStateFlow(prefs.getString("key_campus", DEFAULT_CAMPUS) ?: DEFAULT_CAMPUS)
    val campusFlow: StateFlow<String> = _campusFlow.asStateFlow()

    var darkModeOption: Int // 0: 跟随系统, 1: 浅色模式, 2: 深色模式
        get() = prefs.getInt("key_dark_mode_option", 0)
        set(value) {
            prefs.edit().putInt("key_dark_mode_option", value).apply()
            _darkModeFlow.value = value
        }

    var studentId: String
        get() = prefs.getString("key_student_id", "")?.trim() ?: ""
        set(value) = prefs.edit().putString("key_student_id", value.trim()).apply()

    // ------------------------------------------------------------------
    // 密码：Keystore 加密后落盘，内存缓存明文避免重复解密
    // ------------------------------------------------------------------
    @Volatile
    private var cachedPassword: String? = null

    var password: String
        get() {
            cachedPassword?.let { return it }
            val encrypted = prefs.getString("key_password_enc", null)
            val legacyPlain = prefs.getString("key_password", null)
            val plain = when {
                !encrypted.isNullOrEmpty() -> CredentialCipher.decrypt(encrypted) ?: return ""
                !legacyPlain.isNullOrEmpty() -> legacyPlain
                else -> ""
            }
            if (!legacyPlain.isNullOrEmpty()) {
                // 老版本明文密码：读取时静默升级为密文，并删除明文
                val reEncrypted = CredentialCipher.encrypt(plain)
                prefs.edit().apply {
                    if (reEncrypted != null) putString("key_password_enc", reEncrypted)
                    remove("key_password")
                }.apply()
            }
            cachedPassword = plain
            return plain
        }
        set(value) {
            cachedPassword = value
            val encrypted = CredentialCipher.encrypt(value)
            prefs.edit().apply {
                if (encrypted != null) {
                    putString("key_password_enc", encrypted)
                } else {
                    // 加密不可用时宁可不保存密码，也不落明文
                    remove("key_password_enc")
                }
                remove("key_password")
            }.apply()
        }

    var studentName: String
        get() = prefs.getString("key_student_name", "") ?: ""
        set(value) = prefs.edit().putString("key_student_name", value).apply()

    var studentClass: String
        get() = prefs.getString("key_student_class", "") ?: ""
        set(value) = prefs.edit().putString("key_student_class", value).apply()

    var studentMajor: String
        get() = prefs.getString("key_student_major", "") ?: ""
        set(value) = prefs.edit().putString("key_student_major", value).apply()

    var campus: String
        get() = prefs.getString("key_campus", DEFAULT_CAMPUS) ?: DEFAULT_CAMPUS
        set(value) {
            prefs.edit().putString("key_campus", value).apply()
            _campusFlow.value = value
        }

    var termStartDate: String
        get() = prefs.getString("key_term_start", DEFAULT_TERM_START) ?: DEFAULT_TERM_START
        set(value) = prefs.edit().putString("key_term_start", value).apply()

    var calendarReminderMinutes: Int
        get() = prefs.getInt("key_reminder_minutes", DEFAULT_REMINDER_MINUTES)
        set(value) = prefs.edit().putInt("key_reminder_minutes", value).apply()

    var academicProgressJson: String
        get() = prefs.getString("key_academic_progress_json", "") ?: ""
        set(value) = prefs.edit().putString("key_academic_progress_json", value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean("key_is_logged_in", false)
        set(value) = prefs.edit().putBoolean("key_is_logged_in", value).apply()

    var loginType: String // "zf" (教务处直连) 或 "sso" (统一身份认证)
        get() = prefs.getString("key_login_type", "sso") ?: "sso"
        set(value) = prefs.edit().putString("key_login_type", value).apply()

    /** 最近一次全量/单项同步成功的时间戳，用于「上次同步」展示与自动同步节流 */
    var lastSyncAt: Long
        get() = prefs.getLong("key_last_sync_at", 0L)
        set(value) = prefs.edit().putLong("key_last_sync_at", value).apply()

    // ------------------------------------------------------------------
    // 学期参数：正方教务的 xnm(学年) / xqm(学期) 不再写死在代码里
    // ------------------------------------------------------------------
    private val termStart: LocalDate
        get() = runCatching { LocalDate.parse(termStartDate) }.getOrElse { LocalDate.now() }

    /** 学年，例如 2026 */
    var termYear: Int
        get() = prefs.getInt("key_term_year", 0).takeIf { it > 0 } ?: termStart.year
        set(value) = prefs.edit().putInt("key_term_year", value).apply()

    /** 正方学期编码：3 = 第一学期（秋季），12 = 第二学期（春季） */
    var termSemesterCode: Int
        get() = prefs.getInt("key_term_semester_code", 0).takeIf { it > 0 }
            ?: if (termStart.monthValue in 1..7) 12 else 3
        set(value) = prefs.edit().putInt("key_term_semester_code", value).apply()

    /** 形如 "2026-2027-1" 的学期显示名，随开学日期自动变化 */
    val termLabel: String
        get() {
            val year = termYear
            val index = if (termSemesterCode == 12) 2 else 1
            return "$year-${year + 1}-$index"
        }

    /** 小组件当前展示的日期偏移（0=今天，1=明天，-1=昨天），进程被杀后仍保留 */
    var widgetDayOffset: Int
        get() = prefs.getInt("key_widget_day_offset", 0)
        set(value) = prefs.edit().putInt("key_widget_day_offset", value).apply()

    fun clear() {
        cachedPassword = null
        prefs.edit().clear().apply()
        _darkModeFlow.value = 0
        _campusFlow.value = DEFAULT_CAMPUS
    }

    companion object {
        const val DEFAULT_CAMPUS = "黄岛校区"
        const val DEFAULT_TERM_START = "2026-08-31"
        const val DEFAULT_REMINDER_MINUTES = 20

        /** 全 App 统一的课前提醒候选值，避免「我的」与「日历」两套选项互相覆盖 */
        val REMINDER_OPTIONS = listOf(10, 15, 20, 30)
    }
}
