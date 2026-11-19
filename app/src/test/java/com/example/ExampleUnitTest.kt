package com.example

import com.example.engine.TsxModuleTranspiler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ExampleUnitTest {

    @Test
    fun `transpiles AI Studio TSX React Three Fiber module cleanly`() {
        val tmpDir = createTempDirectory("aistudio_tsx_test").toFile()
        try {
            File(tmpDir, "index.css").writeText("body { margin: 0; }")
            File(tmpDir, "types.ts").writeText(
                """
                export interface GameConfig {
                  speed: number;
                  title?: string;
                }
                export enum PlayState {
                  Idle,
                  Running,
                  Over = 'OVER'
                }
                """.trimIndent()
            )
            File(tmpDir, "App.tsx").writeText(
                """
                import React, { useState, type FC } from 'react';
                import { PlayState, type GameConfig } from './types';
                import './index.css';

                interface Props {
                  initialScore: number;
                }

                export const App: FC<Props> = ({ initialScore }) => {
                  const [score, setScore] = useState<number>(initialScore);
                  const [state, setState] = useState<PlayState>(PlayState.Idle);
                  const cfg = { speed: 12 } as GameConfig;
                  return (
                    <div className="hud">
                      <>
                        <h1>Neon 3D</h1>
                        <button onClick={() => setScore(score + 1)}>Score: {score}</button>
                      </>
                    </div>
                  );
                };
                export default App;
                """.trimIndent()
            )
            File(tmpDir, "index.tsx").writeText(
                """
                import React from 'react';
                import ReactDOM from 'react-dom/client';
                import App from './App';

                const rootEl = document.getElementById('root')!;
                ReactDOM.createRoot(rootEl).render(<App initialScore={5} />);
                """.trimIndent()
            )
            File(tmpDir, "index.html").writeText(
                """
                <!DOCTYPE html>
                <html>
                <head><title>3D Game</title></head>
                <body>
                  <div id="root"></div>
                  <script type="module" src="/index.tsx"></script>
                </body>
                </html>
                """.trimIndent()
            )

            val report = TsxModuleTranspiler.processStagedProject(tmpDir, "index.html")
            assertTrue("Expected 3 transpiled files", report.transpiledFileCount == 3)
            assertTrue("Expected warnings empty, got: ${report.warnings}", report.warnings.isEmpty())

            val html = File(tmpDir, "index.html").readText()
            assertTrue("index.html should reference ./index.js", html.contains("./index.js"))
            assertTrue("index.html should include process polyfill", html.contains("window.process"))

            val appJs = File(tmpDir, "App.js").readText()
            assertFalse("interface should be stripped", appJs.contains("interface Props"))
            assertFalse("type import should be stripped", appJs.contains("type FC"))
            assertFalse("generic call <number> should be stripped", appJs.contains("useState<number>"))
            assertFalse("as GameConfig should be stripped", appJs.contains("as GameConfig"))
            assertTrue("JSX should be lowered to React.createElement", appJs.contains("React.createElement(\"div\""))
            assertTrue("CSS import should point to css-proxy.js", appJs.contains("index.css.css-proxy.js"))
            assertTrue("CSS proxy file should exist", File(tmpDir, "index.css.css-proxy.js").exists())

            val typesJs = File(tmpDir, "types.js").readText()
            assertTrue("enum should lower to JS object", typesJs.contains("export const PlayState = { Idle: 0, Running: 1, Over: 'OVER' }"))

            val indexJs = File(tmpDir, "index.js").readText()
            assertTrue("extensionless ./App import should resolve to ./App.js", indexJs.contains("./App.js"))
            assertFalse("non-null ! should be stripped", indexJs.contains("')!"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `handles import type, export type, JSX apostrophes, ternaries, and generic arrows without syntax errors`() {
        val tmpDir = createTempDirectory("aistudio_tsx_regression").toFile()
        try {
            File(tmpDir, "types.ts").writeText(
                """
                export type GamePhase =
                  | { kind: 'menu'; high: number }
                  | { kind: 'play'; speed: number };

                export interface Obstacle {
                  id: string;
                  lane: number;
                }

                export const enum Lane {
                  Left = -1,
                  Center = 0,
                  Right = 1
                }
                """.trimIndent()
            )
            File(tmpDir, "reexports.ts").writeText(
                """
                export type { GamePhase, Obstacle } from './types';
                export { Lane, type Obstacle as RoadObstacle } from './types';
                """.trimIndent()
            )
            File(tmpDir, "Hud.tsx").writeText(
                """
                import React, { useState } from 'react';
                import type { GamePhase, Obstacle } from './types';
                import type DefaultPhase from './types';
                import { Lane } from './reexports';

                interface HudProps {
                  active: boolean;
                  items: Obstacle[];
                }

                class CarPhysics {
                  speed!: number;
                  turbo?: boolean;
                  update(dt: number): void {
                    this.speed = dt * 60;
                  }
                }

                const identity = <T,>(val: T): T => val;

                export const Hud = ({ active: isActive, items }: HudProps) => {
                  const [msg, setMsg] = useState<string>("Ready");
                  const primary = "neon";
                  const secondary = "muted";
                  const mode = isActive ? primary : secondary;
                  const ratio = 10 < 20 && 30 > (5 + 5);

                  return (
                    <div className="hud-root
                      flex-col
                      text-white">
                      {/* Main title with apostrophe & slash */}
                      <h1>Tehran's 3D Dash 60 / 120 FPS</h1>
                      <p>Don't hit barriers! Let's press "Start" now.</p>
                      <div>
                        {items.map((o) => (
                          <span key={o.id}>Lane's #{o.lane} ({mode})</span>
                        ))}
                      </div>
                      <button onClick={() => setMsg("Let's go!")}>{identity("Play")}</button>
                    </div>
                  );
                };
                """.trimIndent()
            )
            File(tmpDir, "index.html").writeText(
                """
                <!DOCTYPE html>
                <html><head></head><body><div id="root"></div><script type="module" src="/Hud.tsx"></script></body></html>
                """.trimIndent()
            )

            val report = TsxModuleTranspiler.processStagedProject(tmpDir, "index.html")
            assertTrue("Expected no transpiler warnings, got: ${report.warnings}", report.warnings.isEmpty())

            val reexportsJs = File(tmpDir, "reexports.js").readText()
            assertFalse("export type ... from must not leave dangling 'from'", reexportsJs.contains("from './types'"))
            assertFalse("type modifier inside export { ... } must be stripped", reexportsJs.contains("RoadObstacle"))
            assertTrue("value export Lane must remain", reexportsJs.contains("export { Lane } from \"./types.js\""))

            val hudJs = File(tmpDir, "Hud.js").readText()
            assertFalse("import type ... from must not leave dangling 'from'", hudJs.contains("from './types'"))
            assertFalse("generic arrow prefix <T,> must be stripped", hudJs.contains("<T,>"))
            assertFalse("class definite assignment !: must be stripped", hudJs.contains("speed!"))
            assertFalse("class optional ?: must be stripped", hudJs.contains("turbo?"))
            assertTrue("ternary must preserve ': secondary'", hudJs.contains("isActive ? primary : secondary"))
            assertTrue("comparison expression must not be stripped as generic", hudJs.contains("10 < 20 && 30 > (5 + 5)"))
            assertTrue("destructured rename active: isActive must be preserved", hudJs.contains("active: isActive"))
            assertTrue("JSX apostrophe text must become escaped JS string", hudJs.contains("\"Tehran's 3D Dash 60 / 120 FPS\""))
            assertTrue("JSX second apostrophe text must stay intact", hudJs.contains("Don't hit barriers! Let's press \\\"Start\\\" now."))
            assertTrue("nested map apostrophe JSX must stay intact", hudJs.contains("\"Lane's #\""))
            assertTrue("button onClick after apostrophe JSX must stay intact", hudJs.contains("setMsg(\"Let's go!\")"))
            assertFalse("multiline className must escape newlines", hudJs.contains("className: \"hud-root\n"))
            assertTrue("multiline className must emit escaped newline", hudJs.contains("hud-root\\n"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `transpiles Three_js classes, inline object types, catch blocks, overloads, ASI, and nested CSS proxies`() {
        val tmpDir = createTempDirectory("aistudio_tsx_advanced").toFile()
        try {
            File(tmpDir, "src/components").mkdirs()
            File(tmpDir, "src/components/Panel.css").writeText(".panel { color: white; }")
            File(tmpDir, "src/components/Panel.tsx").writeText(
                """
                import React, { useRef, useState } from 'react';
                import './Panel.css';
                import './missing.css';

                interface Option { label: string; }
                function Select<T>(props: { value: T; "xlink:href"?: string }) {
                  return null;
                }

                export const Panel = () => {
                  const ref = useRef<HTMLDivElement | null>(null);
                  const [items, setItems] = useState<{ id: number; z: number }[]>([]);
                  const envKey = import.meta.env.VITE_API_KEY;
                  return (
                    <div>
                      <Select<Option> value={{ label: "Car" }} xlink:href="#icon" />
                      {ref.current!}
                    </div>
                  );
                };
                """.trimIndent()
            )
            File(tmpDir, "src/Engine.ts").writeText(
                """
                import * as THREE from 'three';

                export function tone(freq: number): void;
                export function tone(freq: number) {
                  return freq;
                }

                export class WorldEngine {
                  label = true
                    ? 'Day'
                    : 'Night';

                  constructor(public scene: THREE.Scene, private speed: number) {}

                  update(dt: number) {
                    const cfg: { maxSpeed: number; boost?: number } = { maxSpeed: 50 };
                    let a: number
                    let b = 2
                    const calc = (): { w: number; h: number } => ({ w: a || 1, h: b });
                    try {
                      this.speed = (dt! as number) * cfg.maxSpeed + calc().w;
                    } catch (err: unknown) {
                      console.error(err);
                    }
                  }
                }
                """.trimIndent()
            )
            File(tmpDir, "index.html").writeText(
                """
                <!DOCTYPE html>
                <html><head></head><body><div id="root"></div><script type="module">import './src/components/Panel.tsx';</script></body></html>
                """.trimIndent()
            )

            val report = TsxModuleTranspiler.processStagedProject(tmpDir, "index.html")
            assertTrue("Expected no transpiler warnings, got: ${report.warnings}", report.warnings.isEmpty())

            val html = File(tmpDir, "index.html").readText()
            assertTrue("Inline module import should rewrite .tsx to .js", html.contains("./src/components/Panel.js"))

            val panelJs = File(tmpDir, "src/components/Panel.js").readText()
            assertFalse("Inline generic object arg must be stripped", panelJs.contains("{ id: number; z: number }"))
            assertTrue("import.meta.env should be polyfilled safely", panelJs.contains("(import.meta.env || window.process?.env || {}).VITE_API_KEY"))
            assertFalse("Postfix non-null inside JSX braces must be stripped", panelJs.contains("ref.current!"))
            assertTrue("JSX attr with colon must be quoted", panelJs.contains("\"xlink:href\": \"#icon\""))
            assertTrue("Missing CSS should fall back to _missing_css_proxy.js", File(tmpDir, "_missing_css_proxy.js").exists())

            val cssProxyJs = File(tmpDir, "src/components/Panel.css.css-proxy.js").readText()
            assertTrue("CSS proxy href must resolve relative to index.html root", cssProxyJs.contains("./src/components/Panel.css"))

            val engineJs = File(tmpDir, "src/Engine.js").readText()
            assertFalse("Function overload signature without body must be stripped", engineJs.contains("export function tone(freq);"))
            assertTrue("Function implementation must remain", engineJs.contains("export function tone(freq) {"))
            assertTrue("Constructor parameter properties must assign this.scene and this.speed", engineJs.contains("this.scene = scene;") && engineJs.contains("this.speed = speed;"))
            assertTrue("Method without return type must strip param type", engineJs.contains("update(dt) {"))
            assertFalse("Variable object type annotation must be stripped", engineJs.contains("maxSpeed: number"))
            assertFalse("Arrow object return type must be stripped", engineJs.contains("{ w: number; h: number }"))
            assertTrue("ASI newlines after type annotation must preserve next let", engineJs.contains("let b = 2"))
            assertTrue("catch parameter type must be stripped", engineJs.contains("catch (err) {"))
            assertFalse("as number inside expr must be stripped", engineJs.contains("as number"))
            assertTrue("Multiline ternary class field must stay intact", engineJs.contains(": 'Night';"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `transpiles realistic Three_js engine class fields, qualified casts, nested generics, and default ternaries cleanly`() {
        val tmpDir = createTempDirectory("aistudio_engine_ts_test").toFile()
        try {
            File(tmpDir, "engine.ts").writeText(
                """
                import * as THREE from 'three';

                export interface EngineConfig<T = { laneWidth: number }> {
                  config?: T;
                  maxSpeed: number;
                }

                export type LaneBounds<T = { min: number; max: number }> = {
                  bounds: T;
                };

                export class TehranGameEngine {
                  private scene = new THREE.Scene();
                  private clock = new THREE.Clock();
                  private raycaster = new THREE.Raycaster();
                  private lanes = new Map<string, Array<THREE.Mesh>>();
                  private camera: THREE.PerspectiveCamera;
                  private playerMesh!: THREE.Group;

                  constructor(
                    private container: HTMLElement,
                    private useShadows = true
                  ) {
                    this.camera = new THREE.PerspectiveCamera(60, 16 / 9, 0.1, 1000);
                    this.scene.fog = new THREE.FogExp2(0x0b0e14, 0.015) as THREE.FogExp2;
                  }

                  setupMesh(obj: THREE.Object3D, lane = this.useShadows ? 1 : 0): void {
                    (obj as THREE.Mesh).castShadow = true;
                    const mat = (obj as THREE.Mesh).material as THREE.MeshStandardMaterial;
                    mat.emissiveIntensity = 1.2 + lane;
                    this.playerMesh!.position.x = 0;
                  }

                  play(id: string): void;
                  play(id: string) {
                    return id;
                  }
                }
                """.trimIndent()
            )
            File(tmpDir, "index.html").writeText(
                """
                <!DOCTYPE html>
                <html><head></head><body><script type="module" src="/engine.ts"></script></body></html>
                """.trimIndent()
            )

            val report = TsxModuleTranspiler.processStagedProject(tmpDir, "index.html")
            assertTrue("Expected no transpiler warnings, got: ${report.warnings}", report.warnings.isEmpty())

            val js = File(tmpDir, "engine.js").readText()
            assertFalse("interface with generic default object must be stripped", js.contains("EngineConfig"))
            assertFalse("type alias with generic default object must be stripped", js.contains("LaneBounds"))
            assertTrue("Class field new THREE.Scene(); must be preserved", js.contains("scene = new THREE.Scene();"))
            assertTrue("Class field new THREE.Clock(); must be preserved", js.contains("clock = new THREE.Clock();"))
            assertTrue("Class field new THREE.Raycaster(); must be preserved", js.contains("raycaster = new THREE.Raycaster();"))
            assertTrue("Nested generic call new Map<string, Array<THREE.Mesh>>(); must strip <...> cleanly", js.contains("lanes = new Map();"))
            assertTrue("Constructor property without type annotation must emit assignment", js.contains("this.useShadows = useShadows;"))
            assertTrue("Ternary default param must stay intact", js.contains("lane = this.useShadows ? 1 : 0"))
            assertTrue("Qualified cast (obj as THREE.Mesh).castShadow must strip full THREE.Mesh", js.contains("(obj).castShadow = true;"))
            assertTrue("Qualified cast on material must strip full THREE.Mesh and THREE.MeshStandardMaterial", js.contains("const mat = (obj).material;"))
            assertFalse("Qualified cast must not leave as THREE.Mesh, as THREE.MeshStandardMaterial, or as THREE.FogExp2 behind", js.contains("as THREE.") || js.contains(".Mesh") || js.contains(".MeshStandardMaterial"))
            assertTrue("new THREE.FogExp2(...) call must remain while its cast is stripped", js.contains("this.scene.fog = new THREE.FogExp2(0x0b0e14, 0.015);"))
            assertTrue("Non-null assertion before property assignment must be stripped", js.contains("this.playerMesh.position.x = 0;"))
            assertFalse("Class method overload signature without body must be stripped", js.contains("play(id);"))
            assertTrue("Class method implementation must remain", js.contains("play(id) {"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
