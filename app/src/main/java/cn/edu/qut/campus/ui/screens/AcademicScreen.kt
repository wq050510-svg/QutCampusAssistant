package cn.edu.qut.campus.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.model.AcademicModule
import cn.edu.qut.campus.data.model.AcademicProgress
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.QutApplication
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 学业数据展示约定：**只展示真实抓取到的数据**。
 * 教务接口未返回的字段一律显示占位符，绝不使用看似合理的默认值填充，
 * 否则学生会把编造的 GPA / 学分当成自己的真实学业情况。
 */
private const val NO_DATA = "—"

private fun gpaText(value: Double?): String =
    if (value == null || value <= 0.0) NO_DATA else String.format(Locale.US, "%.2f", value)

private fun creditText(value: Double?): String =
    if (value == null || value <= 0.0) NO_DATA else String.format(Locale.US, "%.1f", value)

/** 同步学业数据：成功返回 null，失败返回可直接展示给用户的原因 */
private suspend fun syncAcademicOrNull(context: Context, repository: ScheduleRepository): String? = try {
    repository.syncAcademicProgress().exceptionOrNull()
        ?.let { it.message?.takeIf { m -> m.isNotBlank() } ?: context.getString(R.string.academic_error_session_expired) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_network_error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicScreen(repository: ScheduleRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // 非 composable 闭包（Toast / Snackbar / 协程）里要用的文案在此提前解析
    val fallbackStudentName = stringResource(R.string.common_default_student_name)
    val fallbackCampus = stringResource(R.string.campus_huangdao)
    val snackbarHostState = remember { SnackbarHostState() }
    val prefs = remember { QutApplication.instance.preferences }
    val campus by prefs.campusFlow.collectAsStateWithLifecycle()
    val academicProgress by repository.academicProgressFlow.collectAsStateWithLifecycle()
    val allGrades by repository.gradesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var isRefreshing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }

    // 动态提取未通过的课程列表（基于去重后的真实有效记录）
    val dedupedGrades = remember(allGrades) { Grade.deduplicateGrades(allGrades) }
    val unpassedCourses = remember(dedupedGrades) {
        dedupedGrades.filter { !it.isPassed && it.credit > 0 }
    }

    // 只统计真实成绩记录：拿不到就显示占位符，不再回退到写死的数字
    val compulsoryCredits = remember(dedupedGrades) {
        dedupedGrades.filter { it.isPassed && !it.courseType.contains("选修") }.sumOf { it.credit }
    }
    val electiveCredits = remember(dedupedGrades) {
        dedupedGrades.filter { it.isPassed && it.courseType.contains("选修") }.sumOf { it.credit }
    }

    // 模块平台筛选标签：以字符串资源 id 作为筛选项标识，展示文案统一由资源提供
    var selectedPlatform by remember { mutableStateOf(R.string.academic_platform_all) }
    val platforms = listOf(
        R.string.academic_platform_all,
        R.string.academic_platform_general,
        R.string.academic_platform_major,
        R.string.academic_platform_practice
    )

    val filteredModules = remember(academicProgress, selectedPlatform) {
        val list = academicProgress?.modules ?: emptyList()
        when (selectedPlatform) {
            R.string.academic_platform_general -> list.filter { it.name.contains("通识") || it.name.contains("英语") || it.name.contains("体育") || it.name.contains("思政") || it.name.contains("文化") }
            R.string.academic_platform_major -> list.filter { it.name.contains("专业") && !it.name.contains("实践") }
            R.string.academic_platform_practice -> list.filter { it.name.contains("实践") || it.name.contains("实验") || it.name.contains("实训") || it.name.contains("设计") || it.name.contains("论文") }
            else -> list
        }
    }

    // 首次进入自动尝试拉取；失败必须让用户看得见，而不是静默吞掉
    LaunchedEffect(Unit) {
        if (academicProgress == null) {
            isRefreshing = true
            syncError = syncAcademicOrNull(context, repository)
            isRefreshing = false
        }
    }

    LaunchedEffect(syncError) {
        syncError?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.academic_sync_failed, it))
            syncError = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.academic_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.academic_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                isRefreshing = true
                                val error = syncAcademicOrNull(context, repository)
                                isRefreshing = false
                                if (error == null) {
                                    snackbarHostState.showSnackbar(context.getString(R.string.academic_sync_success))
                                } else {
                                    syncError = error
                                }
                            }
                        },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.academic_action_refresh))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isRefreshing && academicProgress == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.academic_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        } else if (academicProgress == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.academic_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isRefreshing = true
                                val error = syncAcademicOrNull(context, repository)
                                isRefreshing = false
                                if (error == null) {
                                    snackbarHostState.showSnackbar(context.getString(R.string.academic_sync_success))
                                } else {
                                    syncError = error
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.academic_sync_now))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                val overview = academicProgress?.overview
                val rawName = overview?.studentName
                    ?.replace("bold\">", "")
                    ?.replace("&nbsp;", "")
                    ?.replace(">", "")
                    ?.replace("\"", "")
                    ?.trim() ?: ""
                val cleanStudentName = if (rawName.isNotEmpty()) rawName else prefs.studentName.ifEmpty { fallbackStudentName }

            // 1. 学籍信息总览条
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val majorDisplay = if (prefs.studentMajor.isNotEmpty()) " • ${prefs.studentMajor}" else ""
                            Text(
                                text = "$cleanStudentName$majorDisplay",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.academic_campus_plan_hint, campus.ifEmpty { fallbackCampus }),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!overview?.auditTime.isNullOrEmpty()) {
                            Text(
                                text = stringResource(R.string.academic_valid_audit),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 2. 官方总 GPA 与分项绩点 Hero 卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column {
                                Text(
                                    stringResource(R.string.academic_official_gpa),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = gpaText(overview?.officialGpa),
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = if ((overview?.officialGpa ?: 0.0) > 0.0) stringResource(R.string.academic_from_system) else stringResource(R.string.academic_no_value),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.academic_compulsory_gpa), fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    text = gpaText(overview?.compulsoryGpa),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (dedupedGrades.isEmpty()) stringResource(R.string.academic_grades_pending) else stringResource(R.string.academic_earned_credits, creditText(compulsoryCredits)),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            VerticalDivider(
                                modifier = Modifier
                                    .height(36.dp)
                                    .width(1.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.academic_elective_gpa), fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    text = gpaText(overview?.electiveGpa),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (dedupedGrades.isEmpty()) stringResource(R.string.academic_grades_pending) else stringResource(R.string.academic_earned_credits, creditText(electiveCredits)),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // 3. 毕业学分完成进度条卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        val earned = overview?.totalEarnedCredits ?: 0.0
                        val total = overview?.totalRequiredCredits ?: 0.0
                        val remaining = overview?.totalRemainingCredits ?: 0.0
                        val hasCreditPlan = total > 0.0
                        val progress = if (hasCreditPlan) (earned / total).toFloat().coerceIn(0f, 1f) else 0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.academic_graduation_progress), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = if (hasCreditPlan) stringResource(R.string.academic_credits_ratio, creditText(earned), creditText(total)) else NO_DATA,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (hasCreditPlan) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.academic_completion_rate, String.format(Locale.US, "%.1f", progress * 100)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.academic_remaining_credits, creditText(remaining)),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (remaining > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.academic_no_credit_plan),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. 计划课程门数分布
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.academic_course_stats_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        val planned = overview?.totalPlannedCourses ?: 0
                        val hasPlanCount = planned > 0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CourseStatBox(stringResource(R.string.academic_stat_planned), if (hasPlanCount) "$planned" else NO_DATA, MaterialTheme.colorScheme.onSurface)
                            CourseStatBox(stringResource(R.string.academic_stat_passed), if (hasPlanCount) "${overview?.passedCourses ?: 0}" else NO_DATA, MaterialTheme.colorScheme.primary)
                            CourseStatBox(stringResource(R.string.academic_stat_failed), if (hasPlanCount) "${overview?.failedCourses ?: 0}" else NO_DATA, MaterialTheme.colorScheme.error)
                            CourseStatBox(stringResource(R.string.academic_stat_studying), if (hasPlanCount) "${overview?.studyingCourses ?: 0}" else NO_DATA, MaterialTheme.colorScheme.tertiary)
                            CourseStatBox(stringResource(R.string.academic_stat_unstudied), if (hasPlanCount) "${overview?.unstudiedCourses ?: 0}" else NO_DATA, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 5. 待补考/重修科目预警区 (高优展示)
            // 只依据真实成绩记录判断，不再用官方门数字段兜底、也不再编造挂科名单
            item {
                val failCount = unpassedCourses.size
                if (failCount > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.academic_fail_warning_title, failCount),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.academic_fail_warning_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 列出具体未通过的课程（全部来自本地真实成绩记录）
                            unpassedCourses.forEach { grade ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(grade.courseName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text(
                                                stringResource(R.string.academic_grade_term, grade.academicYear, grade.semester, grade.credit),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Text(
                                                text = stringResource(R.string.academic_course_remaining, grade.credit),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. 培养方案各模块分类明细
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.academic_modules_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 平台筛选
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        platforms.forEach { p ->
                            FilterChip(
                                selected = selectedPlatform == p,
                                onClick = { selectedPlatform = p },
                                label = { Text(stringResource(p), fontSize = 12.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }

            // 模块列表项
            items(filteredModules) { mod ->
                ModuleCreditCard(module = mod)
            }
        }
    }
}
}

@Composable
fun CourseStatBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ModuleCreditCard(module: AcademicModule) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = module.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                if (module.isCompleted) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = stringResource(R.string.academic_module_completed),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = stringResource(R.string.academic_module_remaining, module.unearnedCredits),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (module.requiredCredits > 0) {
                LinearProgressIndicator(
                    progress = { (module.progressPercentage / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (module.isCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.academic_module_required, module.requiredCredits),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.academic_module_earned, module.earnedCredits),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
