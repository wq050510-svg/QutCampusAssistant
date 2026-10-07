package cn.edu.qut.campus.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.qut.campus.QutApplication
import cn.edu.qut.campus.R
import cn.edu.qut.campus.ui.screens.LoginScreen
import cn.edu.qut.campus.ui.screens.MainScreen
import cn.edu.qut.campus.ui.theme.QutCampusAssistantTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用内选了深色（或跟随系统且系统为深色）时先套用深色窗口主题，
        // 避免「系统浅色 + 应用内深色」这一组合下启动首帧闪白
        val darkModeOption = QutApplication.instance.preferences.darkModeOption
        val systemInNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (darkModeOption == 2 || (darkModeOption == 0 && systemInNight)) {
            setTheme(R.style.Theme_QutCampusAssistant_Dark)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = QutApplication.instance
            // 仓库为全进程单例：之前放在 Activity 里 by lazy，
            // 旋屏重建会产生新实例，且首次构造压在首帧组合期（Room 构建 + 读盘 + JSON 解析）
            val repository = remember { app.repository }
            val darkModeOption by app.preferences.darkModeFlow.collectAsStateWithLifecycle()
            val systemInDark = isSystemInDarkTheme()
            val isDark = when (darkModeOption) {
                1 -> false
                2 -> true
                else -> systemInDark
            }

            QutCampusAssistantTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // rememberSaveable：旋屏 / 分屏 / 系统回收后不再被打回登录页
                    var isLoggedIn by rememberSaveable {
                        mutableStateOf(app.preferences.isLoggedIn)
                    }

                    if (isLoggedIn) {
                        MainScreen(
                            repository = repository,
                            onLogout = { isLoggedIn = false }
                        )
                    } else {
                        LoginScreen(
                            repository = repository,
                            onLoginSuccess = { isLoggedIn = true }
                        )
                    }
                }
            }
        }
    }
}
