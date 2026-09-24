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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.qut.campus.data.model.Exam
import cn.edu.qut.campus.data.repository.ScheduleRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsScreen(repository: ScheduleRepository) {
    val exams by repository.examsFlow.collectAsState(initial = emptyList())
    var showFinishedExams by remember { mutableStateOf(false) }
    val campus = remember { repository.prefs.campus.ifEmpty { "黄岛校区" } }

    // 过滤出未结束的考试
    val upcomingExams = remember(exams) {
        exams.filter { !it.isFinished }
    }
    // 已经结束的考试
    val finishedExams = remember(exams) {
        exams.filter { it.isFinished }
    }

    Scaffold(
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
            if (upcomingExams.isEmpty()) {
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
                items(upcomingExams) { exam ->
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
                    items(finishedExams) { exam ->
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
                val cleanRoom = exam.classroom
                    .replace("黄岛校区-", "")
                    .replace("市北校区-", "")
                    .replace("黄岛-", "")
                    .replace("市北-", "")
                    .trim()
                Text(
                    text = "考场：$examCampus $cleanRoom (座位号: ${exam.seatNumber})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFinished) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
