package com.aitts.v2.ui.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.aitts.v2.di.AppContainer
import com.aitts.v2.reader.BookImageItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates lossless ebook image discovery, Lightbox loading, gallery paging,
 * clipboard copy, gallery saving, share Intents, and batch export for ReaderViewModel.
 */
internal class ReaderMediaHelper(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ReaderUiState>
) {
    private var imageLoadJob: Job? = null

    fun refreshBookImages() {
        val st = state.value
        val record = st.book ?: return
        val items = container.bookImageExporter.listBookImages(record, st.readiumPublication)
        state.update { it.copy(bookImages = items) }
    }

    suspend fun loadThumbnail(resourcePath: String): Bitmap? {
        val st = state.value
        val record = st.book ?: return null
        return container.bookImageExporter.loadThumbnail(record, st.readiumPublication, resourcePath)
    }

    fun openImageLightbox(resourcePath: String) {
        val st = state.value
        val record = st.book ?: return
        imageLoadJob?.cancel()
        imageLoadJob = scope.launch {
            val loaded = container.bookImageExporter.loadFullImage(record, st.readiumPublication, resourcePath)
            if (loaded == null) {
                state.update { it.copy(statusMessage = "无法解析该图片资源") }
                return@launch
            }
            val cleanTarget = resourcePath.substringBefore('#').substringBefore('?').trimStart('/')
            val idx = st.bookImages.indexOfFirst {
                val p = it.resourcePath.trimStart('/')
                p == cleanTarget || p.endsWith("/$cleanTarget") || cleanTarget.endsWith("/$p")
            }
            state.update { it.copy(activeLightboxImage = loaded, activeLightboxIndex = idx) }
        }
    }

    fun navigateGallery(targetIndex: Int) {
        val st = state.value
        val item = st.bookImages.getOrNull(targetIndex) ?: return
        val record = st.book ?: return
        imageLoadJob?.cancel()
        imageLoadJob = scope.launch {
            val loaded = container.bookImageExporter.loadFullImage(record, st.readiumPublication, item.resourcePath)
            if (loaded != null) {
                state.update { it.copy(activeLightboxImage = loaded, activeLightboxIndex = targetIndex) }
            }
        }
    }

    fun dismissLightbox() {
        imageLoadJob?.cancel()
        imageLoadJob = null
        state.update { it.copy(activeLightboxImage = null, activeLightboxIndex = -1) }
    }

    fun copyActiveImage() {
        val loaded = state.value.activeLightboxImage ?: return
        container.bookImageExporter.copyImageToClipboard(
            loaded.item.fileName,
            loaded.rawBytes,
            loaded.item.mimeType
        ).fold(
            onSuccess = { state.update { it.copy(statusMessage = "已复制原图到剪贴板，可直接粘贴") } },
            onFailure = { err -> state.update { it.copy(statusMessage = "复制图片失败: ${err.message}") } }
        )
    }

    fun saveActiveImage() {
        val loaded = state.value.activeLightboxImage ?: return
        val title = state.value.book?.title.orEmpty()
        scope.launch {
            container.bookImageExporter.saveImageToGallery(
                title,
                loaded.item.fileName,
                loaded.rawBytes,
                loaded.item.mimeType
            ).fold(
                onSuccess = { loc -> state.update { it.copy(statusMessage = "已保存原图至 $loc") } },
                onFailure = { err -> state.update { it.copy(statusMessage = "保存失败: ${err.message}") } }
            )
        }
    }

    fun shareActiveImage(context: Context) {
        val loaded = state.value.activeLightboxImage ?: return
        container.bookImageExporter.buildShareImageIntent(
            loaded.item.fileName,
            loaded.rawBytes,
            loaded.item.mimeType
        ).fold(
            onSuccess = { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            onFailure = { err -> state.update { it.copy(statusMessage = "分享失败: ${err.message}") } }
        )
    }

    fun exportAllImages() {
        val st = state.value
        val record = st.book ?: return
        if (st.isExportingImages) return
        state.update { it.copy(isExportingImages = true, imageExportProgress = "准备提取插图...") }
        scope.launch {
            container.bookImageExporter.exportAllImages(record, st.readiumPublication) { done, total ->
                state.update { it.copy(imageExportProgress = "正在保存 $done / $total") }
            }.fold(
                onSuccess = { count ->
                    state.update {
                        it.copy(
                            isExportingImages = false,
                            imageExportProgress = "",
                            statusMessage = "已导出 $count 张原图到 Pictures/AI-TTS/"
                        )
                    }
                },
                onFailure = { err ->
                    state.update {
                        it.copy(
                            isExportingImages = false,
                            imageExportProgress = "",
                            statusMessage = "批量导出失败: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun openGalleryItem(item: BookImageItem) = openImageLightbox(item.resourcePath)
}
