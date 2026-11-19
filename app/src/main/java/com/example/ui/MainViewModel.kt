package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ApkBuildOptions
import com.example.data.BuildRecord
import com.example.data.BuildRepository
import com.example.data.BuildUiState
import com.example.data.ProjectAnalysis
import com.example.data.ScreenOrientationOption
import com.example.data.SigningProfile
import com.example.engine.ApkBuilderEngine
import com.example.engine.BuildProgressEvent
import com.example.engine.SampleProjectFactory
import com.example.engine.SigningKeyManager
import com.example.engine.ZipProjectAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class PreviewTarget(
    val title: String,
    val rootDirPath: String,
    val entryPoint: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val analyzer = ZipProjectAnalyzer(context)
    private val signingKeyManager = SigningKeyManager(context)
    private val apkBuilder = ApkBuilderEngine(context, signingKeyManager)
    private val repository = BuildRepository(context)

    private val _analysis = MutableStateFlow<ProjectAnalysis?>(null)
    val analysis: StateFlow<ProjectAnalysis?> = _analysis.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _buildOptions = MutableStateFlow(ApkBuildOptions())
    val buildOptions: StateFlow<ApkBuildOptions> = _buildOptions.asStateFlow()

    private val _buildUiState = MutableStateFlow<BuildUiState>(BuildUiState.Idle)
    val buildUiState: StateFlow<BuildUiState> = _buildUiState.asStateFlow()

    private val _signingProfile = MutableStateFlow(SigningProfile())
    val signingProfile: StateFlow<SigningProfile> = _signingProfile.asStateFlow()

    private val _previewTarget = MutableStateFlow<PreviewTarget?>(null)
    val previewTarget: StateFlow<PreviewTarget?> = _previewTarget.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val buildRecords: StateFlow<List<BuildRecord>> = repository.buildRecordsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _signingProfile.value = signingKeyManager.ensureOrLoadProfile()
        }
    }

    fun showMessage(message: String) {
        _statusBanner.value = message
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun clearAnalysisError() {
        _analysisError.value = null
    }

    fun importZipUri(uri: Uri) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisError.value = null
            _buildUiState.value = BuildUiState.Idle
            try {
                val result = withContext(Dispatchers.IO) {
                    analyzer.analyzeAndStage(uri)
                }
                _analysis.value = result
                _buildOptions.update { current ->
                    current.copy(
                        appName = result.suggestedAppName,
                        packageName = result.suggestedPackageName,
                        enableTsxBridge = result.hasTsxOrTsEntry && !result.hasDistFolder
                    )
                }
            } catch (t: Throwable) {
                _analysisError.value = t.message ?: "خطا در خواندن فایل ZIP"
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun loadSample3dProject() {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisError.value = null
            _buildUiState.value = BuildUiState.Idle
            try {
                val uri = withContext(Dispatchers.IO) {
                    SampleProjectFactory.createSample3dGameZip(context)
                }
                val result = withContext(Dispatchers.IO) {
                    analyzer.analyzeAndStage(uri)
                }
                _analysis.value = result
                _buildOptions.update {
                    it.copy(
                        appName = "Neon Orbit 3D",
                        packageName = "com.aistudio.game.neonorbit3d",
                        versionName = "1.2.0",
                        versionCode = 1,
                        orientation = ScreenOrientationOption.PORTRAIT,
                        fullscreen = true,
                        keepScreenOn = true,
                        enableTsxBridge = false
                    )
                }
            } catch (t: Throwable) {
                _analysisError.value = t.message ?: "خطا در ایجاد نمونه سه‌بعدی"
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun updateAppName(name: String) {
        _buildOptions.update { it.copy(appName = name) }
    }

    fun updatePackageName(pkg: String) {
        _buildOptions.update { it.copy(packageName = pkg) }
    }

    fun updateVersionName(ver: String) {
        _buildOptions.update { it.copy(versionName = ver) }
    }

    fun updateVersionCode(codeText: String) {
        val parsed = codeText.filter { it.isDigit() }.toIntOrNull() ?: 1
        _buildOptions.update { it.copy(versionCode = parsed.coerceAtLeast(1)) }
    }

    fun updateOrientation(option: ScreenOrientationOption) {
        _buildOptions.update { it.copy(orientation = option) }
    }

    fun updateFullscreen(enabled: Boolean) {
        _buildOptions.update { it.copy(fullscreen = enabled) }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        _buildOptions.update { it.copy(keepScreenOn = enabled) }
    }

    fun updateTsxBridge(enabled: Boolean) {
        _buildOptions.update { it.copy(enableTsxBridge = enabled) }
    }

    fun updateCustomIcon(uri: Uri?) {
        _buildOptions.update { it.copy(customIconUri = uri) }
    }

    fun selectEntryPoint(entry: String) {
        _analysis.update { it?.copy(entryPoint = entry) }
    }

    fun isPackageValid(): Boolean = apkBuilder.isValidPackageName(_buildOptions.value.packageName)

    fun startApkBuild() {
        val currentAnalysis = _analysis.value ?: return
        val options = _buildOptions.value
        viewModelScope.launch {
            apkBuilder.buildAndSignApk(currentAnalysis, options).collect { event ->
                when (event) {
                    is BuildProgressEvent.Progress -> {
                        val existingLogs = (_buildUiState.value as? BuildUiState.Building)?.logs.orEmpty()
                        _buildUiState.value = BuildUiState.Building(
                            progress = event.fraction,
                            stageLabel = event.stageLabel,
                            logs = existingLogs + event.log
                        )
                    }
                    is BuildProgressEvent.Completed -> {
                        repository.addBuildRecord(event.record)
                        _buildUiState.value = BuildUiState.Success(event.record, event.logs)
                    }
                    is BuildProgressEvent.Failed -> {
                        _buildUiState.value = BuildUiState.Error(event.errorMessage, event.logs)
                    }
                }
            }
        }
    }

    fun resetBuildState() {
        _buildUiState.value = BuildUiState.Idle
    }

    fun deleteBuild(record: BuildRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBuildRecord(record)
            _statusBanner.value = "فایل ${record.appName} حذف شد."
        }
    }

    fun saveApkToDocumentUri(record: BuildRecord, destUri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val src = File(record.apkPath)
                    require(src.exists()) { "فایل APK یافت نشد." }
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        FileInputStream(src).use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("امکان نوشتن در مسیر انتخاب‌شده وجود ندارد.")
                }
                _statusBanner.value = "فایل APK با موفقیت در حافظه ذخیره شد. برای نصب، روی آن در مدیریت فایل کلیک کنید."
            } catch (t: Throwable) {
                _statusBanner.value = "خطا در ذخیره فایل: ${t.message}"
            }
        }
    }

    fun regenerateSigningKey(commonName: String, organization: String, country: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = signingKeyManager.generateNewKeystore(commonName, organization, country)
            _signingProfile.value = profile
            _statusBanner.value = "کلید امضای جدید RSA-2048 ساخته شد."
        }
    }

    fun openStagingPreview() {
        val current = _analysis.value ?: return
        if (current.entryPoint.isBlank()) return
        _previewTarget.value = PreviewTarget(
            title = _buildOptions.value.appName.ifBlank { current.suggestedAppName },
            rootDirPath = current.stagingDirPath,
            entryPoint = current.entryPoint
        )
    }

    fun openBuiltPreview(record: BuildRecord) {
        _previewTarget.value = PreviewTarget(
            title = record.appName,
            rootDirPath = record.previewDirPath,
            entryPoint = record.entryPoint
        )
    }

    fun closePreview() {
        _previewTarget.value = null
    }
}
