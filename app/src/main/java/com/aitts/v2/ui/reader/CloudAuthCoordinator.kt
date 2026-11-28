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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates Browser OAuth 2.0 PKCE (Desktop & loopback Web clients, JSON/rclone import & paste),
 * Google Play Services One-Tap real error diagnostics, Refresh/Access Token login, and WebDAV verification.
 */
internal class CloudAuthCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CloudFileManagerUiState>
) {

    fun setDevAuthSheet(showDev: Boolean? = null, showPaste: Boolean? = null) = state.update {
        it.copy(
            showDevAuthSheet = showDev ?: it.showDevAuthSheet,
            showPasteCredsSheet = showPaste ?: it.showPasteCredsSheet
        )
    }

    fun updateDevInputs(
        cid: String? = null,
        sec: String? = null,
        redir: String? = null,
        ref: String? = null,
        tok: String? = null,
        pastedUrl: String? = null
    ) = state.update {
        it.copy(
            devClientId = cid ?: it.devClientId,
            devClientSecret = sec ?: it.devClientSecret,
            devRedirectUri = redir ?: it.devRedirectUri,
            devRefreshToken = ref ?: it.devRefreshToken,
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
            state.update { it.copy(errorMessage = "未检测到包含 code 参数的回调链接或授权码，请完整复制后重试。") }
        }
    }

    fun importClientSecretJson(context: Context, uri: Uri) {
        scope.launch {
            val rawTextRes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        readStreamUpTo(stream, 64 * 1024).toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                }
            }
            rawTextRes.fold(
                onSuccess = { importCredentialsFromText(it) },
                onFailure = { err -> state.update { it.copy(errorMessage = "读取凭证文件失败: ${err.message}") } }
            )
        }
    }

    fun importCredentialsFromText(rawText: String) {
        scope.launch {
            val parsed = GoogleOAuthClientJsonParser.parse(rawText).getOrElse { err ->
                state.update { it.copy(errorMessage = "导入凭证失败: ${err.message}") }
                return@launch
            }
            container.googleDrive.saveDraftClientCredentials(parsed.clientId, parsed.clientSecret, parsed.loopbackRedirectUri)
            val redirUpdate = parsed.loopbackRedirectUri.ifBlank { state.value.devRedirectUri }
            if (parsed.refreshToken.isNotBlank() || parsed.accessToken.isNotBlank()) {
                container.googleDrive.connectWithTokens(
                    parsed.clientId, parsed.clientSecret, parsed.refreshToken, parsed.accessToken
                ).fold(
                    onSuccess = {
                        state.update {
                            it.copy(
                                devClientId = parsed.clientId, devClientSecret = parsed.clientSecret,
                                devRedirectUri = redirUpdate, showPasteCredsSheet = false, errorMessage = null,
                                statusMessage = "已导入 (${parsed.clientType}) 并自动连接 Google Drive"
                            )
                        }
                    },
                    onFailure = { err ->
                        state.update {
                            it.copy(
                                devClientId = parsed.clientId, devClientSecret = parsed.clientSecret,
                                devRedirectUri = redirUpdate, showPasteCredsSheet = false,
                                errorMessage = diagnoseNetworkOrOAuthError(err)
                            )
                        }
                    }
                )
            } else {
                state.update {
                    it.copy(
                        devClientId = parsed.clientId, devClientSecret = parsed.clientSecret,
                        devRedirectUri = redirUpdate, showPasteCredsSheet = false, errorMessage = null,
                        statusMessage = "已导入 OAuth (${parsed.clientType}) 并持久化，请点「浏览器 OAuth 永久连接」"
                    )
                }
            }
        }
    }

    fun startBrowserPkceGoogleAuth(context: Context, useExternalBrowser: Boolean = false) {
        val st = state.value
        val cid = st.devClientId.trim().ifBlank { BuildConfig.GDRIVE_DEFAULT_CLIENT_ID.trim() }
        val csec = st.devClientSecret.trim()
        val redir = st.devRedirectUri.trim()
        if (cid.isBlank()) {
            state.update {
                it.copy(errorMessage = "尚未配置 OAuth Client ID：请导入 client_secret.json / rclone.conf，或展开「手填凭据」输入。")
            }
            return
        }
        container.googleDrive.saveDraftClientCredentials(cid, csec, redir)
        state.update {
            it.copy(
                isBusy = true, isBrowserAuthWaiting = true, pendingAuthUrl = "", pastedCallbackUrl = "",
                busyLabel = "请在浏览器中完成 Google 授权并允许跳转回应用...", errorMessage = null
            )
        }
        scope.launch {
            val result = container.googleDrive.authorizeInBrowser(cid, csec, redir) { authUrl ->
                state.update { it.copy(pendingAuthUrl = authUrl) }
                launchAuthUrl(context, authUrl, preferExternal = useExternalBrowser)
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

    fun reopenPendingAuthInExternalBrowser(context: Context) {
        val url = state.value.pendingAuthUrl
        if (url.isNotBlank()) launchAuthUrl(context, url, preferExternal = true)
    }

    fun openExternalUrl(context: Context, url: String) = launchAuthUrl(context, url, preferExternal = false)

    private fun launchAuthUrl(context: Context, url: String, preferExternal: Boolean) {
        val uri = Uri.parse(url)
        if (!preferExternal) {
            val customOk = runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }.isSuccess
            if (customOk) return
        }
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
                    if (pi != null) {
                        runCatching {
                            launchResolution(IntentSenderRequest.Builder(pi.intentSender).build())
                        }.onFailure { err ->
                            state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "无法打开 Play 授权窗: ${err.message}") }
                        }
                    } else {
                        state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "未收到 Google 授权弹窗") }
                    }
                } else {
                    completeGoogleToken(result.accessToken.orEmpty())
                }
            }
            .addOnFailureListener { err ->
                state.update {
                    it.copy(
                        isBusy = false, busyLabel = "",
                        errorMessage = diagnosePlayServicesError(err, container.googleDrive.getSigningSha1())
                    )
                }
            }
    }

    fun handleGoogleAuthResult(activity: Activity, data: Intent?) {
        if (data == null) {
            state.update {
                it.copy(
                    isBusy = false, busyLabel = "",
                    errorMessage = "Google Play 授权未返回结果（未绑定此 APK SHA-1 或弹窗被拦截），请改用「浏览器 OAuth」或「系统网盘直选」。"
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
        val cid = st.devClientId.trim()
        val csec = st.devClientSecret.trim()
        val ref = st.devRefreshToken.trim()
        val acc = st.devAccessToken.trim()
        container.googleDrive.saveDraftClientCredentials(cid, csec, st.devRedirectUri)
        state.update { it.copy(isBusy = true, busyLabel = "校验并连接 Token...", errorMessage = null) }
        scope.launch {
            container.googleDrive.connectWithTokens(cid, csec, ref, acc).fold(
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
