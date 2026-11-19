package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.apksig.ApkVerifier
import com.example.data.ApkBuildOptions
import com.example.data.ScreenOrientationOption
import com.example.engine.ApkBuilderEngine
import com.example.engine.BuildProgressEvent
import com.example.engine.SampleProjectFactory
import com.example.engine.SigningKeyManager
import com.example.engine.ZipProjectAnalyzer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("StudioAPK", appName)
    }

    @Test
    fun `build and verify signed 3D game apk end to end`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleUri = SampleProjectFactory.createSample3dGameZip(context)
        val analysis = ZipProjectAnalyzer(context).analyzeAndStage(sampleUri)

        assertEquals("index.html", analysis.entryPoint)
        assertTrue(analysis.detectedEngines.contains("Three.js"))

        val signingManager = SigningKeyManager(context)
        val builder = ApkBuilderEngine(context, signingManager)
        val options = ApkBuildOptions(
            appName = "بازی سه بعدی آزمایشی",
            packageName = "com.aistudio.game.testorbit",
            versionName = "2.1.0",
            versionCode = 7,
            orientation = ScreenOrientationOption.LANDSCAPE,
            fullscreen = true,
            keepScreenOn = true
        )

        val events = builder.buildAndSignApk(analysis, options).toList()
        val completed = events.filterIsInstance<BuildProgressEvent.Completed>().firstOrNull()
        assertTrue("Expected APK build to complete successfully", completed != null)

        val apkFile = File(completed!!.record.apkPath)
        assertTrue("Signed APK file must exist", apkFile.exists() && apkFile.length() > 10_000)

        // Verify APK contents
        ZipFile(apkFile).use { zip ->
            assertTrue(zip.getEntry("AndroidManifest.xml") != null)
            assertTrue(zip.getEntry("classes.dex") != null)
            assertTrue(zip.getEntry("resources.arsc") != null)
            assertTrue(zip.getEntry("assets/studio_config.json") != null)
            assertTrue(zip.getEntry("assets/www/index.html") != null)
            assertTrue(zip.getEntry("res/mipmap-xxxhdpi-v4/ic_launcher.png") != null)
        }

        // Verify cryptographic APK Signature Scheme v1 + v2 with official apksig ApkVerifier
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("APK signature verification failed: ${verification.errors}", verification.isVerified)
        assertTrue("APK v2 signature must be verified", verification.isVerifiedUsingV2Scheme)
    }

    @Test
    fun `import AI Studio TSX ESM zip and build verified APK`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zipFile = File(context.cacheDir, "ai_studio_tsx_sample.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            fun add(name: String, body: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
            add("my-game/package.json", """{"name":"space-shooter-3d","dependencies":{"three":"^0.170.0","react":"^19.0.0","react-dom":"^19.0.0"}}""")
            add("my-game/index.html", """<!DOCTYPE html><html><head><script type="importmap">{"imports":{"three":"https://esm.sh/three","react":"https://esm.sh/react","react-dom/client":"https://esm.sh/react-dom/client"}}</script></head><body><div id="root"></div><script type="module" src="/index.tsx"></script></body></html>""")
            add("my-game/App.tsx", """import React from 'react'; import * as THREE from 'three'; export default function App() { return <div>3D Ready</div>; }""")
            add("my-game/index.tsx", """import React from 'react'; import { createRoot } from 'react-dom/client'; import App from './App'; createRoot(document.getElementById('root')!).render(<App />);""")
        }

        val analysis = ZipProjectAnalyzer(context).analyzeAndStage(android.net.Uri.fromFile(zipFile))
        assertEquals("index.html", analysis.entryPoint)
        assertTrue("Expected transpiled TSX files > 0", analysis.transpiledFileCount >= 2)
        assertTrue("Expected Three.js detected", analysis.detectedEngines.contains("Three.js"))

        val signingManager = SigningKeyManager(context)
        val builder = ApkBuilderEngine(context, signingManager)
        val events = builder.buildAndSignApk(
            analysis,
            ApkBuildOptions(
                appName = "Space Shooter 3D",
                packageName = "com.aistudio.game.spaceshooter3d"
            )
        ).toList()
        val completed = events.filterIsInstance<BuildProgressEvent.Completed>().firstOrNull()
        assertTrue("TSX project APK build must succeed", completed != null)
        val apkFile = File(completed!!.record.apkPath)
        ZipFile(apkFile).use { zip ->
            assertTrue("Transpiled App.js must be packaged inside APK", zip.getEntry("assets/www/App.js") != null)
            assertTrue("Transpiled index.js must be packaged inside APK", zip.getEntry("assets/www/index.js") != null)
        }
    }

    @Test
    fun `tehran dash 3d style zip transpiles cleanly and builds verified APK from bin templates`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zipFile = File(context.cacheDir, "tehran-dash-3d.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            fun add(name: String, body: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
            add("package.json", """{"name":"React-example","dependencies":{"three":"^0.170.0","react":"^19.0.0","react-dom":"^19.0.0"}}""")
            add("index.html", """<!DOCTYPE html><html><head><title>Tehran Dash 3D</title></head><body><div id="root"></div><script type="module" src="/index.tsx"></script></body></html>""")
            add("types.ts", """export type Lane = -1 | 0 | 1; export interface DashState { score: number; lane: Lane; }""")
            add("App.tsx", """
                import React, { useState } from 'react';
                import type { DashState, Lane } from './types';
                export default function App() {
                  const [state, setState] = useState<DashState>({ score: 0, lane: 0 });
                  const boost = state.score > 10 ? "Turbo" : "Normal";
                  return (
                    <div className="screen
                      full">
                      <h1>Tehran's Dash 3D</h1>
                      <p>Don't crash into taxis! Mode: {boost}</p>
                      <button onClick={() => setState({ score: state.score + 1, lane: 1 as Lane })}>Go!</button>
                    </div>
                  );
                }
            """.trimIndent())
            add("index.tsx", """import React from 'react'; import { createRoot } from 'react-dom/client'; import App from './App'; createRoot(document.getElementById('root')!).render(<App />);""")
        }

        val analysis = ZipProjectAnalyzer(context).analyzeAndStage(android.net.Uri.fromFile(zipFile))
        assertEquals("React-example", analysis.suggestedAppName)
        assertEquals("com.aistudio.game.reactexample", analysis.suggestedPackageName)

        val appJs = File(analysis.stagingDirPath, "App.js").readText()
        assertTrue("No dangling 'from' after import type", !appJs.contains("from './types'"))
        assertTrue("JSX apostrophe preserved cleanly", appJs.contains("Don't crash into taxis!"))

        val signingManager = SigningKeyManager(context)
        val builder = ApkBuilderEngine(context, signingManager)
        val events = builder.buildAndSignApk(
            analysis,
            ApkBuildOptions(
                appName = "React-example",
                packageName = "com.aistudio.game.reactexample"
            )
        ).toList()
        val completed = events.filterIsInstance<BuildProgressEvent.Completed>().firstOrNull()
        assertTrue("Expected APK build to succeed for tehran-dash-3d, events=$events", completed != null)

        val apkFile = File(completed!!.record.apkPath)
        ZipFile(apkFile).use { zip ->
            val manifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val manifestText = manifestBytes.toString(Charsets.UTF_8) + manifestBytes.toString(Charsets.UTF_16LE)
            assertTrue("Template manifest must preserve android.permission.INTERNET", manifestText.contains("android.permission.INTERNET"))
            val dexText = zip.getInputStream(zip.getEntry("classes.dex")).readBytes().toString(Charsets.UTF_8)
            assertTrue("Template classes.dex must serve appassets.local", dexText.contains("appassets.local"))
            assertTrue("Template classes.dex must read studio_config.json", dexText.contains("studio_config.json"))
        }
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("APK signature verification failed: ${verification.errors}", verification.isVerified)
    }

    @Test
    fun `query string TSX entry with stray build directory still transpiles cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zipFile = File(context.cacheDir, "mixed_build_tsx.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            fun add(name: String, body: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
            add("build/notes.txt", "stray build dir")
            add("src/main.tsx", """import React from 'react'; const x: number = 1; export const y = x;""")
            add("index.html", """<!DOCTYPE html><html><head></head><body><script type="module" src="/src/main.tsx?v=42"></script></body></html>""")
        }

        val analysis = ZipProjectAnalyzer(context).analyzeAndStage(android.net.Uri.fromFile(zipFile))
        assertTrue("Expected TSX entry to be detected", analysis.hasTsxOrTsEntry)
        assertTrue("Expected transpiled count >= 1", analysis.transpiledFileCount >= 1)
        val mainJs = File(analysis.stagingDirPath, "src/main.js")
        assertTrue("Transpiled src/main.js must exist", mainJs.exists())
        assertTrue("Type annotation must be stripped", !mainJs.readText().contains(": number"))
        val html = File(analysis.stagingDirPath, "index.html").readText()
        assertTrue("index.html must reference ./src/main.js?v=42", html.contains("./src/main.js?v=42"))
    }
}
