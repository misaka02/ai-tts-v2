package com.aitts.v2.ui.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.browser.customtabs.CustomTabsIntent
import com.aitts.v2.BuildConfig
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.cloud.GoogleOAuthClientJsonParser
import com.aitts.v2.engine.cloud.WebDavConfig
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Coordinates Browser OAuth 2.0 PKCE (with Desktop JSON import & manual callback fallback),
 * Google Play Services One-Tap real error diagnostics, manual Token connection, and WebDAV verification.
 */
internal class CloudAuthCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CloudFileManagerUiState>
) {

    fun setDevAuthSheet(show: Boolean) = state.update { it.copy(showDevAuthSheet = show) }

    fun updateDevInputs(cid: String? = null, sec: String? = null, tok: String? = null, pastedUrl: String? = null) = state.update {
        it.copy(
            devClientId = cid ?: it.devClientId,
            devClientSecret = sec ?: it.devClientSecret,
            devAccessToken = tok ?: it.devAccessToken,
            pastedCallbackUrl = pastedUrl ?: it.pastedCallbackUrl
        )
    }

    fun updateWebDavInputs(url: String? = null, user: String? = null, pass: String? = null, root: String? = null) = state.update {
        it.copy(
            webDavUrlInput = url ?: it.webDavUrlInput,
            webDavUserInput = user ?: it.webDavUserInput,
            webDavPassInput = pass ?: it.webDavPassInput,
            webDavRootInput = root ?: it.webDavRootInput
        )
    }

    fun getPackageAndSha1Info(): Pair<String, String> =
        "com.aitts.engine" to container.googleDrive.getSigningSha1()

    fun copyPackageAndSha1(context: Context) {
        val (pkg, sha1) = getPackageAndSha1Info()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("App Signing Info", "包名: $pkg\nSHA-1: $sha1"))
        state.update { it.copy(statusMessage = "已复制包名与 SHA-1 指纹") }
    }

    fun copyPendingAuthUrl(context: Context) {
        val url = state.value.pendingAuthUrl
        if (url.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Google Auth URL", url))
        state.update { it.copy(statusMessage = "已复制授权链接，可粘贴到任意浏览器打开") }
    }

    fun submitManualCallbackUrl() {
        val raw = state.value.pastedCallbackUrl.trim()
        val accepted = container.googleDrive.completeManualBrowserCallback(raw)
        if (!accepted) {
            state.update { it.copy(errorMessage = "未检测到包含 code 与 state 参数的回调链接，请完整复制浏览器地址栏 URL。") }
        }
    }

    fun importClientSecretJson(context: Context, uri: Uri) {
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        readStreamUpTo(stream, 64 * 1024).toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                    GoogleOAuthClientJsonParser.parse(text).getOrThrow()
                }
            }
            res.fold(
                onSuccess = { parsed ->
                    container.googleDrive.saveDraftClientCredentials(parsed.clientId, parsed.clientSecret)
                    if (parsed.refreshToken.isNotBlank() || parsed.accessToken.isNotBlank()) {
                        container.googleDrive.connectWithTokens(
                            parsed.clientId, parsed.clientSecret, parsed.refreshToken, parsed.accessToken
                        ).fold(
                            onSuccess = {
                                state.update {
                                    it.copy(
                                        devClientId = parsed.clientId, devClientSecret = parsed.clientSecret,
                                        errorMessage = null, statusMessage = "已导入 (${parsed.clientType}) 并自动连接 Google Drive"
                                    )
                                }
                            },
                            onFailure = { err ->
                                state.update {
                                    it.copy(
                                        devClientId = parsed.clientId, devClientSecret = parsed.clientSecret,
                                        errorMessage = diagnoseNetworkOrOAuthError(err)
                                    )
                                }
                            }
                        )
                    } else {
                        state.update {
                            it.copy(
                                devClientId = parsed.clientId, devClientSecret = parsed.clientSecret, errorMessage = null,
                                statusMessage = "已导入 OAuth (${parsed.clientType}) 并持久化，请点「浏览器 OAuth 永久连接」"
                            )
                        }
                    }
                },
                onFailure = { err -> state.update { it.copy(errorMessage = "导入凭证失败: ${err.message}") } }
            )
        }
    }

    fun startBrowserPkceGoogleAuth(context: Context) {
        val st = state.value
        val cid = st.devClientId.trim().ifBlank { BuildConfig.GDRIVE_DEFAULT_CLIENT_ID.trim() }
        val csec = st.devClientSecret.trim()
        if (cid.isBlank()) {
            state.update {
                it.copy(errorMessage = "尚未配置 OAuth Client ID：请先导入 Google Cloud 桌面应用 client_secret.json，或展开填写 Client ID。")
            }
            return
        }
        container.googleDrive.saveDraftClientCredentials(cid, csec)
        state.update {
            it.copy(
                isBusy = true, isBrowserAuthWaiting = true, pendingAuthUrl = "", pastedCallbackUrl = "",
                busyLabel = "请在浏览器中完成 Google 授权并允许跳转回应用...", errorMessage = null
            )
        }
        scope.launch {
            val result = container.googleDrive.authorizeInBrowser(cid, csec) { authUrl ->
                state.update { it.copy(pendingAuthUrl = authUrl) }
                launchAuthUrl(context, authUrl)
            }
            result.fold(
                onSuccess = {
                    state.update {
                        it.copy(
                            isBusy = false, isBrowserAuthWaiting = false, pendingAuthUrl = "", pastedCallbackUrl = "",
                            busyLabel = "", statusMessage = "已通过浏览器连接 Google Drive（支持自动续期）"
                        )
                    }
                },
                onFailure = { err ->
                    state.update {
                        it.copy(
                            isBusy = false, isBrowserAuthWaiting = false, pendingAuthUrl = "", busyLabel = "",
                            errorMessage = diagnoseNetworkOrOAuthError(err)
                        )
                    }
                }
            )
        }
    }

    fun openGoogleCloudConsole(context: Context) {
        launchAuthUrl(context, "https://console.cloud.google.com/apis/credentials")
    }

    private fun launchAuthUrl(context: Context, url: String) {
        val uri = Uri.parse(url)
        val customOk = runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }.isSuccess
        if (customOk) return
        val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(fallback)
        } catch (_: ActivityNotFoundException) {
            state.update { it.copy(statusMessage = "未能自动调起浏览器，请复制授权链接手动打开") }
        }
    }

    fun cancelBrowserPkceAuth() {
        container.googleDrive.cancelPendingAuth()
        state.update {
            it.copy(
                isBusy = false, isBrowserAuthWaiting = false, pendingAuthUrl = "", pastedCallbackUrl = "",
                busyLabel = "", statusMessage = "已取消浏览器登录"
            )
        }
    }

    fun startOneTapGoogleAuth(activity: Activity, launchResolution: (IntentSenderRequest) -> Unit) {
        state.update { it.copy(isBusy = true, busyLabel = "正在请求 Google Play 服务授权...", errorMessage = null) }
        Identity.getAuthorizationClient(activity)
            .authorize(container.googleDrive.buildPlayServicesAuthRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pi = result.pendingIntent
                    if (pi != null) launchResolution(IntentSenderRequest.Builder(pi.intentSender).build())
                    else state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "未收到 Google 授权弹窗") }
                } else {
                    completeGoogleToken(result.accessToken.orEmpty())
                }
            }
            .addOnFailureListener { err ->
                state.update {
                    it.copy(
                        isBusy = false,
                        busyLabel = "",
                        errorMessage = diagnosePlayServicesError(err, container.googleDrive.getSigningSha1())
                    )
                }
            }
    }

    fun handleGoogleAuthResult(activity: Activity, data: Intent?) {
        if (data == null) {
            state.update {
                it.copy(
                    isBusy = false,
                    busyLabel = "",
                    errorMessage = "Google Play 授权未返回结果（可能是设备 Play 商店未配置当前应用 SHA-1 或弹窗被拦截），" +
                        "请改用「浏览器 OAuth 永久登录」或「系统网盘直选」。"
                )
            }
            return
        }
        runCatching {
            val authRes = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            completeGoogleToken(authRes.accessToken.orEmpty())
        }.onFailure { err ->
            val sha1 = container.googleDrive.getSigningSha1()
            state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = diagnosePlayServicesError(err, sha1)) }
        }
    }

    private fun completeGoogleToken(token: String) {
        scope.launch {
            container.googleDrive.handlePlayServicesToken(token).fold(
                onSuccess = { state.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "已连接 Google Drive") } },
                onFailure = { err ->
                    state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = diagnoseNetworkOrOAuthError(err)) }
                }
            )
        }
    }

    fun connectGoogleViaToken() {
        val st = state.value
        state.update { it.copy(isBusy = true, busyLabel = "校验 Token...", errorMessage = null) }
        scope.launch {
            container.googleDrive.connectWithTokens(st.devClientId, st.devClientSecret, "", st.devAccessToken).fold(
                onSuccess = {
                    state.update {
                        it.copy(isBusy = false, busyLabel = "", showDevAuthSheet = false, statusMessage = "已连接 Google Drive")
                    }
                },
                onFailure = { err ->
                    state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = diagnoseNetworkOrOAuthError(err)) }
                }
            )
        }
    }

    fun disconnectGoogle() {
        container.googleDrive.disconnect()
        state.update { it.copy(items = emptyList(), statusMessage = "已断开 Google Drive") }
    }

    fun connectWebDav(onSuccess: () -> Unit) {
        val st = state.value
        state.update { it.copy(isBusy = true, busyLabel = "测试 WebDAV 连接...", errorMessage = null) }
        scope.launch {
            container.webDav.saveAndConnect(
                WebDavConfig(st.webDavUrlInput, st.webDavUserInput, st.webDavPassInput, st.webDavRootInput)
            ).fold(
                onSuccess = { state.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "WebDAV 已连接") }; onSuccess() },
                onFailure = { err -> state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = err.message) } }
            )
        }
    }

    fun disconnectWebDav() {
        container.webDav.disconnect()
        state.update { it.copy(items = emptyList(), statusMessage = "已断开 WebDAV") }
    }
}

private fun readStreamUpTo(stream: java.io.InputStream, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (out.size() < maxBytes) {
        val want = minOf(buf.size, maxBytes - out.size() + 1)
        val count = stream.read(buf, 0, want)
        if (count <= 0) break
        out.write(buf, 0, count)
        if (out.size() > maxBytes) error("凭证文件过大（超过 64 KB）")
    }
    return out.toByteArray()
}

private fun diagnosePlayServicesError(err: Throwable, sha1: String): String {
    val api = err as? ApiException
    val code = api?.statusCode
    val rawMsg = api?.status?.statusMessage ?: api?.message ?: err.message.orEmpty()
    return when (code) {
        CommonStatusCodes.DEVELOPER_ERROR -> "Google Play 拒绝授权 (错误码 10: DEVELOPER_ERROR)：" +
            "当前 APK 签名 (SHA-1: $sha1) 未在 Google Cloud Console 绑定包名 com.aitts.engine。\n" +
            "请改用上方「浏览器 OAuth 永久登录」或「系统网盘直选」。"
        CommonStatusCodes.NETWORK_ERROR -> "网络错误 (错误码 7)：无法连接 Google 服务，请确认代理/VPN 畅通。"
        CommonStatusCodes.CANCELED -> if (rawMsg.contains("cancel", true)) {
            "您取消了 Google Play 授权 (错误码 16)。"
        } else {
            "Play 授权未能完成 (错误码 16: $rawMsg)，常见原因为未绑定测试账号/SHA-1 或网络超时。建议改用「浏览器 OAuth」。"
        }
        12501 -> "您取消了 Google 账号授权 (错误码 12501)。"
        else -> "Play 服务授权失败${code?.let { " (错误码 $it)" } ?: ""}：$rawMsg。建议改用「浏览器 OAuth」或「系统网盘直选」。"
    }
}

private fun diagnoseNetworkOrOAuthError(err: Throwable): String {
    val msg = err.message.orEmpty()
    val isNet = err is UnknownHostException || err is SocketTimeoutException || err is ConnectException ||
        msg.contains("Unable to resolve host", true) || msg.contains("timeout", true) || msg.contains("Failed to connect", true)
    return if (isNet) {
        "无法连接 Google 服务器（$msg）。请确认系统 VPN 已开启，或前往主页顶部「网络与代理设置」配置代理后重试。"
    } else {
        msg.ifBlank { "Google 登录失败，请重试" }
    }
}
