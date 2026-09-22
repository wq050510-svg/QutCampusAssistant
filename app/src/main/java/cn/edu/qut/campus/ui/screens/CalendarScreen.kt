package cn.edu.qut.campus.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.service.CalendarSyncManager
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

    var selectedReminderMinutes by remember { mutableStateOf(20) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncResultMsg by remember { mutableStateOf<String?>(null) }

    // 运行时日历权限请求器
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_CALENDAR] == true &&
                      permissions[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            scope.launch {
                isSyncing = true
                val courses = repository.coursesFlow.first()
                val result = syncManager.syncCoursesToCalendar(courses, reminderMinutes = selectedReminderMinutes)
                isSyncing = false
                syncResultMsg = if (result.isSuccess) {
                    "同步成功！已将 ${result.getOrNull()} 节黄岛校区课程写入手机日历，并开启提前 ${selectedReminderMinutes} 分钟提醒！"
                } else {
                    "同步失败：${result.exceptionOrNull()?.message}"
                }
            }
        } else {
            syncResultMsg = "请在系统设置中允许日历读写权限，以便将课程写入手机日历"
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                        text = "独立日历本隔离：将在日历中创建独立的【青岛理工大学(黄岛校区)课表】，随时可一键重写或清空，绝不污染您的个人私人日程。",
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(15, 20, 30, 45).forEach { min ->
                    val isSelected = selectedReminderMinutes == min
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedReminderMinutes = min },
                        label = { Text("提前 $min 分钟") },
                        shape = RoundedCornerShape(8.dp)
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
                        scope.launch {
                            isSyncing = true
                            val courses = repository.coursesFlow.first()
                            val result = syncManager.syncCoursesToCalendar(courses, reminderMinutes = selectedReminderMinutes)
                            isSyncing = false
                            syncResultMsg = if (result.isSuccess) {
                                "同步成功！已将 ${result.getOrNull()} 节课程排入系统日历，设置提前 ${selectedReminderMinutes} 分钟提醒！"
                            } else {
                                "同步失败：${result.exceptionOrNull()?.message}"
                            }
                        }
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
                        syncManager.clearCalendar()
                        syncResultMsg = "已成功清空系统日历中的青理课表日程"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("清空已同步的日历课表")
            }

            if (syncResultMsg != null) {
                Spacer(modifier = Modifier.height(20.dp))
                AlertDialog(
                    onDismissRequest = { syncResultMsg = null },
                    confirmButton = {
                        TextButton(onClick = { syncResultMsg = null }) {
                            Text("知道了")
                        }
                    },
                    title = { Text("日历同步结果") },
                    text = { Text(syncResultMsg!!) }
                )
            }
        }
    }
}
