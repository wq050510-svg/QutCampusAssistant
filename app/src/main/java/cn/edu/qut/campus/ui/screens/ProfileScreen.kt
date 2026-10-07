package cn.edu.qut.campus.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.local.AppPreferences
import cn.edu.qut.campus.data.model.CampusPeriod
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.components.CampusPickerDialog
import cn.edu.qut.campus.ui.components.readableSyncError
import cn.edu.qut.campus.widget.ScheduleWidgetProvider
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    repository: ScheduleRepository,
    onLogout: () -> Unit,
    onNavigateToCalendar: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // 需要复用或需在非 composable 闭包外提前取出的文案
    val defaultStudentName = stringResource(R.string.common_default_student_name)
    val schoolName = stringResource(R.string.common_school_name)
    val fallbackCampus = stringResource(R.string.campus_huangdao)
    // 统一走 repository.prefs（与课表/日历/服务层同一个 SharedPreferences 实例）
    val prefs = repository.prefs
    val darkModeOption by prefs.darkModeFlow.collectAsStateWithLifecycle()

    var isSyncingAll by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showScheduleDetails by remember { mutableStateOf(false) }
    var showCampusDialog by remember { mutableStateOf(false) }

    // P1: 校区改为 Flow 驱动，不再在组合期快照，切换后本页标题/作息表立即联动
    val currentCampus = prefs.campusFlow.collectAsStateWithLifecycle().value.ifEmpty { fallbackCampus }

    // P1: 提醒时长以 prefs.calendarReminderMinutes 为唯一数据源，选项与日历页共用 REMINDER_OPTIONS
    var reminderMinutes by remember { mutableStateOf(prefs.calendarReminderMinutes) }

    // 「正方教务系统已连接」改为展示真实信息：登录方式 + 上次同步时间
    var lastSyncAt by remember { mutableStateOf(prefs.lastSyncAt) }
    val authLabel = if (prefs.loginType == "sso") {
        stringResource(R.string.profile_auth_sso)
    } else {
        stringResource(R.string.profile_auth_zf)
    }

    val appVersionName = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            context.getString(R.string.profile_version_name, pInfo.versionName)
        } catch (e: Exception) {
            context.getString(R.string.profile_version_name, "1.2.0")
        }
    }

    // 全量同步教务处数据
    fun syncAllData() {
        coroutineScope.launch {
            isSyncingAll = true
            try {
                val sRes = repository.syncSchedule()
                val eRes = repository.syncExams()
                val gRes = repository.syncGrades()
                val aRes = repository.syncAcademicProgress()

                if (sRes.isSuccess || gRes.isSuccess || aRes.isSuccess) {
                    // 记录真实的上次同步时间，供身份卡片展示
                    prefs.lastSyncAt = System.currentTimeMillis()
                    lastSyncAt = prefs.lastSyncAt
                    Toast.makeText(context, context.getString(R.string.profile_sync_all_success), Toast.LENGTH_SHORT).show()
                } else {
                    // 错误统一走 readableSyncError，不再直接拼 exception.message
                    Toast.makeText(
                        context,
                        context.getString(R.string.common_sync_failed, readableSyncError(sRes.exceptionOrNull())),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.profile_sync_error, readableSyncError(e)),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isSyncingAll = false
            }
        }
    }

    // 添加小组件到桌面
    fun pinWidgetToHomeScreen() {
        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
        val myProvider = ComponentName(context, ScheduleWidgetProvider::class.java)
        if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(myProvider, null, null)
            Toast.makeText(context, context.getString(R.string.profile_pin_widget_requested), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.profile_pin_widget_unsupported), Toast.LENGTH_LONG).show()
        }
    }

    // 手动刷新小组件
    fun refreshWidget() {
        val refreshIntent = Intent(context, ScheduleWidgetProvider::class.java).apply {
            action = ScheduleWidgetProvider.ACTION_REFRESH
        }
        context.sendBroadcast(refreshIntent)
        Toast.makeText(context, context.getString(R.string.profile_widget_refreshed), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { syncAllData() },
                        enabled = !isSyncingAll
                    ) {
                        if (isSyncingAll) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.profile_action_sync_all))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 青理专属学生身份卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = prefs.studentName.ifEmpty { defaultStudentName },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            val majorClass = listOfNotNull(
                                prefs.studentMajor.takeIf { it.isNotEmpty() },
                                prefs.studentClass.takeIf { it.isNotEmpty() }
                            ).joinToString(" • ").ifEmpty { schoolName }
                            Text(
                                text = majorClass,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                            )
                            Text(
                                text = if (prefs.studentId.isNotEmpty()) stringResource(R.string.profile_student_id, prefs.studentId) else stringResource(R.string.profile_synced),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                // 真实状态：登录方式 + 上次同步时间（0 显示「尚未同步」）
                                text = "$authLabel · ${formatLastSyncTime(context, lastSyncAt)}",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { showCampusDialog = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = currentCampus,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. 教务数据一键全量同步卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.profile_sync_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.profile_sync_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { syncAllData() },
                        enabled = !isSyncingAll,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSyncingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.profile_syncing_all))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.profile_sync_all_button))
                        }
                    }
                }
            }

            // 3. 当前校区教学作息与时间表（数据源：CampusPeriod）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showScheduleDetails = !showScheduleDetails },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(stringResource(R.string.profile_campus_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.profile_campus_expand_hint, currentCampus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showCampusDialog = true }) {
                                Text(stringResource(R.string.profile_switch_campus), fontSize = 12.sp)
                            }
                            IconButton(onClick = { showScheduleDetails = !showScheduleDetails }) {
                                Icon(
                                    imageVector = if (showScheduleDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = showScheduleDetails) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 作息时间统一从 CampusPeriod 渲染，不再与数据源重复维护一份硬编码表
                            val periods = CampusPeriod.getPeriods(currentCampus)
                            val sessionLabels = listOf(
                                stringResource(R.string.profile_session_morning_1),
                                stringResource(R.string.profile_session_morning_2),
                                stringResource(R.string.profile_session_afternoon_1),
                                stringResource(R.string.profile_session_afternoon_2),
                                stringResource(R.string.profile_session_evening)
                            )
                            periods.chunked(2).forEachIndexed { index, group ->
                                val start = group.first()
                                val end = group.last()
                                val periodText = if (group.size > 1) {
                                    stringResource(R.string.profile_period_range, start.periodNumber, end.periodNumber)
                                } else {
                                    stringResource(R.string.profile_period_single, start.periodNumber)
                                }
                                val label = sessionLabels.getOrNull(index)
                                val timeText = buildString {
                                    append("${start.startTimeFormatted} - ${end.endTimeFormatted}")
                                    if (!label.isNullOrEmpty()) append(" ($label)")
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(periodText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    Text(timeText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // 4. 手机日历提醒设置
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_calendar_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.profile_calendar_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(stringResource(R.string.profile_reminder_label), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 选项与日历页统一取自 AppPreferences.REMINDER_OPTIONS，两页不会互相覆盖
                        AppPreferences.REMINDER_OPTIONS.forEach { mins ->
                            FilterChip(
                                selected = reminderMinutes == mins,
                                onClick = {
                                    reminderMinutes = mins
                                    prefs.calendarReminderMinutes = mins
                                    Toast.makeText(context, context.getString(R.string.profile_reminder_set, mins), Toast.LENGTH_SHORT).show()
                                },
                                label = {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(stringResource(R.string.common_minutes_count, mins), fontSize = 11.sp, maxLines = 1)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    FilledTonalButton(
                        onClick = onNavigateToCalendar,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.profile_go_calendar))
                    }
                }
            }

            // 5. 桌面小组件引导与管理
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_widget_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.profile_widget_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { pinWidgetToHomeScreen() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.profile_widget_add), fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { refreshWidget() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.profile_widget_refresh), fontSize = 13.sp)
                        }
                    }
                }
            }

            // 6. 深色模式与外观
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_theme_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.profile_theme_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            0 to stringResource(R.string.profile_theme_follow_system),
                            1 to stringResource(R.string.profile_theme_light),
                            2 to stringResource(R.string.profile_theme_dark)
                        ).forEach { (opt, title) ->
                            FilterChip(
                                selected = darkModeOption == opt,
                                onClick = { prefs.darkModeOption = opt },
                                label = {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(title, fontSize = 12.sp, maxLines = 1)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 7. 软件信息与退出登录
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.profile_app_version), style = MaterialTheme.typography.bodyMedium)
                        Text(appVersionName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.profile_auth_method), style = MaterialTheme.typography.bodyMedium)
                        val authText = if (prefs.loginType == "sso") stringResource(R.string.profile_auth_sso) else stringResource(R.string.profile_auth_zf)
                        Text(authText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { showLogoutDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_logout_button))
                    }
                }
            }

            // 底部留白，确保任何尺寸屏幕均能完整滚动
            Spacer(modifier = Modifier.height(28.dp))
        }

        // 退出登录二次确认对话框
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                icon = {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = { Text(stringResource(R.string.profile_logout_dialog_title)) },
                text = {
                    Text(stringResource(R.string.profile_logout_dialog_message))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLogoutDialog = false
                            coroutineScope.launch {
                                try {
                                    // 确认文案承诺清除离线课表/成绩/考试，这里必须真的清 Room
                                    repository.clearAllLocalData()
                                } catch (e: Exception) {
                                    // 清库失败也必须退出登录，否则用户被困在已失效的会话里
                                } finally {
                                    prefs.clear()
                                    onLogout()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.profile_logout_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        // 切换校区对话框（与课表页共用 ui/components/CampusPickerDialog）
        if (showCampusDialog) {
            CampusPickerDialog(
                current = currentCampus,
                onSelect = { cName ->
                    prefs.campus = cName
                    Toast.makeText(context, context.getString(R.string.profile_campus_switched, cName), Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showCampusDialog = false }
            )
        }
    }
}

/**
 * 容错地把「上次同步」时间戳格式化成可读文案。
 * 0 或异常值统一显示「尚未同步」，绝不因格式化失败而崩溃。
 */
private fun formatLastSyncTime(context: Context, timestamp: Long): String {
    if (timestamp <= 0L) return context.getString(R.string.profile_never_synced)
    return try {
        val date = Date(timestamp)
        val dayText = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date)
        val timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(date)
        val todayText = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        if (dayText == todayText) {
            context.getString(R.string.profile_synced_today, timeText)
        } else {
            "$dayText $timeText"
        }
    } catch (e: Exception) {
        context.getString(R.string.profile_never_synced)
    }
}
