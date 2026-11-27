package com.aitts.v2.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aitts.v2.reader.LoadedBookImage

/**
 * Full-screen illustration lightbox with pinch-to-zoom, pan, double-tap zoom,
 * gallery navigation, and lossless Copy / Save / Share actions.
 */
@Composable
fun ReaderImageLightbox(
    loadedImage: LoadedBookImage,
    galleryIndex: Int,
    galleryTotal: Int,
    onNavigateGallery: ((Int) -> Unit)?,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    var scale by remember(loadedImage.item.resourcePath) { mutableFloatStateOf(1f) }
    var offset by remember(loadedImage.item.resourcePath) { mutableStateOf(Offset.Zero) }
    var showControls by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .pointerInput(loadedImage.item.resourcePath) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = nextScale
                        offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(loadedImage.item.resourcePath) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.2f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = { showControls = !showControls }
                    )
                }
        ) {
            Image(
                bitmap = loadedImage.bitmap.asImageBitmap(),
                contentDescription = "插图全屏预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )

            if (showControls) {
                LightboxTopHeader(
                    fileName = loadedImage.item.fileName,
                    dims = "${loadedImage.widthPx}×${loadedImage.heightPx}",
                    counter = if (galleryTotal > 1 && galleryIndex >= 0) "${galleryIndex + 1}/$galleryTotal" else "",
                    onDismiss = onDismiss,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                LightboxBottomActions(
                    canStep = onNavigateGallery != null && galleryTotal > 1 && galleryIndex >= 0,
                    canPrev = galleryIndex > 0,
                    canNext = galleryIndex in 0 until (galleryTotal - 1),
                    onPrev = { onNavigateGallery?.invoke(galleryIndex - 1) },
                    onNext = { onNavigateGallery?.invoke(galleryIndex + 1) },
                    onCopy = onCopy,
                    onSave = onSave,
                    onShare = onShare,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun LightboxTopHeader(
    fileName: String,
    dims: String,
    counter: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = listOf(counter, fileName, dims).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.16f)) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭插图", tint = Color.White)
            }
        }
    }
}

@Composable
private fun LightboxBottomActions(
    canStep: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.72f),
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (canStep) {
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上一张", tint = Color.White)
                }
            }
            TextButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("复制", color = Color.White)
            }
            TextButton(onClick = onSave) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("保存原图", color = Color.White)
            }
            TextButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("分享", color = Color.White)
            }
            if (canStep) {
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下一张", tint = Color.White)
                }
            }
        }
    }
}
