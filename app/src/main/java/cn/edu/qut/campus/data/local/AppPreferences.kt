package cn.edu.qut.campus.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("qut_campus_prefs", Context.MODE_PRIVATE)

    private val _darkModeFlow = MutableStateFlow(prefs.getInt("key_dark_mode_option", 0))
    val darkModeFlow: StateFlow<Int> = _darkModeFlow.asStateFlow()

    var darkModeOption: Int // 0: 跟随系统, 1: 浅色模式, 2: 深色模式
        get() = prefs.getInt("key_dark_mode_option", 0)
        set(value) {
            prefs.edit().putInt("key_dark_mode_option", value).apply()
            _darkModeFlow.value = value
        }

    var studentId: String
        get() = prefs.getString("key_student_id", "") ?: ""
        set(value) = prefs.edit().putString("key_student_id", value).apply()

    var password: String
        get() = prefs.getString("key_password", "") ?: ""
        set(value) = prefs.edit().putString("key_password", value).apply()

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
        get() = prefs.getString("key_campus", "黄岛校区") ?: "黄岛校区"
        set(value) = prefs.edit().putString("key_campus", value).apply()

    var termStartDate: String
        get() = prefs.getString("key_term_start", "2026-08-31") ?: "2026-08-31"
        set(value) = prefs.edit().putString("key_term_start", value).apply()

    var calendarReminderMinutes: Int
        get() = prefs.getInt("key_reminder_minutes", 20)
        set(value) = prefs.edit().putInt("key_reminder_minutes", value).apply()

    var academicProgressJson: String
        get() = prefs.getString("key_academic_progress_json", "") ?: ""
        set(value) = prefs.edit().putString("key_academic_progress_json", value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean("key_is_logged_in", false)
        set(value) = prefs.edit().putBoolean("key_is_logged_in", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
