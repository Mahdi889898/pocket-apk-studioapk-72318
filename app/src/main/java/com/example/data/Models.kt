package com.example.data

import android.net.Uri

enum class ScreenOrientationOption(val key: String, val labelFa: String) {
    AUTO("auto", "خودکار"),
    LANDSCAPE("landscape", "افقی (بازی)"),
    PORTRAIT("portrait", "عمودی")
}

enum class ProjectReadiness(val labelFa: String) {
    READY_BUNDLE("آماده تبدیل به APK (آفلاین)"),
    SOURCE_TSX_ESM("تبدیل‌شده از TSX/TS • آماده APK"),
    NO_ENTRY_FOUND("فایل ورودی index.html یافت نشد")
}

data class ProjectAnalysis(
    val sourceUri: Uri?,
    val sourceFileName: String,
    val totalZipBytes: Long,
    val totalExtractedBytes: Long,
    val fileCount: Int,
    val rootPrefixToStrip: String,
    val entryPoint: String,
    val allHtmlEntries: List<String>,
    val detectedEngines: List<String>,
    val model3dCount: Int,
    val shaderCount: Int,
    val scriptCount: Int,
    val mediaCount: Int,
    val hasPackageJson: Boolean,
    val hasDistFolder: Boolean,
    val hasTsxOrTsEntry: Boolean,
    val hasImportMap: Boolean,
    val suggestedAppName: String,
    val suggestedPackageName: String,
    val readiness: ProjectReadiness,
    val stagingDirPath: String,
    val transpiledFileCount: Int = 0
)

data class ApkBuildOptions(
    val appName: String = "My 3D Game",
    val packageName: String = "com.aistudio.game.demo3d",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val orientation: ScreenOrientationOption = ScreenOrientationOption.AUTO,
    val fullscreen: Boolean = true,
    val keepScreenOn: Boolean = true,
    val enableTsxBridge: Boolean = true,
    val backgroundColorHex: String = "#0B0E14",
    val customIconUri: Uri? = null
)

data class BuildLogLine(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false
)

sealed interface BuildUiState {
    data object Idle : BuildUiState
    data class Building(
        val progress: Float,
        val stageLabel: String,
        val logs: List<BuildLogLine>
    ) : BuildUiState

    data class Success(
        val record: BuildRecord,
        val logs: List<BuildLogLine>
    ) : BuildUiState

    data class Error(
        val message: String,
        val logs: List<BuildLogLine>
    ) : BuildUiState
}

data class BuildRecord(
    val id: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val apkPath: String,
    val previewDirPath: String,
    val entryPoint: String,
    val orientation: String,
    val fullscreen: Boolean,
    val keepScreenOn: Boolean,
    val apkSizeBytes: Long,
    val engines: List<String>,
    val sha256Fingerprint: String,
    val createdAtMillis: Long
)

data class SigningProfile(
    val commonName: String = "AI Studio Creator",
    val organization: String = "StudioAPK Forge",
    val country: String = "IR",
    val sha256Fingerprint: String = "",
    val sha1Fingerprint: String = "",
    val validUntil: String = "",
    val keystoreReady: Boolean = false
)
