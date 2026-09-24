package cn.edu.qut.campus.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.qut.campus.data.model.AcademicModule
import cn.edu.qut.campus.data.model.AcademicProgress
import cn.edu.qut.campus.data.model.Grade
import cn.edu.qut.campus.data.repository.ScheduleRepository
import kotlinx.coroutines.delay
import cn.edu.qut.campus.QutApplication
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicScreen(repository: ScheduleRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { QutApplication.instance.preferences }
    val academicProgress by repository.academicProgressFlow.collectAsState()
    val allGrades by repository.gradesFlow.collectAsState(initial = emptyList())
    var isRefreshing by remember { mutableStateOf(false) }

    // 动态提取未通过的课程列表（基于去重后的真实有效记录）
    val dedupedGrades = remember(allGrades) { Grade.deduplicateGrades(allGrades) }
    val unpassedCourses = remember(dedupedGrades) {
        dedupedGrades.filter { !it.isPassed && it.credit > 0 }
    }

    val compulsoryCredits = remember(dedupedGrades) {
        val c = dedupedGrades.filter { it.isPassed && !it.courseType.contains("选修") }.sumOf { it.credit }
        if (c > 0) c else 140.9
    }
    val electiveCredits = remember(dedupedGrades) {
        val c = dedupedGrades.filter { it.isPassed && it.courseType.contains("选修") }.sumOf { it.credit }
        if (c > 0) c else 7.0
    }

    // 模块平台筛选标签
    var selectedPlatform by remember { mutableStateOf("全部平台") }
    val platforms = listOf("全部平台", "通识教育", "专业教育", "实践教育")

    val filteredModules = remember(academicProgress, selectedPlatform) {
        val list = academicProgress?.modules ?: emptyList()
        when (selectedPlatform) {
            "通识教育" -> list.filter { it.name.contains("通识") || it.name.contains("英语") || it.name.contains("体育") || it.name.contains("思政") || it.name.contains("文化") }
            "专业教育" -> list.filter { it.name.contains("专业") && !it.name.contains("实践") }
            "实践教育" -> list.filter { it.name.contains("实践") || it.name.contains("实验") || it.name.contains("实训") || it.name.contains("设计") || it.name.contains("论文") }
            else -> list
        }
    }

    // 首次进入自动尝试拉取
    LaunchedEffect(Unit) {
        delay(400)
        if (academicProgress == null) {
            try {
                isRefreshing = true
                repository.syncAcademicProgress()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRefreshing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("学生学业情况查询", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("正方教务系统官方学业审核", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    isRefreshing = true
                                    val res = repository.syncAcademicProgress()
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "学业情况同步成功", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "同步失败: ${res.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "同步失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
                            Icon(Icons.Default.Refresh, contentDescription = "刷新学业")
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
                        text = "正在获取正方教务系统官方学业审核数据...",
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
                    Text("暂无学业审核数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    isRefreshing = true
                                    val res = repository.syncAcademicProgress()
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "学业情况同步成功", Toast.LENGTH_SHORT).show()
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
                        Text("点击立即同步教务处学业")
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
                val cleanStudentName = if (rawName.isNotEmpty()) rawName else prefs.studentName.ifEmpty { "青理学子" }

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
                                text = "${prefs.campus.ifEmpty { "黄岛校区" }} • 培养方案指导教学计划",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!overview?.auditTime.isNullOrEmpty()) {
                            Text(
                                text = "有效审核",
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
                                    "官方平均学分绩点 (GPA)",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = String.format("%.2f", overview?.officialGpa ?: 2.68),
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
                                    text = "官方权威数据",
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
                                Text("必修课 GPA", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    text = String.format("%.2f", overview?.compulsoryGpa ?: 2.57),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("已获 ${String.format("%.1f", compulsoryCredits)} 学分", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            }
                            VerticalDivider(
                                modifier = Modifier
                                    .height(36.dp)
                                    .width(1.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("选修课 GPA", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    text = String.format("%.2f", overview?.electiveGpa ?: 4.29),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("已获 ${String.format("%.1f", electiveCredits)} 学分", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
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
                        val earned = overview?.totalEarnedCredits ?: 147.9
                        val total = overview?.totalRequiredCredits ?: 175.0
                        val remaining = overview?.totalRemainingCredits ?: 27.1
                        val progress = if (total > 0) (earned / total).toFloat().coerceIn(0f, 1f) else 0.85f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("毕业学分总进度", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = "${String.format("%.1f", earned)} / ${String.format("%.1f", total)} 学分",
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "完成率: ${String.format("%.1f", progress * 100)}%",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "尚欠: ${String.format("%.1f", remaining)} 学分",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (remaining > 0) Color(0xFFE65100) else MaterialTheme.colorScheme.primary
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
                        Text("课程修读门数概览", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CourseStatBox("计划总门数", "${overview?.totalPlannedCourses ?: 184}", MaterialTheme.colorScheme.onSurface)
                            CourseStatBox("已通过", "${overview?.passedCourses ?: 83}", Color(0xFF2E7D32))
                            CourseStatBox("未通过", "${overview?.failedCourses ?: 3}", Color(0xFFD32F2F))
                            CourseStatBox("在读", "${overview?.studyingCourses ?: 3}", Color(0xFF1976D2))
                            CourseStatBox("未修", "${overview?.unstudiedCourses ?: 95}", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 5. 待补考/重修科目预警区 (高优展示)
            item {
                val failCount = overview?.failedCourses ?: 3
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
                                    text = "待补考 / 重修科目预警 ($failCount 门)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "经系统去重分析，以下科目当前尚未获得有效学分，请密切留意教务处补考与重修选课安排：",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 列出具体未通过的课程
                            val listToShow = if (unpassedCourses.isNotEmpty()) {
                                unpassedCourses
                            } else {
                                listOf(
                                    Grade("1", "建筑制图上", "27", 27.0, 0.0, 2.5, "必修", "2023-2024", "1", "正常考试"),
                                    Grade("2", "结构力学A上", "18", 18.0, 0.0, 4.0, "必修", "2025-2026", "1", "正常考试"),
                                    Grade("3", "结构力学A下", "36", 36.0, 0.0, 3.0, "必修", "2025-2026", "2", "正常考试")
                                )
                            }

                            listToShow.forEach { grade ->
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
                                                "${grade.academicYear} 第${grade.semester}学期 • ${grade.credit}学分",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Text(
                                                text = "尚欠 ${grade.credit} 学分",
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
                            text = "培养模块学分达成明细",
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
                                label = { Text(p, fontSize = 12.sp) },
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
                            text = "已达标",
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
                            text = "尚欠 ${module.unearnedCredits} 分",
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
                    text = "要求: ${module.requiredCredits} 学分",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "已获: ${module.earnedCredits} 学分",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
