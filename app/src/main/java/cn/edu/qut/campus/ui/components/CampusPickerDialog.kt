package cn.edu.qut.campus.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.qut.campus.data.model.CampusPeriod

/**
 * 「切换就读校区」对话框 —— 「我的」页与「课表」页共用的唯一实现。
 *
 * 抽这个组件的原因：此前 ProfileScreen 与 ScheduleScreen 各维护一份几乎相同的弹窗，
 * 校区候选写死成 `listOf("黄岛校区", "市北校区")`，与 `CampusPeriod.ALL_CAMPUSES` 重复维护，
 * 新增校区时必然漏改一处。现在候选统一来自 [CampusPeriod.ALL_CAMPUSES]。
 *
 * ## 行为约定（调用方按此接入）
 * - 组件自身**纯展示 + 回调**：不读写 SharedPreferences，不弹出 Toast，持久化由调用方负责，
 *   即调用方在 [onSelect] 里执行 `prefs.campus = it`（写 prefs 会自动更新 `campusFlow`）。
 * - 用户点击某个校区行：**先** `onSelect(校区名)`，紧接着自动 `onDismiss()`。
 *   点击已选中的那一项也会触发 `onSelect`，方便调用方统一给出「已切换为 xx」提示。
 * - 用户点「关闭」按钮、点对话框外部或按系统返回键：只触发 `onDismiss()`，**不会**触发 `onSelect`。
 * - 选中态判定：`campus == current` 严格相等；因此 [current] 请传未裁剪的原始校区名
 *   （例如 `prefs.campusFlow.collectAsStateWithLifecycle().value`，空串时再兜底 `"黄岛校区"`）。
 *
 * @param current 当前已选中的校区名，用于渲染选中态（勾选图标 + 高亮底色）。
 * @param onSelect 选中回调，参数为 `CampusPeriod.ALL_CAMPUSES` 中的校区名。
 * @param onDismiss 关闭回调，调用方须在此把控制该弹窗可见性的 state 置为 false。
 */
@Composable
fun CampusPickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换就读校区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "选择您的就读校区，将自动联动课表、日历同步与考场地点信息：",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                CampusPeriod.ALL_CAMPUSES.forEach { campusName ->
                    val isSelected = campusName == current
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(campusName)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = campusName,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = campusRoadName(campusName),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/** 校区对应的门牌路名，仅用于弹窗内的次要说明文字 */
private fun campusRoadName(campusName: String): String {
    return if (campusName.contains("市北")) "抚顺路" else "嘉陵江东路"
}
