package cn.edu.qut.campus.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.components.EmptyState
import cn.edu.qut.campus.ui.components.ErrorState
import cn.edu.qut.campus.ui.components.LoadingState
import cn.edu.qut.campus.ui.viewmodel.GradesViewModel

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
    // 状态与同步逻辑都在 ViewModel 里：切 Tab / 旋屏不再丢（原先 remember 会全部重置）
    val vm: GradesViewModel = viewModel(factory = GradesViewModel.factory(repository))

    val grades by vm.grades.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    // 三态互斥：syncing(加载中) / syncError(失败) / syncSucceeded+空列表(确实没有数据)
    val syncInProgress by vm.syncInProgress.collectAsStateWithLifecycle()
    val syncError by vm.syncError.collectAsStateWithLifecycle()
    val syncSuccessEvent by vm.syncSuccessEvent.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 同步失败文案由 ViewModel 暴露（String?）；SnackbarHostState 属于 UI 层，仍留在页面里消费
    LaunchedEffect(syncError) {
        syncError?.let { message -> snackbarHostState.showSnackbar(message) }
    }

    // 手动同步成功的一次性 Toast 事件：消费后立即清除，旋屏不会重复弹
    LaunchedEffect(syncSuccessEvent) {
        if (syncSuccessEvent) {
            Toast.makeText(context, context.getString(R.string.grades_sync_success), Toast.LENGTH_SHORT).show()
            vm.consumeSyncSuccess()
        }
    }

    // 自动同步策略：本次会话只自动同步一次（原先用 rememberSaveable，切 Tab 会丢失）
    LaunchedEffect(Unit) {
        vm.autoSyncIfNeeded()
    }

    // 是否启用重修覆盖去重模式（默认启用：重修通过后只保留通过后的最高成绩）
    // 与学期筛选一起搬进 ViewModel：切 Tab 回来不再被重置
    val isDeduplicated by vm.isDeduplicated.collectAsStateWithLifecycle()
    val selectedSemester by vm.selectedSemester.collectAsStateWithLifecycle()

    // 基础成绩列表（根据是否去重切换）
    val baseGrades = remember(grades, isDeduplicated) {
        if (isDeduplicated) Grade.deduplicateGrades(grades) else grades
    }

    // Grade.isPassed 每次访问都会重建 failKeywords 列表，这里每门课只判定一次并复用
    val gradedRows = remember(baseGrades) {
        baseGrades.map { grade -> GradedGrade(grade, grade.isPassed) }
    }

    val semesters = remember(baseGrades) {
        listOf(GradesViewModel.SEMESTER_ALL) + baseGrades.map { "${it.academicYear}-${it.semester}" }.distinct().sortedDescending()
    }

    // 学期筛选现在由 ViewModel 持有（不再随切 Tab 重置）：万一本地数据被清空/换学期后
    // 停在一个已不存在的学期上，兜底回到「全部学期」，避免列表永远空白
    LaunchedEffect(semesters) {
        if (selectedSemester !in semesters) vm.selectSemester(GradesViewModel.SEMESTER_ALL)
    }

    // 按学期过滤
    val filteredRows = remember(gradedRows, selectedSemester) {
        if (selectedSemester == GradesViewModel.SEMESTER_ALL) gradedRows
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
                        Text(stringResource(R.string.grades_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isDeduplicated) {
                                stringResource(R.string.grades_subtitle_deduplicated)
                            } else {
                                stringResource(R.string.grades_subtitle_all)
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.refresh() },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.grades_refresh_desc))
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
                    onClick = { vm.toggleDeduplicated() },
                    label = {
                        Text(
                            text = if (isDeduplicated) {
                                stringResource(R.string.grades_chip_deduplicated)
                            } else {
                                stringResource(R.string.grades_chip_all)
                            },
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
                        onClick = { vm.selectSemester(sem) },
                        label = { Text(if (sem == GradesViewModel.SEMESTER_ALL) stringResource(R.string.grades_semester_all) else sem, fontSize = 12.sp) },
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
                        Text(stringResource(R.string.grades_valid_credits), fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format("%.1f", totalCredits),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(stringResource(R.string.grades_valid_credits_hint), fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .height(44.dp)
                            .width(1.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.grades_failed_title), fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.grades_failed_count, failedCount),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (failedCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (failedCount == 0) {
                                stringResource(R.string.grades_all_passed)
                            } else {
                                stringResource(R.string.grades_failed_hint)
                            },
                            fontSize = 10.sp,
                            color = if (failedCount > 0) Color(0xFFD32F2F).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // 统计说明条
            if (isDeduplicated) {
                Text(
                    text = stringResource(R.string.grades_dedup_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                )
            }

            // 成绩内容区域：加载中 / 同步失败 / 确实无数据 三态严格区分，不共用同一张空状态卡
            val loading = filteredGrades.isEmpty() && syncInProgress
            val failed = syncError != null && filteredGrades.isEmpty()
            if (loading && !failed) {
                LoadingState(message = stringResource(R.string.grades_loading))
            } else if (failed) {
                ErrorState(
                    message = syncError ?: stringResource(R.string.grades_sync_failed_fallback),
                    onRetry = { vm.refresh() }
                )
            } else if (filteredGrades.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.grades_empty_title),
                    description = stringResource(R.string.grades_empty_desc),
                    actionLabel = stringResource(R.string.grades_empty_action),
                    onAction = { vm.refresh() }
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
                                text = stringResource(R.string.grades_badge_passed),
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
                    text = stringResource(R.string.grades_course_meta, grade.academicYear, grade.semester, grade.credit, grade.courseType),
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
                    text = if (passed) {
                        stringResource(R.string.academic_earned_credits, grade.credit)
                    } else {
                        stringResource(R.string.grades_not_earned)
                    },
                    fontSize = 11.sp,
                    color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
