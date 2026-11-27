package com.aitts.v2.ui.reader

import android.app.Activity
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.cloud.WebDavConfig
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates Google One-Tap Play Services authorization, fallback Token login,
 * and WebDAV connection verification on behalf of CloudFileManagerViewModel.
 */
internal class CloudAuthCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CloudFileManagerUiState>
) {

    fun setDevAuthSheet(show: Boolean) = state.update { it.copy(showDevAuthSheet = show) }

    fun updateDevInputs(cid: String? = null, sec: String? = null, tok: String? = null) = state.update {
        it.copy(
            devClientId = cid ?: it.devClientId,
            devClientSecret = sec ?: it.devClientSecret,
            devAccessToken = tok ?: it.devAccessToken
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

    fun startOneTapGoogleAuth(activity: Activity, launchResolution: (IntentSenderRequest) -> Unit) {
        state.update { it.copy(isBusy = true, busyLabel = "正在请求 Google 一键授权...", errorMessage = null) }
        Identity.getAuthorizationClient(activity)
            .authorize(container.googleDrive.buildPlayServicesAuthRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pi = result.pendingIntent
                    if (pi != null) {
                        launchResolution(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        state.update { it.copy(isBusy = false, errorMessage = "未收到 Google 授权窗口") }
                    }
                } else {
                    completeGoogleToken(result.accessToken.orEmpty())
                }
            }
            .addOnFailureListener { err ->
                val sha1 = container.googleDrive.getSigningSha1()
                state.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = "Play 服务一键授权不可用（${err.message ?: "SHA-1: $sha1"}）。可直接点下方「系统网盘直选」或「手动 Token」。"
                    )
                }
            }
    }

    fun handleGoogleAuthResult(activity: Activity, data: Intent?) {
        if (data == null) {
            state.update { it.copy(isBusy = false, statusMessage = "已取消 Google 登录") }
            return
        }
        runCatching {
            val authRes = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            completeGoogleToken(authRes.accessToken.orEmpty())
        }.onFailure { err ->
            state.update { it.copy(isBusy = false, errorMessage = "解析 Google 授权失败: ${err.message}") }
        }
    }

    private fun completeGoogleToken(token: String) {
        scope.launch {
            container.googleDrive.handlePlayServicesToken(token).fold(
                onSuccess = { state.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "已连接 Google Drive") } },
                onFailure = { err -> state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = err.message) } }
            )
        }
    }

    fun connectGoogleViaToken() {
        val st = state.value
        state.update { it.copy(isBusy = true, busyLabel = "校验 Token...") }
        scope.launch {
            container.googleDrive.connectWithTokens(st.devClientId, st.devClientSecret, "", st.devAccessToken).fold(
                onSuccess = { state.update { it.copy(isBusy = false, showDevAuthSheet = false, statusMessage = "已连接 Google Drive") } },
                onFailure = { err -> state.update { it.copy(isBusy = false, errorMessage = err.message) } }
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
                onSuccess = {
                    state.update { it.copy(isBusy = false, statusMessage = "WebDAV 已连接") }
                    onSuccess()
                },
                onFailure = { err -> state.update { it.copy(isBusy = false, errorMessage = err.message) } }
            )
        }
    }

    fun disconnectWebDav() {
        container.webDav.disconnect()
        state.update { it.copy(items = emptyList(), statusMessage = "已断开 WebDAV") }
    }
}
