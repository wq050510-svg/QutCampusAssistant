package cn.edu.qut.campus.data.error

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 与 UI 无关的「错误类型」。
 *
 * 数据层没有 Context，原先只能把中文文案硬编码进异常 message、再由 UI 层用
 * `if (message.contains("timeout"))` 猜类别：既无法本地化，界面也无法区分
 * 「网络不通 / 会话过期 / 账号密码错误 / 教务没返回数据」。
 *
 * 这里只做分类，**不引用任何 R.**；文案映射统一放在 ui/components/ErrorMessage.kt。
 */
sealed interface AppError {

    /** 原始诊断信息（可能为 null），仅用于排查与 [Unknown] 的兜底展示 */
    val detail: String?

    /** 无法连接学校服务器（DNS 解析失败、连接被拒绝、无网络） */
    data class Network(override val detail: String? = null) : AppError

    /** 连接 / 读取超时 */
    data class Timeout(override val detail: String? = null) : AppError

    /** 教务会话已失效，需要重新登录 */
    data class SessionExpired(override val detail: String? = null) : AppError

    /** 账号或密码错误 */
    data class InvalidCredentials(override val detail: String? = null) : AppError

    /** 教务系统未返回预期结构（接口调整，或会话失效被重定向到登录页） */
    data class ServerDataMissing(override val detail: String? = null) : AppError

    /** 与学校服务器的安全连接失败（SSL、公钥获取失败、密码加密失败） */
    data class SecureConnection(override val detail: String? = null) : AppError

    /** 未归类 */
    data class Unknown(override val detail: String? = null) : AppError
}

/** 大小写不敏感地判断是否命中任一关键字（对中文文案与英文技术消息同样有效） */
private fun String.containsAny(vararg keys: String): Boolean =
    keys.any { contains(it, ignoreCase = true) }

/**
 * 把底层异常分类成 [AppError]。
 *
 * 覆盖 ZhengFangClient / ScheduleRepository 目前真正会抛出的全部失败文案：
 * - 登录：「未获取到登录加密公钥，请稍后重试」「登录加密公钥异常，请稍后重试」
 *   「登录失败，请检查学号与密码是否正确」「密码加密失败，为保护密码已中止登录…」
 *   「统一身份认证页面不是安全连接，为保护密码已中止登录…」
 *   「统一身份认证失败，请检查账号密码」「登录响应异常(HTTP xxx)」
 * - 课表：「课表获取失败，教务系统会话可能已过期」「课表数据格式错误」
 *   「教务系统未返回课表数据，可能接口已调整或登录已过期」
 * - 成绩：「成绩获取失败，教务系统会话可能已过期」「成绩数据格式错误」
 *   「教务系统未返回成绩数据，可能接口已调整或登录已过期」
 * - 考试：「考试安排获取失败，教务系统会话可能已过期」「考试数据格式错误」
 *   「教务系统未返回考试数据，可能接口已调整或登录已过期」
 * - 学业：「学业情况获取失败，教务系统会话可能已过期」「学业数据解析为空，会话可能已过期」
 *
 * 另外保留了上一版 UI 层异常翻译函数已覆盖的全部英文模式
 * （Unable to resolve host / UnknownHost / Failed to connect / Connection refused /
 * timeout / timed out / 401 / session）。
 *
 * 关键字顺序说明：安全连接与「教务未返回数据」两类必须排在**会话过期 / 账号密码错误之前**，
 * 否则「密码加密失败…」（同时含「密码」「失败」）会被判成账号密码错误、
 * 「教务系统未返回课表数据，可能接口已调整或登录已过期」会被判成会话过期，
 * 把真实原因盖掉、给出误导性提示。
 */
fun Throwable?.toAppError(): AppError {
    val throwable = this ?: return AppError.Unknown(null)
    val detail = throwable.message
    val text = throwable.message.orEmpty()

    // 1) 异常类型优先：这几类语义明确，不受文案变化影响
    if (throwable is UnknownHostException || throwable is ConnectException) {
        return AppError.Network(detail)
    }
    if (throwable is SocketTimeoutException) {
        return AppError.Timeout(detail)
    }
    if (throwable is SSLException || throwable is SSLHandshakeException ||
        throwable is SSLPeerUnverifiedException
    ) {
        return AppError.SecureConnection(detail)
    }
    if (throwable is InterruptedIOException && text.containsAny("timeout", "timed out", "超时")) {
        return AppError.Timeout(detail)
    }

    // 2) 文案关键字
    // 2.1 安全连接：未拿到加密公钥 / 密码加密失败 / 认证页不是 https
    if (text.containsAny(
            "加密公钥", "未提供加密公钥", "加密失败",
            "不是安全连接", "安全连接失败", "ssl", "handshake", "certificate"
        )
    ) {
        return AppError.SecureConnection(detail)
    }
    // 2.2 教务未返回预期结构（这类文案里同时写着「登录已过期」，必须先于会话过期判断）
    if (text.containsAny("未返回", "接口已调整", "格式错误")) {
        return AppError.ServerDataMissing(detail)
    }
    // 2.3 会话过期
    if (text.containsAny(
            "会话", "登录已过期", "登录状态已过期", "未登录", "请先登录",
            "session", "401", "unauthorized"
        )
    ) {
        return AppError.SessionExpired(detail)
    }
    // 2.4 账号或密码错误（教务直连登录失败 / 统一身份认证失败）
    if (text.contains("密码") && text.containsAny("错误", "失败", "不正确")) {
        return AppError.InvalidCredentials(detail)
    }
    // 2.5 超时
    if (text.containsAny("超时", "timeout", "timed out")) {
        return AppError.Timeout(detail)
    }
    // 2.6 网络不可达
    if (text.containsAny(
            "无法连接", "网络",
            "unable to resolve host", "unknownhost", "failed to connect",
            "connection refused", "connection reset", "econnrefused"
        )
    ) {
        return AppError.Network(detail)
    }

    return AppError.Unknown(detail)
}
