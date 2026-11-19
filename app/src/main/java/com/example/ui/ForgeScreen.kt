package com.example.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ApkBuildOptions
import com.example.data.BuildLogLine
import com.example.data.BuildRecord
import com.example.data.BuildUiState
import com.example.data.ProjectAnalysis
import com.example.data.ProjectReadiness
import com.example.data.ScreenOrientationOption

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForgeScreen(
    analysis: ProjectAnalysis?,
    isAnalyzing: Boolean,
    analysisError: String?,
    options: ApkBuildOptions,
    buildState: BuildUiState,
    isPackageValid: Boolean,
    onPickZip: () -> Unit,
    onLoadSample: () -> Unit,
    onUpdateAppName: (String) -> Unit,
    onUpdatePackageName: (String) -> Unit,
    onUpdateVersionName: (String) -> Unit,
    onUpdateVersionCode: (String) -> Unit,
    onUpdateOrientation: (ScreenOrientationOption) -> Unit,
    onUpdateFullscreen: (Boolean) -> Unit,
    onUpdateKeepScreenOn: (Boolean) -> Unit,
    onUpdateTsxBridge: (Boolean) -> Unit,
    onUpdateCustomIcon: (android.net.Uri?) -> Unit,
    onSelectEntryPoint: (String) -> Unit,
    onPreview3d: () -> Unit,
    onStartBuild: () -> Unit,
    onSaveApk: (BuildRecord) -> Unit,
    onShareApk: (BuildRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val iconPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onUpdateCustomIcon(uri)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Cyber-Forge Hero Banner
            item {
                HeroBannerCard()
            }

            // 2. Import ZIP & Sample 3D Game Actions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onPickZip,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(54.dp)
                            .testTag("pick_zip_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("انتخاب فایل ZIP", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = onLoadSample,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("load_sample_button"),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            Icons.Default.Gamepad,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("بازی نمونه 3D", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Loading / Error States
            if (isAnalyzing) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                            Text("در حال استخراج و تحلیل ساختار پروژه AI Studio...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (analysisError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                text = analysisError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 3. Project Inspector & Configuration Form
            if (analysis != null) {
                item {
                    ProjectInspectorCard(
                        analysis = analysis,
                        onSelectEntry = onSelectEntryPoint
                    )
                }

                item {
                    ApkConfigCard(
                        analysis = analysis,
                        options = options,
                        isPackageValid = isPackageValid,
                        onUpdateAppName = onUpdateAppName,
                        onUpdatePackageName = onUpdatePackageName,
                        onUpdateVersionName = onUpdateVersionName,
                        onUpdateVersionCode = onUpdateVersionCode,
                        onUpdateOrientation = onUpdateOrientation,
                        onUpdateFullscreen = onUpdateFullscreen,
                        onUpdateKeepScreenOn = onUpdateKeepScreenOn,
                        onUpdateTsxBridge = onUpdateTsxBridge,
                        onPickIcon = {
                            iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onClearIcon = { onUpdateCustomIcon(null) }
                    )
                }

                // 4. Primary Preview & Build Action Row
                item {
                    val isBuilding = buildState is BuildUiState.Building
                    val canBuild = !isBuilding && isPackageValid && analysis.entryPoint.isNotBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPreview3d,
                            enabled = analysis.entryPoint.isNotBlank() && !isBuilding,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("preview_3d_button"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("پیش‌نمایش 3D", maxLines = 1)
                        }

                        Button(
                            onClick = onStartBuild,
                            enabled = canBuild,
                            modifier = Modifier
                                .weight(1.35f)
                                .height(54.dp)
                                .testTag("build_apk_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isBuilding) "در حال ساخت..." else "ساخت و امضای APK", maxLines = 1)
                        }
                    }
                }
            } else if (!isAnalyzing) {
                item {
                    QuickGuideCard()
                }
            }

            // 5. Build Progress / Terminal Logs / Result Export Card
            item {
                AnimatedVisibility(visible = buildState !is BuildUiState.Idle) {
                    BuildOutputSection(
                        buildState = buildState,
                        onSaveApk = onSaveApk,
                        onShareApk = onShareApk,
                        onPreview3d = onPreview3d
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun HeroBannerCard() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(172.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_studio_hero),
                contentDescription = "نمای کارگاه تبدیل پروژه سه‌بعدی به APK",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x550B0E14),
                                Color(0xCC0B0E14),
                                Color(0xF50B0E14)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.ViewInAr,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "WEBGL • THREE.JS • APK v2 SIGNER",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "تبدیل پروژه‌های AI Studio به APK",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFF1F5FB)
                )
                Text(
                    text = "فایل ZIP خروجی بازی‌های سه‌بعدی را انتخاب کنید، زنده تست بگیرید و APK امضاشده بسازید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBAC6DB)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectInspectorCard(
    analysis: ProjectAnalysis,
    onSelectEntry: (String) -> Unit
) {
    val readinessColor = when (analysis.readiness) {
        ProjectReadiness.READY_BUNDLE -> MaterialTheme.colorScheme.primary
        ProjectReadiness.SOURCE_TSX_ESM -> MaterialTheme.colorScheme.primary
        ProjectReadiness.NO_ENTRY_FOUND -> MaterialTheme.colorScheme.error
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = analysis.sourceFileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val extraInfo = if (analysis.transpiledFileCount > 0) {
                        " • ${analysis.transpiledFileCount} فایل TSX/TS تبدیل شد"
                    } else ""
                    Text(
                        text = "${analysis.fileCount} فایل • ${formatBytes(analysis.totalExtractedBytes)}$extraInfo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = readinessColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = analysis.readiness.labelFa,
                        style = MaterialTheme.typography.labelMedium,
                        color = readinessColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // Detected Engines
            if (analysis.detectedEngines.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    analysis.detectedEngines.forEach { engine ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = engine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // Asset Breakdown Mini Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip(label = "مدل 3D", count = analysis.model3dCount, modifier = Modifier.weight(1f))
                StatChip(label = "اسکریپت", count = analysis.scriptCount, modifier = Modifier.weight(1f))
                StatChip(label = "رسانه/بافت", count = analysis.mediaCount, modifier = Modifier.weight(1f))
                StatChip(label = "شیدر", count = analysis.shaderCount, modifier = Modifier.weight(1f))
            }

            if (analysis.allHtmlEntries.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "فایل ورودی (Entry Point):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        analysis.allHtmlEntries.take(4).forEach { entry ->
                            FilterChip(
                                selected = entry == analysis.entryPoint,
                                onClick = { onSelectEntry(entry) },
                                label = {
                                    Text(entry, style = MaterialTheme.typography.labelSmall)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ApkConfigCard(
    analysis: ProjectAnalysis,
    options: ApkBuildOptions,
    isPackageValid: Boolean,
    onUpdateAppName: (String) -> Unit,
    onUpdatePackageName: (String) -> Unit,
    onUpdateVersionName: (String) -> Unit,
    onUpdateVersionCode: (String) -> Unit,
    onUpdateOrientation: (ScreenOrientationOption) -> Unit,
    onUpdateFullscreen: (Boolean) -> Unit,
    onUpdateKeepScreenOn: (Boolean) -> Unit,
    onUpdateTsxBridge: (Boolean) -> Unit,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit
) {
    val context = LocalContext.current
    val customBitmap = remember(options.customIconUri) {
        options.customIconUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("تنظیمات خروجی APK", style = MaterialTheme.typography.titleMedium)

            // Icon Picker Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onPickIcon)
                    .padding(12.dp)
                    .testTag("pick_icon_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (customBitmap != null) {
                        Image(
                            bitmap = customBitmap.asImageBitmap(),
                            contentDescription = "آیکون انتخابی برنامه",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.Android,
                            contentDescription = "آیکون پیش‌فرض",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("آیکون برنامه اندروید", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (customBitmap != null) "تصویر سفارشی انتخاب شده (برای تغییر ضربه بزنید)"
                        else "تولید خودکار آیکون 3D یا انتخاب تصویر دلخواه",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (customBitmap != null) {
                    OutlinedButton(onClick = onClearIcon, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text("پیش‌فرض", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedTextField(
                value = options.appName,
                onValueChange = onUpdateAppName,
                label = { Text("نام برنامه (App Name)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_name_input"),
                shape = RoundedCornerShape(14.dp)
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = options.packageName,
                    onValueChange = onUpdatePackageName,
                    label = { Text("Package ID (e.g. com.aistudio.game.app)") },
                    singleLine = true,
                    isError = !isPackageValid,
                    supportingText = if (!isPackageValid) {
                        { Text("فرمت صحیح: com.domain.appname (حروف انگلیسی و نقطه)") }
                    } else null,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MaterialTheme.typography.labelSmall.fontFamily
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("package_name_input"),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = options.versionName,
                    onValueChange = onUpdateVersionName,
                    label = { Text("نسخه (Version)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = options.versionCode.toString(),
                    onValueChange = onUpdateVersionCode,
                    label = { Text("کد نسخه") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            // Orientation Chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("جهت صفحه در بازی", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScreenOrientationOption.entries.forEach { opt ->
                        FilterChip(
                            selected = options.orientation == opt,
                            onClick = { onUpdateOrientation(opt) },
                            label = { Text(opt.labelFa, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Runtime Toggles
            ToggleOptionRow(
                title = "اجرای تمام‌صفحه (Immersive)",
                subtitle = "مخفی کردن نوار وضعیت و دکمه‌های سیستم در حین بازی",
                checked = options.fullscreen,
                onCheckedChange = onUpdateFullscreen
            )
            ToggleOptionRow(
                title = "روشن ماندن صفحه",
                subtitle = "جلوگیری از خاموش شدن نمایشگر حین رندر سه‌بعدی",
                checked = options.keepScreenOn,
                onCheckedChange = onUpdateKeepScreenOn
            )
        }
    }
}

@Composable
private fun ToggleOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BuildOutputSection(
    buildState: BuildUiState,
    onSaveApk: (BuildRecord) -> Unit,
    onShareApk: (BuildRecord) -> Unit,
    onPreview3d: () -> Unit
) {
    val logs: List<BuildLogLine> = when (buildState) {
        is BuildUiState.Building -> buildState.logs
        is BuildUiState.Success -> buildState.logs
        is BuildUiState.Error -> buildState.logs
        BuildUiState.Idle -> emptyList()
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(
            1.dp,
            when (buildState) {
                is BuildUiState.Success -> MaterialTheme.colorScheme.primary
                is BuildUiState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (buildState) {
                is BuildUiState.Building -> {
                    Text(buildState.stageLabel, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { buildState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
                is BuildUiState.Success -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Column {
                            Text("APK امضاشده آماده نصب است!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "${buildState.record.packageName} • ${formatBytes(buildState.record.apkSizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onSaveApk(buildState.record) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_apk_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("ذخیره APK", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onShareApk(buildState.record) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("share_apk_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("اشتراک‌گذاری", maxLines = 1)
                        }
                    }

                    OutlinedButton(
                        onClick = onPreview3d,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("اجرای آزمایشی در پیش‌نمایش 3D")
                    }
                }
                is BuildUiState.Error -> {
                    Text(
                        text = buildState.message,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                BuildUiState.Idle -> Unit
            }

            if (logs.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "COMPILER LOG",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            logs.takeLast(8).forEach { line ->
                                Text(
                                    text = "[${line.timestamp}] ${line.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickGuideCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("چطور کار می‌کند؟", style = MaterialTheme.typography.titleMedium)
            }
            GuideStep("۱", "در Google AI Studio بازی یا برنامه سه‌بعدی خود را به صورت ZIP دانلود کنید.")
            GuideStep("۲", "فایل ZIP را بالا انتخاب کنید (یا دکمه «بازی نمونه 3D» را بزنید).")
            GuideStep("۳", "نام برنامه، Package ID، آیکون و جهت صفحه را تنظیم و APK امضاشده (v1+v2) بسازید.")
            GuideStep("۴", "فایل APK را ذخیره کنید و از مدیریت فایل گوشی روی آن بزنید تا نصب شود.")
        }
    }
}

@Composable
private fun GuideStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
