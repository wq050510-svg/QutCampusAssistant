package cn.edu.qut.campus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.qut.campus.data.model.Exam
import cn.edu.qut.campus.data.model.cleanClassroom
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.components.EmptyState
import cn.edu.qut.campus.ui.components.ErrorState
import cn.edu.qut.campus.ui.components.LoadingState
import cn.edu.qut.campus.ui.components.readableSyncError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.regex.Pattern

/**
 * 自动同步节流窗口：距上次成功同步未超过该时长时，切回考试 Tab 不再重复请求教务系统。
 */
private const val AUTO_SYNC_THROTTLE_MS = 30 * 60 * 1000L

/**
 * 容错解析考试时间 "2026-09-09(14:00-15:50)" 用于排序。
 * 解析失败返回 null，调用方保持原顺序，绝不抛异常。
 */
private fun examStartTimeOrNull(examTime: String): LocalDateTime? = try {
    val dateMatcher = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(examTime)
    if (!dateMatcher.find()) null
    else {
        val dateStr = dateMatcher.group(1)
        val timeMatcher = Pattern.compile("(\\d{2}:\\d{2})").matcher(examTime)
        val startTimeStr = if (timeMatcher.find()) timeMatcher.group(1) else "00:00"
        LocalDateTime.parse("${dateStr}T${startTimeStr}:00")
    }
} catch (e: Exception) {
    null
}

/**
 * 稳定排序：只有解析成功的考试参与排序，解析失败的原样留在末尾，
 * 从而保证「解析失败时保持原顺序」。解析结果预先算好，避免比较器里反复解析。
 */
private fun sortExamsByTime(exams: List<Exam>, descending: Boolean): List<Exam> {
    val withTime = exams.map { it to examStartTimeOrNull(it.examTime) }
    val parsed = withTime.filter { it.second != null }
    val unparsed = withTime.filter { it.second == null }
    val sorted = parsed.sortedWith(
        (if (descending) {
            compareByDescending<Pair<Exam, LocalDateTime?>> { it.second }
        } else {
            compareBy<Pair<Exam, LocalDateTime?>> { it.second }
        }).thenBy { it.first.id }
    ).map { it.first }
    return sorted + unparsed.map { it.first }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsScreen(repository: ScheduleRepository) {
    val coroutineScope = rememberCoroutineScope()
    val exams by repository.examsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showFinishedExams by remember { mutableStateOf(false) }
    // 校区用 Flow 读取，切换校区后本页立即跟着刷新（原来是一次性快照）
    val campus by repository.prefs.campusFlow.collectAsStateWithLifecycle()

    var isSyncing by remember { mutableStateOf(false) }
    // 三态互斥：syncing(加载中) / syncError(失败) / syncSucceeded+空列表(确实没有考试)
    var syncError by remember { mutableStateOf<String?>(null) }
    var syncSucceeded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 本次进入页面是否已经自动同步过，避免每次切回 Tab 都重复打教务系统
    var autoSyncDone by rememberSaveable { mutableStateOf(false) }
    // 本次进入是否真的发起了同步（节流命中时为 false，此时不能一直显示加载中）
    var syncTriggered by remember { mutableStateOf(false) }

    fun syncInProgress(): Boolean = isSyncing || (syncTriggered && !syncSucceeded && syncError == null)

    suspend fun doSync() {
        if (isSyncing) return
        isSyncing = true
        try {
            val res = repository.syncExams()
            if (res.isSuccess) {
                syncError = null
                syncSucceeded = true
                repository.prefs.lastSyncAt = System.currentTimeMillis()
            } else {
                syncError = readableSyncError(res.exceptionOrNull())
                snackbarHostState.showSnackbar(syncError ?: "考试安排同步失败")
            }
        } catch (e: CancellationException) {
            // 协程取消不是同步失败，必须原样抛出
            throw e
        } catch (e: Exception) {
            syncError = readableSyncError(e)
            snackbarHostState.showSnackbar(syncError ?: "考试安排同步失败")
        } finally {
            isSyncing = false
        }
    }

    // 考试页原先没有任何刷新入口，只有登录流程会写入考试数据，这里给出显式刷新；
    // 进入页面时也自动补一次（只补一次），避免登录后换设备/清库看不到考试。
    LaunchedEffect(Unit) {
        if (!autoSyncDone) {
            autoSyncDone = true
            val localEmpty = exams.isEmpty()
            val stale = System.currentTimeMillis() - repository.prefs.lastSyncAt > AUTO_SYNC_THROTTLE_MS
            if (localEmpty || stale) {
                syncTriggered = true
                doSync()
            }
        }
    }

    // 未结束的考试按时间正序（最近的排最前）
    val upcomingExams = remember(exams) {
        sortExamsByTime(exams.filter { !it.isFinished }, descending = false)
    }
    // 已结束的考试按时间倒序（最新的排最前）
    val finishedExams = remember(exams) {
        sortExamsByTime(exams.filter { it.isFinished }, descending = true)
    }

    val isLoading = exams.isEmpty() && syncInProgress()
    val isFailed = syncError != null && exams.isEmpty()
    val isEmpty = exams.isEmpty() && !isLoading && !isFailed

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("考试安排与座号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (upcomingExams.isEmpty()) "暂无未结束考试 • $campus" else "未结束考试: ${upcomingExams.size} 门 • $campus",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { coroutineScope.launch { doSync() } },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新考试安排")
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
                        LoadingState(message = "正在直连正方教务系统同步考试安排与座号...")
                    }
                }
            } else if (isFailed) {
                item {
                    Box(modifier = Modifier.fillParentMaxHeight(0.6f)) {
                        ErrorState(
                            message = syncError ?: "考试安排同步失败，请稍后重试",
                            onRetry = { coroutineScope.launch { doSync() } }
                        )
                    }
                }
            } else if (isEmpty) {
                item {
                    Box(modifier = Modifier.fillParentMaxHeight(0.6f)) {
                        EmptyState(
                            title = "教务系统暂无考试安排",
                            description = "本次同步成功但未返回任何考试，补考/缓考安排公布后可点击下方按钮重新同步",
                            actionLabel = "立即同步考试安排",
                            onAction = { coroutineScope.launch { doSync() } }
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
                                text = "近期暂无未结束考试",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "已为你自动隐藏已结束的历史考试日程",
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
                        onClick = { showFinishedExams = !showFinishedExams },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (showFinishedExams) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showFinishedExams) "收起已结束考试 (${finishedExams.size} 门)" else "查看已结束考试 (${finishedExams.size} 门)",
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
fun ExamCard(exam: Exam, isFinished: Boolean, defaultCampus: String = "黄岛校区") {
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
                            text = "已结束",
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
                                text = "${exam.seatNumber}号座",
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
                    text = "时间：${exam.examTime}",
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
                val examCampus = if (exam.classroom.contains("市北")) "市北校区" else if (exam.classroom.contains("黄岛")) "黄岛校区" else defaultCampus
                val cleanRoom = cleanClassroom(exam.classroom)
                Text(
                    text = "考场：$examCampus $cleanRoom (座位号: ${exam.seatNumber})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFinished) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
