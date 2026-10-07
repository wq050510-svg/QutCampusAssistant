package cn.edu.qut.campus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.error.toAppError
import cn.edu.qut.campus.data.repository.ScheduleRepository
import cn.edu.qut.campus.ui.components.text
import kotlinx.coroutines.launch

/** 学号 / 工号的最短合理长度，用于登录前的本地粗校验 */
private const val MIN_ACCOUNT_LENGTH = 6

@Composable
fun LoginScreen(
    repository: ScheduleRepository,
    onLoginSuccess: () -> Unit
) {
    // 登录方式持久化：切换 Tab 立即写回 prefs，冷启动不再回到默认方式
    var isSsoLogin by remember { mutableStateOf(repository.prefs.loginType != "zf") }
    // 记住账号：仅预填学号，密码始终由用户重新输入
    var studentId by remember { mutableStateOf(repository.prefs.studentId) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 字段级错误：仅在用户提交过一次后展示，避免边输边报错
    var studentIdError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // 供非 composable 的 performLogin 闭包解析字符串资源（该闭包会被 onClick 与 ImeAction 调用）
    val context = LocalContext.current

    // 登录逻辑抽成闭包，供「登录按钮」与密码框 ImeAction.Done 共用
    val performLogin: () -> Unit = {
        val account = studentId.trim()
        val pwd = password

        val accountError = when {
            account.isEmpty() -> context.getString(R.string.login_error_empty_id)
            account.length < MIN_ACCOUNT_LENGTH -> context.getString(R.string.login_error_invalid_id)
            else -> null
        }
        val pwdError = if (pwd.isEmpty()) context.getString(R.string.login_error_empty_password) else null

        studentIdError = accountError
        passwordError = pwdError

        if (accountError != null || pwdError != null) {
            errorMessage = accountError ?: pwdError
        } else if (!isLoading) {
            focusManager.clearFocus()
            isLoading = true
            errorMessage = null
            scope.launch {
                // 用 runCatching 兜住取消/异常：CancellationException 会被重新抛出，
                // 不会伪装成「登录失败」而误报给用户
                val outcome = runCatching {
                    repository.loginAndSyncAll(account, pwd, isSso = isSsoLogin)
                }
                isLoading = false
                val result = outcome.getOrNull()
                when {
                    // 失败原因统一分类成 AppError 后再映射成文案，页面不再出现硬编码中文
                    result == null -> errorMessage = outcome.exceptionOrNull().toAppError().text(context)
                    result.isSuccess -> onLoginSuccess()
                    else -> errorMessage = result.exceptionOrNull().toAppError().text(context)
                }
            }
        }
    }

    // 密码显示切换的变换器只建一次，避免每次重组新建对象导致输入框整体重新布局
    val passwordTransformation = remember(passwordVisible) {
        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 校徽/标志图标
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = stringResource(R.string.login_logo_desc),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.common_school_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 登录方式切换 (统一身份认证 vs 教务系统直连)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                TabRow(
                    selectedTabIndex = if (isSsoLogin) 0 else 1,
                    containerColor = Color.Transparent,
                    divider = {}
                ) {
                    Tab(
                        selected = isSsoLogin,
                        onClick = {
                            isSsoLogin = true
                            repository.prefs.loginType = "sso"
                            errorMessage = null
                            studentIdError = null
                        },
                        text = {
                            Text(
                                stringResource(R.string.login_tab_sso),
                                fontWeight = if (isSsoLogin) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = !isSsoLogin,
                        onClick = {
                            isSsoLogin = false
                            repository.prefs.loginType = "zf"
                            errorMessage = null
                            studentIdError = null
                        },
                        text = {
                            Text(
                                stringResource(R.string.login_tab_zf),
                                fontWeight = if (!isSsoLogin) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isSsoLogin) stringResource(R.string.login_endpoint_sso) else stringResource(R.string.login_endpoint_zf),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 账号/学号输入框
            OutlinedTextField(
                value = studentId,
                onValueChange = {
                    studentId = it
                    studentIdError = null
                    errorMessage = null
                },
                label = { Text(if (isSsoLogin) stringResource(R.string.login_label_account_sso) else stringResource(R.string.login_label_account_zf)) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                isError = studentIdError != null,
                supportingText = studentIdError?.let { message ->
                    { Text(message) }
                },
                keyboardOptions = KeyboardOptions(
                    // 教务学号一定是数字，用数字键盘更快；
                    // 统一身份认证账号可能是字母工号，用全键盘避免输不进去
                    keyboardType = if (isSsoLogin) KeyboardType.Text else KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 密码输入框
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    passwordError = null
                    errorMessage = null
                },
                label = { Text(if (isSsoLogin) stringResource(R.string.login_label_password_sso) else stringResource(R.string.login_label_password_zf)) },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) stringResource(R.string.login_action_hide_password) else stringResource(R.string.login_action_show_password)
                        )
                    }
                },
                singleLine = true,
                isError = passwordError != null,
                supportingText = passwordError?.let { message ->
                    { Text(message) }
                },
                visualTransformation = passwordTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                // 键盘「完成」直接登录，与下方按钮走同一段逻辑
                keyboardActions = KeyboardActions(onDone = { performLogin() }),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 登录按钮
            Button(
                onClick = performLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.login_syncing))
                } else {
                    Text(stringResource(R.string.login_submit), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isSsoLogin) stringResource(R.string.login_privacy_hint_sso) else stringResource(R.string.login_privacy_hint_zf),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            TextButton(onClick = { showPrivacyDialog = true }) {
                Text(
                    text = stringResource(R.string.login_privacy_title),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showPrivacyDialog) {
        val bodyStyle = MaterialTheme.typography.bodySmall
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text(stringResource(R.string.login_privacy_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.login_privacy_body_1),
                        style = bodyStyle
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.login_privacy_body_2),
                        style = bodyStyle
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.login_privacy_body_3),
                        style = bodyStyle
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text(stringResource(R.string.common_got_it)) }
            }
        )
    }
}
