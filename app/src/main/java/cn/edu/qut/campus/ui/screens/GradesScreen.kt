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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.repository.ScheduleRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(repository: ScheduleRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val grades by repository.gradesFlow.collectAsState(initial = emptyList())
    var isRefreshing by remember { mutableStateOf(false) }

    // 首次进入：若本地无数据则安全拉取一次
    LaunchedEffect(Unit) {
        delay(400)
        if (grades.isEmpty()) {
            try {
                isRefreshing = true
                repository.syncGrades()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRefreshing = false
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

    val semesters = remember(baseGrades) {
        listOf("全部学期") + baseGrades.map { "${it.academicYear}-${it.semester}" }.distinct().sortedDescending()
    }

    // 按学期过滤
    val filteredGrades = remember(baseGrades, selectedSemester) {
        if (selectedSemester == "全部学期") baseGrades
        else baseGrades.filter { "${it.academicYear}-${it.semester}" == selectedSemester }
    }

    // 统计指标计算（基于去重后的真实清单）
    val totalCredits = remember(filteredGrades) {
        // 已获学分仅统计通过的课程
        filteredGrades.filter { it.isPassed }.sumOf { it.credit }
    }

    val gpa = remember(filteredGrades) {
        val totalXf = filteredGrades.filter { it.credit > 0 }.sumOf { it.credit }
        val totalJd = filteredGrades.filter { it.credit > 0 }.sumOf { it.credit * it.gradePoint }
        if (totalXf > 0) totalJd / totalXf else 0.0
    }

    val failedCount = remember(filteredGrades) {
        filteredGrades.count { !it.isPassed && it.credit > 0 }
    }

    Scaffold(
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
                                try {
                                    isRefreshing = true
                                    val res = repository.syncGrades()
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "成绩刷新成功", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "刷新失败: ${res.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "刷新失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isRefreshing = false
                                }
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

            // GPA 与学分总览卡片
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
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("计算绩点 (GPA)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            text = String.format("%.2f", gpa),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .height(36.dp)
                            .width(1.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("已获有效学分", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            text = String.format("%.1f", totalCredits),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .height(36.dp)
                            .width(1.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("待重修/补考", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            text = "$failedCount",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (failedCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
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

            // 成绩内容区域
            if (isRefreshing && filteredGrades.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "正在直连正方教务系统同步历年成绩...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
            } else if (filteredGrades.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无成绩记录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        isRefreshing = true
                                        val res = repository.syncGrades()
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "成绩同步成功", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "同步失败: ${res.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "同步失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("点击立即同步教务处成绩")
                        }
                    }
                }
            } else {
                // 成绩明细列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredGrades) { grade ->
                        GradeItemCard(grade = grade)
                    }
                }
            }
        }
    }
}

@Composable
fun GradeItemCard(grade: Grade) {
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
                    if (grade.isPassed && grade.examNature != "正常考试") {
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
                    color = if (grade.isPassed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = "绩点: ${grade.gradePoint}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
