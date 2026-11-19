package com.example.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileInputStream
import java.util.Locale

private const val PREVIEW_HOST = "appassets.local"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(
    target: PreviewTarget,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val errorLogs = remember { mutableStateListOf<String>() }
    var showErrorDialog by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val rootDir = remember(target.rootDirPath) { File(target.rootDirPath) }
    val cleanEntry = remember(target.entryPoint) { target.entryPoint.trimStart('/') }
    val baseSubdir = remember(cleanEntry) {
        val idx = cleanEntry.lastIndexOf('/')
        if (idx >= 0) cleanEntry.substring(0, idx + 1) else ""
    }

    fun recordError(entry: String) {
        if (errorLogs.size < 40 && errorLogs.lastOrNull() != entry) {
            errorLogs.add(entry)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.parseColor("#0B0E14"))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowFileAccess = true
                        allowContentAccess = true
                        loadsImagesAutomatically = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }

                        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                            if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                val src = cm.sourceId()?.substringAfterLast('/')?.ifBlank { "inline" } ?: "inline"
                                recordError("${cm.message()} ($src:${cm.lineNumber()})")
                            }
                            return super.onConsoleMessage(cm)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (!reqUrl.endsWith("/favicon.ico", ignoreCase = true)) {
                                recordError("Network error: ${error?.description} ($reqUrl)")
                            }
                            super.onReceivedError(view, request, error)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            val status = errorResponse?.statusCode ?: 0
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (status >= 400 && !reqUrl.endsWith("/favicon.ico", ignoreCase = true)) {
                                recordError("HTTP $status: $reqUrl")
                            }
                            super.onReceivedHttpError(view, request, errorResponse)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val uri: Uri = request?.url ?: return null
                            if (!PREVIEW_HOST.equals(uri.host, ignoreCase = true)) {
                                return super.shouldInterceptRequest(view, request)
                            }
                            var path = uri.path?.trimStart('/') ?: cleanEntry
                            if (path.isEmpty()) path = cleanEntry

                            var targetFile = File(rootDir, path)
                            if (!targetFile.exists() && baseSubdir.isNotEmpty() && !path.startsWith(baseSubdir)) {
                                targetFile = File(rootDir, baseSubdir + path)
                            }
                            val canonicalRoot = rootDir.canonicalPath + File.separator
                            if (!targetFile.exists() || !targetFile.isFile || !targetFile.canonicalPath.startsWith(canonicalRoot)) {
                                return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null)
                            }
                            val mime = guessWebMimeType(path)
                            val encoding = if (mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json" || mime == "image/svg+xml" || mime == "model/gltf+json") "UTF-8" else null
                            return WebResourceResponse(
                                mime,
                                encoding,
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
                                FileInputStream(targetFile)
                            )
                        }
                    }
                    loadUrl("https://$PREVIEW_HOST/$cleanEntry")
                    webViewRef = this
                }
            }
        )

        // Floating Translucent HUD Header
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("preview_close_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "بازگشت از پیش‌نمایش"
                    )
                }

                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                if (errorLogs.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .testTag("preview_error_badge")
                            .clickable { showErrorDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "خطاهای کنسول",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "${errorLogs.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        errorLogs.clear()
                        webViewRef?.reload()
                    },
                    modifier = Modifier.testTag("preview_reload_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "بارگذاری مجدد"
                    )
                }
            }
        }

        val primaryError = errorLogs.firstOrNull()
        if (primaryError != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .testTag("preview_error_banner")
                    .clickable { showErrorDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = primaryError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (errorLogs.size > 1) {
                        Text(
                            text = "+${errorLogs.size - 1} خطای دیگر (برای جزئیات ضربه بزنید)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (showErrorDialog) {
            AlertDialog(
                onDismissRequest = { showErrorDialog = false },
                title = { Text("گزارش خطاهای پیش‌نمایش (${errorLogs.size})") },
                text = {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            errorLogs.forEachIndexed { idx, err ->
                                Text(
                                    text = "${idx + 1}. $err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showErrorDialog = false }) {
                        Text("بستن")
                    }
                }
            )
        }
    }
}

private fun guessWebMimeType(path: String): String {
    val lower = path.lowercase(Locale.US)
    return when {
        lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
        lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx") -> "application/javascript"
        lower.endsWith(".css") -> "text/css"
        lower.endsWith(".json") || lower.endsWith(".map") -> "application/json"
        lower.endsWith(".wasm") -> "application/wasm"
        lower.endsWith(".glb") -> "model/gltf-binary"
        lower.endsWith(".gltf") -> "model/gltf+json"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".svg") -> "image/svg+xml"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".ogg") -> "audio/ogg"
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".woff2") -> "font/woff2"
        lower.endsWith(".woff") -> "font/woff"
        lower.endsWith(".ttf") -> "font/ttf"
        lower.endsWith(".glsl") || lower.endsWith(".vert") || lower.endsWith(".frag") || lower.endsWith(".obj") || lower.endsWith(".mtl") -> "text/plain"
        else -> "application/octet-stream"
    }
}
