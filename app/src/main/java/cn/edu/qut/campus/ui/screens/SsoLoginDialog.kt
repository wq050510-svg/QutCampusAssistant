package cn.edu.qut.campus.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val SSO_INITIAL_URL =
    "https://sso.qut.edu.cn/sso/login?x_started=true&redirect_uri=https%3A%2F%2Fsso.qut.edu.cn%2Fsso%2Foauth2%2Fauthorize%3Fscope%3Dprofile%26response_type%3Dcode%26redirect_uri%3Dhttp%253A%252F%252Fjxgl.qut.edu.cn%252Fsso%252Ftotlogin%253Fstatus%253Dsuccess%26client_id%3Dav6mAMyEtw3MZwugEE1#!/"

private const val JXGL_OAUTH_REDIRECT =
    "https://sso.qut.edu.cn/sso/oauth2/authorize?scope=profile&response_type=code&redirect_uri=http%3A%2F%2Fjxgl.qut.edu.cn%2Fsso%2Ftotlogin%3Fstatus%3Dsuccess&client_id=av6mAMyEtw3MZwugEE1"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SsoLoginDialog(
    onDismiss: () -> Unit,
    onCookiesCaptured: (String) -> Unit
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableStateOf(0) }
    var isCaptured by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            if (!isCaptured) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isCaptured,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler {
            if (webViewInstance?.canGoBack() == true) {
                webViewInstance?.goBack()
            } else if (!isCaptured) {
                onDismiss()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部操作栏
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "青理统一身份认证",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "支持密码 / 短信 / 微信扫码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            enabled = !isCaptured
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { webViewInstance?.reload() },
                            enabled = !isCaptured
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )

                // 加载进度指示条
                AnimatedVisibility(visible = pageProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { pageProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 底部提示信息
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "安全通道由青理统一认证驱动。登录成功将自动捕获凭据进入课表，无需手动复制。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            lineHeight = 15.sp
                        )
                    }
                }

                // 网页容器与加载遮罩
                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webViewInstance = this

                                val settings = this.settings
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                settings.cacheMode = WebSettings.LOAD_DEFAULT

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                fun checkAndCapture(url: String?) {
                                    if (isCaptured || url == null) return

                                    // 1. 如果已到达教务系统端点或带有 totlogin，直接提取 Session Cookie
                                    if (url.contains("jxgl.qut.edu.cn") || url.contains("totlogin")) {
                                        val cHttps = cookieManager.getCookie("https://jxgl.qut.edu.cn").orEmpty()
                                        val cHttp = cookieManager.getCookie("http://jxgl.qut.edu.cn").orEmpty()
                                        val cUrl = cookieManager.getCookie(url).orEmpty()
                                        val combined = listOf(cHttps, cHttp, cUrl).filter { it.isNotEmpty() }.joinToString("; ")
                                        if (combined.contains("JSESSIONID")) {
                                            isCaptured = true
                                            onCookiesCaptured(combined)
                                            return
                                        }
                                    }

                                    // 2. 如果在统一认证端登录成功（停留在 cas/login 或 sso 首页但未跳向教务系统），主动发起教务 OAuth 授权
                                    if (url.contains("sso.qut.edu.cn/cas/login") || url.contains("sso.qut.edu.cn/sso/index") || url.endsWith("sso.qut.edu.cn/#/")) {
                                        post {
                                            loadUrl(JXGL_OAUTH_REDIRECT)
                                        }
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        pageProgress = newProgress
                                        if (newProgress >= 70) {
                                            checkAndCapture(view?.url)
                                        }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val reqUrl = request?.url?.toString() ?: return false
                                        if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                                            checkAndCapture(reqUrl)
                                            return false
                                        }
                                        return try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(reqUrl))
                                            view?.context?.startActivity(intent)
                                            true
                                        } catch (e: Exception) {
                                            true
                                        }
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        checkAndCapture(url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        checkAndCapture(url)
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        checkAndCapture(url)
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: SslErrorHandler?,
                                        error: SslError?
                                    ) {
                                        // 信任青理校内 SSL 证书
                                        handler?.proceed()
                                    }
                                }

                                loadUrl(SSO_INITIAL_URL)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 认证成功后的全屏加载遮罩
                    if (isCaptured) {
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "统一认证授权成功！",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "正在极速同步教务课表、考试安排与历年成绩...",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
