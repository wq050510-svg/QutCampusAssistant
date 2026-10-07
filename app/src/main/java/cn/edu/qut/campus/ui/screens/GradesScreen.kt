package cn.edu.qut.campus.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.components.EmptyState
import cn.edu.qut.campus.ui.components.ErrorState
import cn.edu.qut.campus.ui.components.LoadingState
import cn.edu.qut.campus.ui.components.readableSyncError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 自动同步节流窗口：距上次成功同步未超过该时长且本地已有缓存时，
 * 切回成绩 Tab 不再重复请求教务系统（避免每次切 Tab 都白等一次网络往返）。
 */
private const val AUTO_SYNC_THROTTLE_MS = 30 * 60 * 1000L

/**
 * 把 Grade 和只算一次的通过判定绑定在一起。
 * Grade.isPassed 是每次访问都会重建失败关键字列表的 getter，
 * 列表页 / 统计 / 卡片在一帧内会读多次，这里先算好避免同一帧重复计算。
 */
private data class GradedGrade(val grade: Grade, val passed: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(repository: ScheduleRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val grades by repository.gradesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var isRefreshing by remember { mutableStateOf(false) }

    // 三态互斥：syncing(加载中) / syncError(失败) / syncSucceeded+空列表(确实没有数据)
    var syncError by remember { mutableStateOf<String?>(null) }
    var syncSucceeded by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // 本次进入页面是否已经自动同步过（rememberSaveable：旋转/重建后不重复打扰教务系统）
    var autoSyncDone by rememberSaveable { mutableStateOf(false) }
    // 本次进入是否真的发起了同步（节流命中时为 false，此时不能一直显示加载中）
    var syncTriggered by remember { mutableStateOf(false) }

    // 同步中（含本次进入还没跑过自动同步的首帧，避免先闪一帧空状态）
    fun syncInProgress(): Boolean = isRefreshing || (syncTriggered && !syncSucceeded && syncError == null)

    suspend fun doSync(showSuccessToast: Boolean) {
        if (syncInProgress()) return
        isRefreshing = true
        try {
            val res = repository.syncGrades()
            val err = res.exceptionOrNull()
            if (res.isSuccess) {
                syncError = null
                syncSucceeded = true
                repository.prefs.lastSyncAt = System.currentTimeMillis()
                if (showSuccessToast) {
                    Toast.makeText(context, "成绩同步成功", Toast.LENGTH_SHORT).show()
                }
            } else {
                syncError = readableSyncError(err)
                snackbarHostState.showSnackbar(syncError ?: "成绩同步失败")
            }
        } catch (e: CancellationException) {
            // 协程取消不是「同步失败」，必须原样抛出，否则会把页面销毁误报成网络错误
            throw e
        } catch (e: Exception) {
            syncError = readableSyncError(e)
            snackbarHostState.showSnackbar(syncError ?: "成绩同步失败")
        } finally {
            isRefreshing = false
        }
    }

    // 自动同步策略：本次进入只自动同步一次（rememberSaveable），
    // 且距上次成功同步未超过节流窗口且本地已有数据时不再重复打教务系统。
    LaunchedEffect(Unit) {
        if (!autoSyncDone) {
            autoSyncDone = true
            val localEmpty = grades.isEmpty()
            val stale = System.currentTimeMillis() - repository.prefs.lastSyncAt > AUTO_SYNC_THROTTLE_MS
            if (localEmpty || stale) {
                syncTriggered = true
                doSync(showSuccessToast = false)
            }
        }
    }

    // 是否启用重修覆盖去重模式（默认启用：重修通过后只保留通过后的最高成绩）
    var isDeduplicated by remember { mutableStateOf(true) }
    var selectedSemester by remember { mutableStateOf("全部学期") }

    // 基础成绩列表（根据是否去重切换）
    val baseGrades = remember(grades, isDeduplicated) {
        if (isDeduplicated) Grade.deduplicateGrades(grades) else grades
    }

    // Grade.isPassed 每次访问都会重建 failKeywords 列表，这里每门课只判定一次并复用
    val gradedRows = remember(baseGrades) {
        baseGrades.map { grade -> GradedGrade(grade, grade.isPassed) }
    }

    val semesters = remember(baseGrades) {
        listOf("全部学期") + baseGrades.map { "${it.academicYear}-${it.semester}" }.distinct().sortedDescending()
    }

    // 按学期过滤
    val filteredRows = remember(gradedRows, selectedSemester) {
        if (selectedSemester == "全部学期") gradedRows
        else gradedRows.filter { "${it.grade.academicYear}-${it.grade.semester}" == selectedSemester }
    }

    val filteredGrades = remember(filteredRows) { filteredRows.map { it.grade } }

    // 统计指标计算（基于去重后的真实清单，复用已算好的 isPassed）
    val totalCredits = remember(filteredRows) {
        // 已获学分仅统计通过的课程
        filteredRows.filter { it.passed }.sumOf { it.grade.credit }
    }

    val failedCount = remember(filteredRows) {
        filteredRows.count { !it.passed && it.grade.credit > 0 }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("学生成绩查询", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isDeduplicated) "已开启重修覆盖去重 (仅保留最终有效成绩)" else "显示全部历次考试记录 (含未通过重修)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                doSync(showSuccessToast = true)
                            }
                        },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新成绩")
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
        ) {
            // 模式切换与学期横滑筛选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = isDeduplicated,
                    onClick = { isDeduplicated = !isDeduplicated },
                    label = {
                        Text(
                            text = if (isDeduplicated) "✓ 重修已去重" else "全部考试记录",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                semesters.forEach { sem ->
                    FilterChip(
                        selected = sem == selectedSemester,
                        onClick = { selectedSemester = sem },
                        label = { Text(sem, fontSize = 12.sp) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            // 有效学分与待重修/补考总览卡片 (已去除自算绩点，专注有效学分与待补考)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("已获有效学分", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format("%.1f", totalCredits),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("考核通过科目累计", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .height(44.dp)
                            .width(1.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("待重修 / 待补考", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (failedCount == 0) "0 门" else "$failedCount 门",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (failedCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (failedCount == 0) "全部科目已通过" else "需关注补考/重修安排",
                            fontSize = 10.sp,
                            color = if (failedCount > 0) Color(0xFFD32F2F).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // 统计说明条
            if (isDeduplicated) {
                Text(
                    text = "注：已自动过滤被重修/补考覆盖的历史不及格记录，学分与门数无重复计算。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                )
            }

            // 成绩内容区域：加载中 / 同步失败 / 确实无数据 三态严格区分，不共用同一张空状态卡
            val loading = filteredGrades.isEmpty() && syncInProgress()
            val failed = syncError != null && filteredGrades.isEmpty()
            if (loading && !failed) {
                LoadingState(message = "正在直连正方教务系统同步历年成绩...")
            } else if (failed) {
                ErrorState(
                    message = syncError ?: "成绩同步失败，请稍后重试",
                    onRetry = {
                        coroutineScope.launch { doSync(showSuccessToast = true) }
                    }
                )
            } else if (filteredGrades.isEmpty()) {
                EmptyState(
                    title = "暂无成绩记录",
                    description = "教务系统本次返回的成绩为空，可点击下方按钮重新同步历年成绩",
                    actionLabel = "点击立即同步教务处成绩",
                    onAction = {
                        coroutineScope.launch { doSync(showSuccessToast = true) }
                    }
                )
            } else {
                // 成绩明细列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredRows, key = { it.grade.id }) { row ->
                        GradeItemCard(grade = row.grade, passed = row.passed)
                    }
                }
            }
        }
    }
}

@Composable
fun GradeItemCard(grade: Grade, passed: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = grade.courseName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // 考试性质徽章
                    if (grade.examNature.isNotEmpty() && grade.examNature != "正常考试") {
                        Spacer(modifier = Modifier.width(6.dp))
                        val isRetake = grade.examNature.contains("重修")
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isRetake) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = grade.examNature,
                                color = if (isRetake) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // 重修通过特殊徽章
                    if (passed && grade.examNature != "正常考试") {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "通过",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${grade.academicYear} 第${grade.semester}学期 • ${grade.credit}学分 • ${grade.courseType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = grade.score,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (passed) "已获 ${grade.credit} 学分" else "未获学分",
                    fontSize = 11.sp,
                    color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
