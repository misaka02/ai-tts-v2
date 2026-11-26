package com.aitts.v2.offline

import android.content.Context
import com.aitts.v2.engine.offline.OfflineModelInstaller
import com.aitts.v2.engine.provider.TtsProvider

interface OfflineTtsBackendFactory {
    fun createProvider(context: Context, installer: OfflineModelInstaller): TtsProvider
    fun isNativeRuntimeBundled(): Boolean
}
