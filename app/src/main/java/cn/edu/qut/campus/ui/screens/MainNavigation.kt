package cn.edu.qut.campus.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.repository.ScheduleRepository

/**
 * 底部导航项。标题以字符串资源 id 保存（枚举构造参数不是 composable，
 * 不能在构造期解析文案），UI 侧统一用 stringResource(tab.titleRes) 取文案。
 */
enum class ScreenTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    SCHEDULE(R.string.nav_tab_schedule, Icons.Default.CalendarToday),
    EXAMS(R.string.nav_tab_exams, Icons.Default.EventNote),
    GRADES(R.string.nav_tab_grades, Icons.Default.Assessment),
    ACADEMIC(R.string.nav_tab_academic, Icons.Default.School),
    PROFILE(R.string.nav_tab_profile, Icons.Default.Person)
}

@Composable
fun MainScreen(
    repository: ScheduleRepository,
    onLogout: () -> Unit
) {
    // rememberSaveable：切页/旋屏/进程恢复后仍停留在原来的 Tab
    var currentTab by rememberSaveable { mutableStateOf(ScreenTab.SCHEDULE) }
    var showCalendarSync by rememberSaveable { mutableStateOf(false) }

    // 登录后补齐本地缺失的数据（只同步缺的部分，且登录窗口期内不会重复登录）
    LaunchedEffect(Unit) {
        repository.autoSyncIfEmpty()
    }

    // 系统返回键：
    // 1) 在日历页 → 回主界面；2) 在非课表 Tab → 回课表；3) 课表页 → 交给系统退出
    BackHandler(enabled = showCalendarSync) { showCalendarSync = false }
    BackHandler(enabled = !showCalendarSync && currentTab != ScreenTab.SCHEDULE) {
        currentTab = ScreenTab.SCHEDULE
    }

    if (showCalendarSync) {
        CalendarScreen(
            repository = repository,
            onBack = { showCalendarSync = false }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    ScreenTab.entries.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.titleRes)) },
                            label = { Text(stringResource(tab.titleRes)) },
                            selected = currentTab == tab,
                            onClick = { currentTab = tab }
                        )
                    }
                }
            }
        ) { padding ->
            androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
                when (currentTab) {
                    ScreenTab.SCHEDULE -> ScheduleScreen(
                        repository = repository,
                        onNavigateToCalendar = { showCalendarSync = true }
                    )
                    ScreenTab.EXAMS -> ExamsScreen(repository = repository)
                    ScreenTab.GRADES -> GradesScreen(repository = repository)
                    ScreenTab.ACADEMIC -> AcademicScreen(repository = repository)
                    ScreenTab.PROFILE -> ProfileScreen(
                        repository = repository,
                        onLogout = onLogout,
                        onNavigateToCalendar = { showCalendarSync = true }
                    )
                }
            }
        }
    }
}
