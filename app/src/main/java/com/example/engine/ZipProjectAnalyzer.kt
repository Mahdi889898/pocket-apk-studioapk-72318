package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.ProjectAnalysis
import com.example.data.ProjectReadiness
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class ZipProjectAnalyzer(private val context: Context) {

    companion object {
        private const val MAX_EXTRACTED_BYTES = 250L * 1024L * 1024L // 250 MB safety cap
        private val MODEL_3D_EXTS = setOf("glb", "gltf", "obj", "mtl", "fbx", "dae", "stl", "ply", "3ds")
        private val SHADER_EXTS = setOf("glsl", "vert", "frag", "wgsl", "shader")
        private val SCRIPT_EXTS = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "wasm")
        private val MEDIA_EXTS = setOf("png", "jpg", "jpeg", "webp", "svg", "gif", "mp3", "wav", "ogg", "mp4", "hdr", "exr")
    }

    fun analyzeAndStage(zipUri: Uri): ProjectAnalysis {
        val rawStaging = File(context.filesDir, "staging/raw_extract")
        val finalStaging = File(context.filesDir, "staging/current")
        rawStaging.deleteRecursively()
        finalStaging.deleteRecursively()
        rawStaging.mkdirs()
        finalStaging.mkdirs()

        val displayName = resolveFileName(zipUri)
        val totalZipBytes = resolveFileSize(zipUri)

        var totalExtracted = 0L
        var entryCount = 0
        val relativePaths = mutableListOf<String>()

        val inputStream = context.contentResolver.openInputStream(zipUri)
            ?: throw IllegalStateException("امکان باز کردن فایل ZIP انتخاب‌شده وجود ندارد.")

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(16 * 1024)
            while (entry != null) {
                val cleanName = entry.name.replace('\\', '/').trimStart('/')
                val hasTraversal = cleanName.split('/').any { it == ".." }
                if (cleanName.isNotEmpty() && !hasTraversal && !cleanName.startsWith("__MACOSX/")) {
                    if (!entry.isDirectory) {
                        val outFile = File(rawStaging, cleanName)
                        val canonicalRoot = rawStaging.canonicalPath + File.separator
                        if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                            throw SecurityException("مسیر ناامن در فایل ZIP: $cleanName")
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                totalExtracted += read
                                if (totalExtracted > MAX_EXTRACTED_BYTES) {
                                    throw IllegalStateException("حجم فایل‌های استخراج‌شده از حد مجاز (۲۵۰ مگابایت) بیشتر است.")
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                        relativePaths.add(cleanName)
                        entryCount++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (entryCount == 0) {
            throw IllegalStateException("فایل ZIP خالی است یا ساختار معتبری ندارد.")
        }

        // Detect single wrapper root folder (e.g. "my-game/index.html" -> strip "my-game/")
        val firstSegments = relativePaths.mapNotNull { path ->
            val idx = path.indexOf('/')
            if (idx > 0) path.substring(0, idx + 1) else null
        }.toSet()
        val allHaveSlash = relativePaths.all { it.contains('/') }
        val rootPrefixToStrip = if (allHaveSlash && firstSegments.size == 1) firstSegments.first() else ""

        // Move normalized tree into finalStaging
        val normalizedPaths = mutableListOf<String>()
        for (rel in relativePaths) {
            val trimmedRel = if (rootPrefixToStrip.isNotEmpty()) rel.removePrefix(rootPrefixToStrip) else rel
            if (trimmedRel.isBlank()) continue
            val srcFile = File(rawStaging, rel)
            val dstFile = File(finalStaging, trimmedRel)
            dstFile.parentFile?.mkdirs()
            srcFile.copyTo(dstFile, overwrite = true)
            normalizedPaths.add(trimmedRel)
        }
        rawStaging.deleteRecursively()

        // Find HTML entries, preferring dist/index.html > build/index.html > index.html
        val htmlEntries = normalizedPaths
            .filter { it.lowercase(Locale.US).endsWith(".html") || it.lowercase(Locale.US).endsWith(".htm") }
            .sortedWith(compareBy<String> { path ->
                when (path.lowercase(Locale.US)) {
                    "dist/index.html" -> 0
                    "build/index.html" -> 1
                    "out/index.html" -> 2
                    "index.html" -> 3
                    else -> 10 + path.count { it == '/' }
                }
            })

        val hasDistFolder = normalizedPaths.any {
            val l = it.lowercase(Locale.US)
            l.startsWith("dist/") || l.startsWith("build/") || l.startsWith("out/")
        }

        val entryPoint = htmlEntries.firstOrNull() ?: ""
        val hasPackageJson = normalizedPaths.any { it.equals("package.json", ignoreCase = true) }

        var model3dCount = 0
        var shaderCount = 0
        var scriptCount = 0
        var mediaCount = 0
        for (p in normalizedPaths) {
            val ext = p.substringAfterLast('.', "").lowercase(Locale.US)
            when {
                ext in MODEL_3D_EXTS -> model3dCount++
                ext in SHADER_EXTS -> shaderCount++
                ext in SCRIPT_EXTS -> scriptCount++
                ext in MEDIA_EXTS -> mediaCount++
            }
        }

        val engines = linkedSetOf<String>()
        var pkgJsonName: String? = null
        val pkgFile = File(finalStaging, "package.json")
        if (pkgFile.exists()) {
            runCatching {
                val json = JSONObject(pkgFile.readText())
                pkgJsonName = json.optString("name").takeIf { it.isNotBlank() }
                val allDeps = buildString {
                    json.optJSONObject("dependencies")?.keys()?.forEach { append(it.lowercase(Locale.US)).append(' ') }
                    json.optJSONObject("devDependencies")?.keys()?.forEach { append(it.lowercase(Locale.US)).append(' ') }
                }
                detectEnginesFromText(allDeps, engines)
            }
        }

        val lowerEntry = entryPoint.lowercase(Locale.US)
        val entryInsideDist = lowerEntry.startsWith("dist/") || lowerEntry.startsWith("build/") || lowerEntry.startsWith("out/")
        val hasSourceTsFiles = normalizedPaths.any { p ->
            val l = p.lowercase(Locale.US)
            !l.startsWith("node_modules/") &&
                !l.startsWith("dist/") &&
                !l.startsWith("build/") &&
                !l.startsWith("out/") &&
                !l.endsWith(".d.ts") &&
                !l.endsWith("vite.config.ts") &&
                (l.endsWith(".tsx") || l.endsWith(".ts") || l.endsWith(".jsx"))
        }

        var hasTsxOrTsEntry = false
        var hasImportMap = false
        if (entryPoint.isNotEmpty()) {
            val entryFile = File(finalStaging, entryPoint)
            if (entryFile.exists()) {
                val htmlText = entryFile.readText()
                val lowerHtml = htmlText.lowercase(Locale.US)
                detectEnginesFromText(lowerHtml, engines)
                hasImportMap = lowerHtml.contains("importmap")
                val hasSrcTs = Regex("""src\s*=\s*["'][^"']+\.(tsx|ts|jsx)(?:\?[^"']*)?["']""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(htmlText)
                val hasInlineModuleTs = Regex("""<script\b[^>]*type\s*=\s*["']module["'][^>]*>.*?\.(tsx|ts|jsx)\b.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .containsMatchIn(htmlText)
                hasTsxOrTsEntry = hasSrcTs || hasInlineModuleTs || (hasSourceTsFiles && !entryInsideDist)
            }
        }

        // Scan up to 15 small JS/TS files for engine clues if not found yet
        if (engines.isEmpty()) {
            normalizedPaths
                .filter { p -> p.endsWith(".js", true) || p.endsWith(".ts", true) || p.endsWith(".tsx", true) }
                .take(15)
                .forEach { rel ->
                    val f = File(finalStaging, rel)
                    if (f.length() < 512 * 1024) {
                        detectEnginesFromText(f.readText().lowercase(Locale.US), engines)
                    }
                }
        }

        if (model3dCount > 0 && "3D Models" !in engines) {
            engines.add("3D Assets ($model3dCount)")
        }
        if (engines.isEmpty() && entryPoint.isNotEmpty()) {
            engines.add("HTML5 / WebGL")
        }

        val rawName = pkgJsonName
            ?: displayName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
        val suggestedAppName = rawName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
            .take(28)
            .ifBlank { "AI Studio 3D" }

        val slug = sanitizePackageSegment(rawName)
        val suggestedPackageName = "com.aistudio.game.$slug"

        // Automatically transpile TypeScript / TSX / JSX & normalize HTML for both Preview and APK
        var transpiledCount = 0
        if (entryPoint.isNotEmpty() && (!entryInsideDist || hasTsxOrTsEntry)) {
            val report = TsxModuleTranspiler.processStagedProject(finalStaging, entryPoint)
            transpiledCount = report.transpiledFileCount
        }

        val readiness = when {
            entryPoint.isEmpty() -> ProjectReadiness.NO_ENTRY_FOUND
            hasTsxOrTsEntry -> ProjectReadiness.SOURCE_TSX_ESM
            else -> ProjectReadiness.READY_BUNDLE
        }

        return ProjectAnalysis(
            sourceUri = zipUri,
            sourceFileName = displayName,
            totalZipBytes = totalZipBytes,
            totalExtractedBytes = totalExtracted,
            fileCount = normalizedPaths.size,
            rootPrefixToStrip = rootPrefixToStrip,
            entryPoint = entryPoint,
            allHtmlEntries = htmlEntries,
            detectedEngines = engines.toList(),
            model3dCount = model3dCount,
            shaderCount = shaderCount,
            scriptCount = scriptCount,
            mediaCount = mediaCount,
            hasPackageJson = hasPackageJson,
            hasDistFolder = hasDistFolder,
            hasTsxOrTsEntry = hasTsxOrTsEntry,
            hasImportMap = hasImportMap,
            suggestedAppName = suggestedAppName,
            suggestedPackageName = suggestedPackageName,
            readiness = readiness,
            stagingDirPath = finalStaging.absolutePath,
            transpiledFileCount = transpiledCount
        )
    }

    private fun detectEnginesFromText(text: String, out: MutableSet<String>) {
        if (text.contains("three") || text.contains("gltf")) out.add("Three.js")
        if (text.contains("@react-three/fiber") || text.contains("@react-three/drei")) out.add("React Three Fiber")
        if (text.contains("babylon")) out.add("Babylon.js")
        if (text.contains("phaser")) out.add("Phaser")
        if (text.contains("pixi")) out.add("PixiJS")
        if (text.contains("playcanvas")) out.add("PlayCanvas")
        if (text.contains("cannon") || text.contains("rapier") || text.contains("ammo")) out.add("3D Physics")
        if (text.contains("webgl")) out.add("WebGL")
        if (text.contains("react")) out.add("React")
    }

    private fun sanitizePackageSegment(raw: String): String {
        val cleaned = raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")
            .take(18)
        return when {
            cleaned.isEmpty() -> "app3d"
            cleaned.first().isDigit() -> "g$cleaned"
            else -> cleaned
        }
    }

    private fun resolveFileName(uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path ?: "project.zip").name
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx) ?: "project.zip"
            }
        }
        return "project.zip"
    }

    private fun resolveFileSize(uri: Uri): Long {
        if (uri.scheme == "file") {
            return File(uri.path ?: "").length()
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) return cursor.getLong(idx)
            }
        }
        return 0L
    }
}
