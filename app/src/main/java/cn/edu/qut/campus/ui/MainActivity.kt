package cn.edu.qut.campus.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import cn.edu.qut.campus.QutApplication
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.screens.LoginScreen
import cn.edu.qut.campus.ui.screens.MainScreen
import cn.edu.qut.campus.ui.theme.QutCampusAssistantTheme

class MainActivity : ComponentActivity() {

    private val repository by lazy { ScheduleRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val darkModeOption by QutApplication.instance.preferences.darkModeFlow.collectAsState()
            val systemInDark = isSystemInDarkTheme()
            val isDark = when (darkModeOption) {
                1 -> false
                2 -> true
                else -> systemInDark
            }

            QutCampusAssistantTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var isLoggedIn by remember {
                        mutableStateOf(QutApplication.instance.preferences.isLoggedIn)
                    }

                    if (isLoggedIn) {
                        LaunchedEffect(Unit) {
                            repository.autoSyncIfEmpty()
                        }
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
