package cn.edu.qut.campus.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.local.AppPreferences
import cn.edu.qut.campus.data.model.Exam
import cn.edu.qut.campus.data.model.cleanClassroom
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.components.EmptyState
import cn.edu.qut.campus.ui.components.ErrorState
import cn.edu.qut.campus.ui.components.LoadingState
import cn.edu.qut.campus.ui.viewmodel.ExamsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsScreen(repository: ScheduleRepository) {
    // 状态与同步逻辑都在 ViewModel 里：切 Tab / 旋屏不再丢（原先 remember 会全部重置）
    val vm: ExamsViewModel = viewModel(factory = ExamsViewModel.factory(repository))

    val exams by vm.exams.collectAsStateWithLifecycle()
    // 校区用 Flow 读取，切换校区后本页立即跟着刷新（原来是一次性快照）
    val campus by vm.campus.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()
    // 三态互斥：syncing(加载中) / syncError(失败) / syncSucceeded+空列表(确实没有考试)
    val syncInProgress by vm.syncInProgress.collectAsStateWithLifecycle()
    val syncError by vm.syncError.collectAsStateWithLifecycle()
    val showFinishedExams by vm.showFinishedExams.collectAsStateWithLifecycle()
    // 排序派生结果也由 ViewModel 暴露：未结束按时间正序（最近的排最前）、已结束按时间倒序
    val upcomingExams by vm.upcomingExams.collectAsStateWithLifecycle()
    val finishedExams by vm.finishedExams.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 同步失败文案由 ViewModel 暴露（String?）；SnackbarHostState 属于 UI 层，仍留在页面里消费
    LaunchedEffect(syncError) {
        syncError?.let { message -> snackbarHostState.showSnackbar(message) }
    }

    // 考试页原先没有任何刷新入口，只有登录流程会写入考试数据，这里给出显式刷新；
    // 进入页面时也自动补一次（本次会话只补一次），避免登录后换设备/清库看不到考试。
    LaunchedEffect(Unit) {
        vm.autoSyncIfNeeded()
    }

    val isLoading = exams.isEmpty() && syncInProgress
    val isFailed = syncError != null && exams.isEmpty()
    val isEmpty = exams.isEmpty() && !isLoading && !isFailed

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.exams_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (upcomingExams.isEmpty()) {
                                stringResource(R.string.exams_subtitle_none, campus)
                            } else {
                                stringResource(R.string.exams_subtitle_count, upcomingExams.size, campus)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.refresh() },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.exams_refresh_desc))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillParentMaxHeight(0.6f)) {
                        LoadingState(message = stringResource(R.string.exams_loading))
                    }
                }
            } else if (isFailed) {
                item {
                    Box(modifier = Modifier.fillParentMaxHeight(0.6f)) {
                        ErrorState(
                            message = syncError ?: stringResource(R.string.exams_sync_failed_fallback),
                            onRetry = { vm.refresh() }
                        )
                    }
                }
            } else if (isEmpty) {
                item {
                    Box(modifier = Modifier.fillParentMaxHeight(0.6f)) {
                        EmptyState(
                            title = stringResource(R.string.exams_empty_title),
                            description = stringResource(R.string.exams_empty_desc),
                            actionLabel = stringResource(R.string.exams_empty_action),
                            onAction = { vm.refresh() }
                        )
                    }
                }
            } else if (upcomingExams.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.exams_no_upcoming_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.exams_no_upcoming_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(upcomingExams, key = { it.id }) { exam ->
                    ExamCard(exam = exam, isFinished = false, defaultCampus = campus)
                }
            }

            // 历史已结束考试的折叠展开区
            if (finishedExams.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { vm.toggleFinishedExams() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (showFinishedExams) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showFinishedExams) {
                                stringResource(R.string.exams_collapse_finished, finishedExams.size)
                            } else {
                                stringResource(R.string.exams_expand_finished, finishedExams.size)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }

                if (showFinishedExams) {
                    items(finishedExams, key = { it.id }) { exam ->
                        ExamCard(exam = exam, isFinished = true, defaultCampus = campus)
                    }
                }
            }
        }
    }
}

@Composable
fun ExamCard(exam: Exam, isFinished: Boolean, defaultCampus: String = AppPreferences.DEFAULT_CAMPUS) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFinished) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = exam.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isFinished) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (isFinished) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.exams_finished_badge),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.EventSeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.exams_seat_number, exam.seatNumber),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (isFinished) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.exams_label_time, exam.examTime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFinished) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = if (isFinished) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val examCampus = if (exam.classroom.contains("市北")) {
                    stringResource(R.string.campus_shibei)
                } else if (exam.classroom.contains("黄岛")) {
                    stringResource(R.string.campus_huangdao)
                } else {
                    defaultCampus
                }
                val cleanRoom = cleanClassroom(exam.classroom)
                Text(
                    text = stringResource(R.string.exams_label_classroom, examCampus, cleanRoom, exam.seatNumber),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFinished) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
