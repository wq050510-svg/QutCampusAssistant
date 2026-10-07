package cn.edu.qut.campus.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.qut.campus.data.local.AppPreferences
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.service.CalendarSyncManager
import cn.edu.qut.campus.ui.components.readableSyncError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    repository: ScheduleRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncManager = remember { CalendarSyncManager(context) }
    val prefs = repository.prefs
    val snackbarHostState = remember { SnackbarHostState() }

    // P1: 本页由 MainNavigation 用 if (showCalendarSync) 切出来，不在返回栈上，
    // 不拦截系统返回键会直接退出 App。统一交回 onBack()。
    BackHandler(enabled = true) { onBack() }

    // P1: 提醒时长以 prefs.calendarReminderMinutes 为唯一数据源，选项与「我的」页共用
    // AppPreferences.REMINDER_OPTIONS；进入页面即读取当前值，点击后立即写回。
    var selectedReminderMinutes by remember { mutableStateOf(prefs.calendarReminderMinutes) }
    var isSyncing by remember { mutableStateOf(false) }

    // P1: 校区改用 Flow 驱动，避免组合期快照导致切到市北后本页标题仍显示黄岛
    val campus = prefs.campusFlow.collectAsStateWithLifecycle().value.ifEmpty { "黄岛校区" }

    // 打开本应用的系统设置页，供权限被拒绝时引导用户手动开启
    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        runCatching { context.startActivity(intent) }
    }

    // 写回唯一数据源：CalendarSyncManager 与「我的」页读到的都是这个值
    fun applyReminderMinutes(minutes: Int) {
        selectedReminderMinutes = minutes
        prefs.calendarReminderMinutes = minutes
    }

    // 权限已就绪时真正执行写入
    fun performCalendarSync() {
        if (isSyncing) return
        scope.launch {
            isSyncing = true
            try {
                val courses = repository.coursesFlow.first()
                val minutes = prefs.calendarReminderMinutes
                val result = syncManager.syncCoursesToCalendar(
                    courses,
                    reminderMinutes = minutes,
                    campus = campus
                )
                if (result.isSuccess) {
                    snackbarHostState.showSnackbar(
                        "同步成功！已将 ${result.getOrNull()} 节${campus}课程写入手机日历，并开启提前 ${minutes} 分钟提醒"
                    )
                } else {
                    snackbarHostState.showSnackbar("同步失败：${readableSyncError(result.exceptionOrNull())}")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("同步失败：${readableSyncError(e)}")
            } finally {
                isSyncing = false
            }
        }
    }

    // 运行时日历权限请求器
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_CALENDAR] == true &&
                      permissions[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            performCalendarSync()
        } else {
            scope.launch {
                val action = snackbarHostState.showSnackbar(
                    message = "未获得日历读写权限，无法把课表写入手机日历",
                    actionLabel = "去设置",
                    withDismissAction = true,
                    duration = SnackbarDuration.Long
                )
                if (action == SnackbarResult.ActionPerformed) {
                    openAppSettings()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机日历智能同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // P2: 内容总高超过 600dp，小屏/大字体下不加纵向滚动会导致底部按钮被裁掉
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图标与说明卡片
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "青岛理工大学课表日历联动",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "通过 Android 系统级 CalendarProvider，将全学期每节课按单双周精准写入系统日历，支持手机负一屏速览与系统级准时提醒。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 专属隔离说明
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "独立日历本隔离：将在日历中创建独立的【青岛理工大学(${campus})课表】，随时可一键重写或清空，绝不污染您的个人私人日程。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 提醒时间选择器
            Text(
                text = "上课前提前提醒时长：",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // P1: 与「我的」页共用 AppPreferences.REMINDER_OPTIONS，杜绝两套选项互相覆盖
                AppPreferences.REMINDER_OPTIONS.forEach { min ->
                    FilterChip(
                        selected = selectedReminderMinutes == min,
                        onClick = { applyReminderMinutes(min) },
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("$min 分钟", fontSize = 11.sp, maxLines = 1)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // 同步按钮
            Button(
                onClick = {
                    val readCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                    val writeCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
                    if (readCheck == PackageManager.PERMISSION_GRANTED && writeCheck == PackageManager.PERMISSION_GRANTED) {
                        performCalendarSync()
                    } else {
                        calendarPermissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在同步计算全学期日程...")
                } else {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即一键同步至系统日历")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 清除按钮
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            syncManager.clearCalendar(campus)
                            snackbarHostState.showSnackbar("已成功清空系统日历中的青理课表日程")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("清空失败：${readableSyncError(e)}")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("清空已同步的日历课表")
            }

            // 底部留白，配合 verticalScroll 保证小屏也能完整看到最后一个按钮
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
