package com.aitts.v2.ui.reader

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun readStreamUpTo(stream: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
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

internal fun diagnosePlayServicesError(err: Throwable, sha1: String): String {
    val api = err as? ApiException
    val code = api?.statusCode
    val rawMsg = api?.status?.statusMessage ?: api?.message ?: err.message.orEmpty()
    return when (code) {
        CommonStatusCodes.DEVELOPER_ERROR -> "Google Play 拒绝授权 (错误码 10: DEVELOPER_ERROR)：\n" +
            "原因：Play 服务授权校验的是您自己的 Google Cloud Android 客户端签名，当前 APK 的包名 com.aitts.engine " +
            "与签名 SHA-1 ($sha1) 尚未注册并关联测试用户。\n" +
            "推荐：直接使用下方「浏览器 OAuth 永久连接」或顶部「系统网盘直选 (SAF)」。"
        CommonStatusCodes.NETWORK_ERROR -> "网络错误 (错误码 7)：无法连接 Google 服务，请确认代理/VPN 畅通。"
        CommonStatusCodes.CANCELED -> if (rawMsg.contains("cancel", true)) {
            "您取消了 Google Play 授权 (错误码 16)。"
        } else {
            "Play 授权被系统拦截 (错误码 16: $rawMsg)，通常因为应用未在 Google Play 控制台登记或未加测试用户，请改用「浏览器 OAuth」。"
        }
        12501 -> "您取消了 Google 账号授权 (错误码 12501)。"
        else -> "Play 服务授权失败${code?.let { " (错误码 $it)" } ?: ""}：$rawMsg。请改用「浏览器 OAuth」或「系统网盘直选 (SAF)」。"
    }
}

internal fun diagnoseNetworkOrOAuthError(err: Throwable): String {
    val msg = err.message.orEmpty()
    val isNet = err is UnknownHostException || err is SocketTimeoutException || err is ConnectException ||
        msg.contains("Unable to resolve host", true) || msg.contains("timeout", true) || msg.contains("Failed to connect", true)
    return when {
        isNet -> "无法连接 Google 服务器（$msg）。请确认系统 VPN 已开启，或前往主页顶部「网络与代理设置」配置代理后重试。"
        msg.contains("access_denied", true) || msg.contains("403") ->
            "Google 拒绝访问 (403 / access_denied)：请进入 Google Cloud Console →「OAuth 同意屏幕」→「测试用户 (Test users)」，" +
                "添加您当前登录的 Gmail 邮箱，并确认已启用 Google Drive API。"
        msg.contains("redirect_uri_mismatch", true) ->
            "回调地址不匹配 (redirect_uri_mismatch)：请改为创建「桌面应用 (Desktop app)」OAuth 客户端，" +
                "或在 Web 客户端的「已获授权的重定向 URI」中添加 http://127.0.0.1:53682/callback 并在下方填写该固定 URI。"
        msg.contains("client_secret", true) || msg.contains("invalid_client", true) ->
            "Google 客户端认证失败 ($msg)：Google Desktop / Web OAuth 客户端在换取令牌时必须同时填写「Client Secret」。"
        msg.contains("invalid_grant", true) ->
            "授权码或 Refresh Token 已失效 ($msg)，请重新执行浏览器 OAuth 授权或重新生成令牌。"
        else -> msg.ifBlank { "Google 登录失败，请检查配置并重试" }
    }
}
