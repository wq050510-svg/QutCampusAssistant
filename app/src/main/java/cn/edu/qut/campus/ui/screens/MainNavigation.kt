package cn.edu.qut.campus.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import cn.edu.qut.campus.data.repository.ScheduleRepository

enum class ScreenTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    SCHEDULE("课表", Icons.Default.CalendarToday),
    EXAMS("考试", Icons.Default.EventNote),
    GRADES("成绩", Icons.Default.Assessment),
    ACADEMIC("学业", Icons.Default.School),
    PROFILE("我的", Icons.Default.Person)
}

@Composable
fun MainScreen(
    repository: ScheduleRepository,
    onLogout: () -> Unit
) {
    var currentTab by remember { mutableStateOf(ScreenTab.SCHEDULE) }
    var showCalendarSync by remember { mutableStateOf(false) }

    if (showCalendarSync) {
        CalendarScreen(
            repository = repository,
            onBack = { showCalendarSync = false }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    ScreenTab.values().forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) },
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
