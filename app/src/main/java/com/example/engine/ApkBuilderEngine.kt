package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import com.android.apksig.ApkSigner
import com.example.data.ApkBuildOptions
import com.example.data.BuildLogLine
import com.example.data.BuildRecord
import com.example.data.ProjectAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface BuildProgressEvent {
    data class Progress(
        val fraction: Float,
        val stageLabel: String,
        val log: BuildLogLine
    ) : BuildProgressEvent

    data class Completed(
        val record: BuildRecord,
        val logs: List<BuildLogLine>
    ) : BuildProgressEvent

    data class Failed(
        val errorMessage: String,
        val logs: List<BuildLogLine>
    ) : BuildProgressEvent
}

class ApkBuilderEngine(
    private val context: Context,
    private val signingKeyManager: SigningKeyManager
) {

    companion object {
        private const val TEMPLATE_DIR = "shell_template"
        private const val TEMPLATE_PACKAGE = "com.aistudio.template.game"
        private const val TEMPLATE_LABEL = "StudioGameTemplateLabel"
        private const val TEMPLATE_VERSION_NAME = "1.0.0"
        private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }

    fun isValidPackageName(pkg: String): Boolean = PACKAGE_REGEX.matches(pkg.trim())

    fun buildAndSignApk(
        analysis: ProjectAnalysis,
        options: ApkBuildOptions
    ): Flow<BuildProgressEvent> = flow {
        val logs = mutableListOf<BuildLogLine>()
        fun log(msg: String, isError: Boolean = false): BuildLogLine {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val line = BuildLogLine(time, msg, isError)
            logs.add(line)
            return line
        }

        try {
            val cleanAppName = options.appName.trim().ifBlank { "AI Studio Game" }
            val cleanPkg = options.packageName.trim()
            require(isValidPackageName(cleanPkg)) {
                "شناسه پکیج نامعتبر است. مثال معتبر: com.aistudio.game.mygame"
            }
            require(analysis.entryPoint.isNotBlank()) {
                "فایل ورودی index.html در پروژه یافت نشد."
            }

            emit(BuildProgressEvent.Progress(0.10f, "آماده‌سازی فایل‌های وب و سه‌بعدی...", log("Staging web & 3D assets from ${analysis.sourceFileName}")))

            val buildId = UUID.randomUUID().toString().take(8)
            val buildsDir = File(context.filesDir, "builds").apply { mkdirs() }
            val previewDir = File(buildsDir, "${buildId}_www").apply {
                deleteRecursively()
                mkdirs()
            }

            val stagingRoot = File(analysis.stagingDirPath)
            require(stagingRoot.exists()) { "فایل‌های پروژه در حافظه موقت یافت نشد. لطفا ZIP را دوباره انتخاب کنید." }

            // Copy staging web files into normalized previewDir
            stagingRoot.walkTopDown().forEach { src ->
                if (src.isFile) {
                    val rel = src.relativeTo(stagingRoot).path.replace('\\', '/')
                    val dst = File(previewDir, rel)
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                }
            }

            // Normalize HTML entry points for https://appassets.local/ serving
            val entryFile = File(previewDir, analysis.entryPoint)
            if (entryFile.exists()) {
                val originalHtml = entryFile.readText(StandardCharsets.UTF_8)
                val patchedHtml = normalizeHtmlEntry(
                    html = originalHtml,
                    enableTsxBridge = options.enableTsxBridge && analysis.hasTsxOrTsEntry
                )
                entryFile.writeText(patchedHtml, StandardCharsets.UTF_8)
                emit(BuildProgressEvent.Progress(0.24f, "بهینه‌سازی مسیرهای WebGL و ESM...", log("Normalized entry HTML: ${analysis.entryPoint}")))
            }

            // Write studio_config.json for GameShellActivity
            val configJson = JSONObject().apply {
                put("title", cleanAppName)
                put("entryPoint", analysis.entryPoint)
                put("fullscreen", options.fullscreen)
                put("orientation", options.orientation.key)
                put("keepScreenOn", options.keepScreenOn)
                put("allowZoom", false)
                put("backgroundColor", options.backgroundColorHex)
            }.toString(2)

            emit(BuildProgressEvent.Progress(0.36f, "تولید آیکون برنامه...", log("Generating 192x192 PNG launcher icon...")))
            val iconBytes = buildIconPngBytes(cleanAppName, options.customIconUri)

            emit(BuildProgressEvent.Progress(0.50f, "کامپایل باینری AndroidManifest.xml...", log("Patching AXML manifest: $cleanPkg (v${options.versionName} / ${options.versionCode})")))
            val templateManifest = readTemplateAsset("template_manifest.bin")
            val patchedManifest = AxmlPatcher.patchManifest(
                axmlBytes = templateManifest,
                replacements = mapOf(
                    TEMPLATE_PACKAGE to cleanPkg,
                    TEMPLATE_LABEL to cleanAppName,
                    TEMPLATE_VERSION_NAME to options.versionName.trim().ifBlank { "1.0.0" }
                ),
                newVersionCode = options.versionCode.coerceAtLeast(1)
            )

            val templateArsc = readTemplateAsset("template_resources.bin")
            val patchedArsc = AxmlPatcher.patchResourcesArscPackageName(templateArsc, cleanPkg)
            val classesDex = readTemplateAsset("template_classes.bin")

            emit(BuildProgressEvent.Progress(0.68f, "بسته‌بندی و هم‌ترازی ۴ بایتی APK...", log("Writing 4-byte aligned resources.arsc + classes.dex + ${analysis.fileCount} assets...")))
            val unsignedApk = File(context.cacheDir, "unsigned_$buildId.apk")
            if (unsignedApk.exists()) unsignedApk.delete()

            writeAlignedApkZip(
                outputFile = unsignedApk,
                resourcesArsc = patchedArsc,
                manifestAxml = patchedManifest,
                classesDex = classesDex,
                iconPng = iconBytes,
                studioConfigJson = configJson.toByteArray(StandardCharsets.UTF_8),
                wwwRootDir = previewDir
            )

            emit(BuildProgressEvent.Progress(0.84f, "امضای دیجیتال APK (Scheme v1 + v2)...", log("Loading RSA-2048 X.509 signing certificate...")))
            val signingProfile = signingKeyManager.ensureOrLoadProfile()
            val signerConfig = signingKeyManager.loadSignerConfig()

            val safeSlug = cleanAppName.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "studio_game" }
            val signedApk = File(buildsDir, "${safeSlug}_${buildId}.apk")
            if (signedApk.exists()) signedApk.delete()

            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(24)
                .build()
            signer.sign()
            unsignedApk.delete()

            emit(BuildProgressEvent.Progress(1.0f, "ساخت APK با موفقیت کامل شد!", log("Signed APK ready: ${signedApk.name} (${signedApk.length() / 1024} KB)")))

            val record = BuildRecord(
                id = buildId,
                appName = cleanAppName,
                packageName = cleanPkg,
                versionName = options.versionName.trim().ifBlank { "1.0.0" },
                versionCode = options.versionCode.coerceAtLeast(1),
                apkPath = signedApk.absolutePath,
                previewDirPath = previewDir.absolutePath,
                entryPoint = analysis.entryPoint,
                orientation = options.orientation.key,
                fullscreen = options.fullscreen,
                keepScreenOn = options.keepScreenOn,
                apkSizeBytes = signedApk.length(),
                engines = analysis.detectedEngines,
                sha256Fingerprint = signingProfile.sha256Fingerprint,
                createdAtMillis = System.currentTimeMillis()
            )
            emit(BuildProgressEvent.Completed(record, logs))
        } catch (t: Throwable) {
            val errMsg = t.message ?: "خطای ناشناخته هنگام ساخت APK"
            emit(BuildProgressEvent.Failed(errMsg, logs + log("ERROR: $errMsg", isError = true)))
        }
    }.flowOn(Dispatchers.IO)

    private fun readTemplateAsset(name: String): ByteArray {
        return context.assets.open("$TEMPLATE_DIR/$name").use { it.readBytes() }
    }

    private fun writeAlignedApkZip(
        outputFile: File,
        resourcesArsc: ByteArray,
        manifestAxml: ByteArray,
        classesDex: ByteArray,
        iconPng: ByteArray,
        studioConfigJson: ByteArray,
        wwwRootDir: File
    ) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // 1. resources.arsc MUST be STORED (uncompressed) and 4-byte aligned for Android 11+ (API 30+)
            // Entry #1 Local File Header is 30 + "resources.arsc".length (14) = 44 bytes (44 % 4 == 0).
            writeStoredEntry(zos, "resources.arsc", resourcesArsc)
            writeDeflatedEntry(zos, "AndroidManifest.xml", manifestAxml)
            writeDeflatedEntry(zos, "classes.dex", classesDex)
            writeStoredEntry(zos, "res/mipmap-xxxhdpi-v4/ic_launcher.png", iconPng)
            writeDeflatedEntry(zos, "assets/studio_config.json", studioConfigJson)

            wwwRootDir.walkTopDown().sortedBy { it.path }.forEach { file ->
                if (file.isFile) {
                    val rel = file.relativeTo(wwwRootDir).path.replace('\\', '/')
                    val entryPath = "assets/www/$rel"
                    val ext = rel.substringAfterLast('.', "").lowercase(Locale.US)
                    val bytes = file.readBytes()
                    // Keep binary audio/video uncompressed so Media/WebAudio streams efficiently; compress text/assets
                    if (ext in setOf("mp3", "ogg", "wav", "mp4", "png", "jpg", "jpeg", "webp")) {
                        writeStoredEntry(zos, entryPath, bytes)
                    } else {
                        writeDeflatedEntry(zos, entryPath, bytes)
                    }
                }
            }
        }
    }

    private fun writeStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val crc = CRC32().apply { update(data) }.value
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            this.crc = crc
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun writeDeflatedEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun buildIconPngBytes(appName: String, customIconUri: Uri?): ByteArray {
        val size = 192
        if (customIconUri != null) {
            runCatching {
                context.contentResolver.openInputStream(customIconUri)?.use { input ->
                    val decoded = BitmapFactory.decodeStream(input)
                    if (decoded != null) {
                        val scaled = Bitmap.createScaledBitmap(decoded, size, size, true)
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                        if (decoded !== scaled) decoded.recycle()
                        scaled.recycle()
                        return out.toByteArray()
                    }
                }
            }
        }

        // Generate a crisp procedural 3D Cyber-Forge emblem PNG with the app's initials
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0xFF0D1321.toInt(), 0xFF172540.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 42f, 42f, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0xFF00E5A3.toInt(), 0xFF7B74FF.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(12f, 12f, size - 12f, size - 12f), 32f, 32f, borderPaint)

        val initial = appName.trim()
            .firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()
            ?.toString() ?: "3D"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E5A3.toInt()
            textSize = 78f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val yOffset = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initial, size / 2f, yOffset, textPaint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun normalizeHtmlEntry(html: String, enableTsxBridge: Boolean): String {
        // Rewrite root-absolute src="/..." and href="/..." to relative ./ so nested entry points work cleanly
        return html
            .replace(Regex("""(src|href)\s*=\s*"/([^/][^"]*)""""), """$1="./$2"""")
            .replace(Regex("""(src|href)\s*=\s*'/([^/][^']*)'"""), """$1='./$2'""")
    }
}
