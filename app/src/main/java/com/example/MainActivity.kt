package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.BuildRecord
import com.example.ui.BuildsScreen
import com.example.ui.ForgeScreen
import com.example.ui.MainViewModel
import com.example.ui.PreviewScreen
import com.example.ui.SigningScreen
import com.example.ui.theme.StudioApkTheme
import java.io.File

private const val ROUTE_FORGE = "forge"
private const val ROUTE_BUILDS = "builds"
private const val ROUTE_SIGNING = "signing"

private data class NavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioApkTheme {
                StudioApkApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApkApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: ROUTE_FORGE

    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val analysisError by viewModel.analysisError.collectAsStateWithLifecycle()
    val options by viewModel.buildOptions.collectAsStateWithLifecycle()
    val buildState by viewModel.buildUiState.collectAsStateWithLifecycle()
    val buildRecords by viewModel.buildRecords.collectAsStateWithLifecycle()
    val signingProfile by viewModel.signingProfile.collectAsStateWithLifecycle()
    val previewTarget by viewModel.previewTarget.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    // Zero-permission SAF ZIP picker
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importZipUri(uri)
        }
    }

    // Zero-permission SAF APK saver
    var pendingSaveRecord by remember { mutableStateOf<BuildRecord?>(null) }
    val saveApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { destUri ->
        val record = pendingSaveRecord
        pendingSaveRecord = null
        if (destUri != null && record != null) {
            viewModel.saveApkToDocumentUri(record, destUri)
        }
    }

    val startSaveFlow: (BuildRecord) -> Unit = { record ->
        pendingSaveRecord = record
        val safeName = record.appName.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "studio_game" }
        saveApkLauncher.launch("${safeName}_v${record.versionName}.apk")
    }

    val shareApkFlow: (BuildRecord) -> Unit = { record ->
        val apkFile = File(record.apkPath)
        if (apkFile.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "${record.appName} v${record.versionName}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری فایل APK"))
        }
    }

    // Fullscreen Live 3D WebGL Preview overlay
    val activePreview = previewTarget
    if (activePreview != null) {
        PreviewScreen(
            target = activePreview,
            onClose = { viewModel.closePreview() }
        )
        return
    }

    val tabs = remember {
        listOf(
            NavTab(ROUTE_FORGE, R.string.nav_forge, Icons.Filled.Build, Icons.Outlined.Build, "tab_forge"),
            NavTab(ROUTE_BUILDS, R.string.nav_builds, Icons.Filled.Inventory2, Icons.Outlined.Inventory2, "tab_builds"),
            NavTab(ROUTE_SIGNING, R.string.nav_signing, Icons.Filled.Security, Icons.Outlined.Security, "tab_signing")
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(ROUTE_FORGE) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier.testTag(tab.testTag),
                                icon = {
                                    if (tab.route == ROUTE_BUILDS && buildRecords.isNotEmpty()) {
                                        BadgedBox(badge = { Badge { Text("${buildRecords.size}") } }) {
                                            Icon(
                                                if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = stringResource(tab.labelRes)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes)
                                        )
                                    }
                                },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(ROUTE_FORGE) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier.testTag(tab.testTag),
                                icon = {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_FORGE
                    ) {
                        composable(ROUTE_FORGE) {
                            ForgeScreen(
                                analysis = analysis,
                                isAnalyzing = isAnalyzing,
                                analysisError = analysisError,
                                options = options,
                                buildState = buildState,
                                isPackageValid = viewModel.isPackageValid(),
                                onPickZip = {
                                    zipPickerLauncher.launch(
                                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                                    )
                                },
                                onLoadSample = { viewModel.loadSample3dProject() },
                                onUpdateAppName = viewModel::updateAppName,
                                onUpdatePackageName = viewModel::updatePackageName,
                                onUpdateVersionName = viewModel::updateVersionName,
                                onUpdateVersionCode = viewModel::updateVersionCode,
                                onUpdateOrientation = viewModel::updateOrientation,
                                onUpdateFullscreen = viewModel::updateFullscreen,
                                onUpdateKeepScreenOn = viewModel::updateKeepScreenOn,
                                onUpdateTsxBridge = viewModel::updateTsxBridge,
                                onUpdateCustomIcon = viewModel::updateCustomIcon,
                                onSelectEntryPoint = viewModel::selectEntryPoint,
                                onPreview3d = { viewModel.openStagingPreview() },
                                onStartBuild = { viewModel.startApkBuild() },
                                onSaveApk = startSaveFlow,
                                onShareApk = shareApkFlow
                            )
                        }
                        composable(ROUTE_BUILDS) {
                            BuildsScreen(
                                records = buildRecords,
                                onSaveApk = startSaveFlow,
                                onShareApk = shareApkFlow,
                                onPreviewBuild = { viewModel.openBuiltPreview(it) },
                                onDeleteBuild = { viewModel.deleteBuild(it) },
                                onGoToForge = {
                                    navController.navigate(ROUTE_FORGE) {
                                        popUpTo(ROUTE_FORGE) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(ROUTE_SIGNING) {
                            SigningScreen(
                                profile = signingProfile,
                                onRegenerateKey = viewModel::regenerateSigningKey,
                                onShowMessage = viewModel::showMessage
                            )
                        }
                    }
                }
            }
        }
    }
}
