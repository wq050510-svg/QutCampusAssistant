package cn.edu.qut.campus.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cn.edu.qut.campus.R
import cn.edu.qut.campus.data.error.AppError

/**
 * [AppError] → 字符串资源。
 *
 * 数据层只负责「分类」（data/error/AppError.kt），文案统一在这里映射：
 * 页面不再出现硬编码中文，也不再需要 `if (message.contains("timeout"))` 这类字符串匹配。
 */

@StringRes
fun AppError.messageRes(): Int = when (this) {
    is AppError.Network -> R.string.error_network
    is AppError.Timeout -> R.string.error_timeout
    is AppError.SessionExpired -> R.string.error_session_expired
    is AppError.InvalidCredentials -> R.string.error_invalid_credentials
    is AppError.ServerDataMissing -> R.string.error_server_data_missing
    is AppError.SecureConnection -> R.string.error_secure_connection
    // 「未归类」复用既有的 common_network_error（文案与 error_unknown 逐字相同：网络异常，请稍后重试），
    // 不再重复新建一个语义完全一致的键
    is AppError.Unknown -> R.string.common_network_error
}

/** 原始诊断信息：空白串一律当作「没有」，避免把空文案塞进占位符 */
fun AppError.detailOrNull(): String? = detail?.takeIf { it.isNotBlank() }

/**
 * 组合期取文案。
 * [AppError.Unknown] 且带有原始信息时，用带占位符的资源把原文透出来，便于排查；
 * 其余类型走常规映射（文案里已包含「该做什么」的指引）。
 */
@Composable
fun AppError.text(): String {
    val raw = detailOrNull()
    return if (this is AppError.Unknown && raw != null) {
        stringResource(R.string.error_unknown_with_detail, raw)
    } else {
        stringResource(messageRes())
    }
}

/**
 * 非组合期取文案：Toast / Snackbar / 协程闭包里不能调用 [stringResource]。
 * 映射规则与 [text] 保持一致。
 */
fun AppError.text(context: Context): String {
    val raw = detailOrNull()
    return if (this is AppError.Unknown && raw != null) {
        context.getString(R.string.error_unknown_with_detail, raw)
    } else {
        context.getString(messageRes())
    }
}

/**
 * 便捷取文案：ViewModel 暴露的是 `AppError?`，而 Snackbar 的 effect 里不能调用 @Composable，
 * 需要在组合期先取好字符串；空值直接返回 null，省掉页面里的 if 与 `!!`。
 */
@Composable
fun AppError?.textOrNull(): String? {
    val error = this
    return if (error == null) null else error.text()
}
