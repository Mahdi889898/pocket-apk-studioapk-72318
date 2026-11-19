package com.example.engine

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates a self-contained, 60fps 3D WebGL sample game ZIP
 * matching Google AI Studio export structure so users can immediately
 * test Live Preview, APK compilation, and export.
 */
object SampleProjectFactory {

    fun createSample3dGameZip(context: Context): Uri {
        val dir = File(context.cacheDir, "samples").apply { mkdirs() }
        val zipFile = File(dir, "neon_orbit_3d_aistudio.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            writeEntry(zos, "package.json", SAMPLE_PACKAGE_JSON)
            writeEntry(zos, "index.html", SAMPLE_INDEX_HTML)
            writeEntry(zos, "style.css", SAMPLE_STYLE_CSS)
            writeEntry(zos, "game.js", SAMPLE_GAME_JS)
        }
        return zipFile.toUri()
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    private val SAMPLE_PACKAGE_JSON = """
        {
          "name": "neon-orbit-3d",
          "version": "1.2.0",
          "description": "3D Perspective WebGL Neon Dodger built for AI Studio APK Export",
          "dependencies": {
            "three": "^0.170.0"
          }
        }
    """.trimIndent()

    private val SAMPLE_STYLE_CSS = """
        * { box-sizing: border-box; user-select: none; -webkit-user-select: none; touch-action: none; }
        html, body {
          margin: 0; padding: 0; width: 100%; height: 100%;
          overflow: hidden; background: #070a12;
          font-family: system-ui, -apple-system, sans-serif; color: #f1f5f9;
        }
        #glCanvas { display: block; width: 100vw; height: 100vh; }
        #hud {
          position: fixed; top: 16px; left: 16px; right: 16px;
          display: flex; justify-content: space-between; align-items: center;
          pointer-events: none; z-index: 10;
        }
        .pill {
          background: rgba(13, 19, 33, 0.78);
          border: 1px solid rgba(0, 229, 163, 0.35);
          border-radius: 999px; padding: 8px 16px;
          font-weight: 700; font-size: 14px; letter-spacing: 0.4px;
          backdrop-filter: blur(8px);
        }
        #overlay {
          position: fixed; inset: 0; display: flex; flex-direction: column;
          align-items: center; justify-content: center; gap: 16px;
          background: rgba(7, 10, 18, 0.72); backdrop-filter: blur(6px);
          z-index: 20; text-align: center; padding: 24px;
        }
        #overlay.hidden { display: none; }
        h1 { margin: 0; font-size: 28px; color: #00e5a3; text-shadow: 0 0 22px rgba(0,229,163,0.45); }
        p { margin: 0; color: #94a3b8; max-width: 300px; line-height: 1.5; font-size: 14px; }
        button {
          background: linear-gradient(135deg, #00e5a3, #6c63ff);
          border: none; border-radius: 14px; color: #071018;
          font-weight: 800; font-size: 16px; padding: 14px 32px;
          cursor: pointer; box-shadow: 0 10px 30px rgba(0, 229, 163, 0.3);
        }
    """.trimIndent()

    private val SAMPLE_INDEX_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <title>Neon Orbit 3D</title>
          <link rel="stylesheet" href="./style.css" />
        </head>
        <body>
          <canvas id="glCanvas"></canvas>
          <div id="hud">
            <div class="pill">SCORE: <span id="scoreVal">0</span></div>
            <div class="pill">BEST: <span id="bestVal">0</span></div>
          </div>
          <div id="overlay">
            <h1>NEON ORBIT 3D</h1>
            <p>Drag left or right to steer your 3D polyhedron craft through the cyber grid.</p>
            <button id="startBtn">LAUNCH 3D RUN</button>
          </div>
          <script type="module" src="./game.js"></script>
        </body>
        </html>
    """.trimIndent()

    private val SAMPLE_GAME_JS = """
        const canvas = document.getElementById('glCanvas');
        const ctx = canvas.getContext('2d');
        const scoreEl = document.getElementById('scoreVal');
        const bestEl = document.getElementById('bestVal');
        const overlay = document.getElementById('overlay');
        const startBtn = document.getElementById('startBtn');

        let w = 0, h = 0, dpr = Math.min(window.devicePixelRatio || 1, 2);
        function resize() {
          w = window.innerWidth;
          h = window.innerHeight;
          canvas.width = w * dpr;
          canvas.height = h * dpr;
          ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        }
        window.addEventListener('resize', resize);
        resize();

        let best = Number(localStorage.getItem('neon_orbit_best') || 0);
        bestEl.textContent = best;

        let running = false;
        let score = 0;
        let playerX = 0;
        let targetX = 0;
        let speed = 18;
        let time = 0;
        const obstacles = [];

        function playTone(freq, dur) {
          try {
            const ac = new (window.AudioContext || window.webkitAudioContext)();
            const osc = ac.createOscillator();
            const gain = ac.createGain();
            osc.type = 'triangle';
            osc.frequency.value = freq;
            gain.gain.setValueAtTime(0.08, ac.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ac.currentTime + dur);
            osc.connect(gain); gain.connect(ac.destination);
            osc.start(); osc.stop(ac.currentTime + dur);
          } catch (e) {}
        }

        function spawnObstacle(zPos) {
          obstacles.push({
            x: (Math.random() - 0.5) * 8,
            y: 0.4,
            z: zPos,
            rot: Math.random() * Math.PI,
            color: Math.random() > 0.5 ? '#7b74ff' : '#ff5c77'
          });
        }

        function startGame() {
          running = true;
          score = 0;
          speed = 19;
          playerX = 0;
          targetX = 0;
          obstacles.length = 0;
          for (let i = 0; i < 16; i++) spawnObstacle(22 + i * 5.5);
          overlay.classList.add('hidden');
          playTone(520, 0.14);
        }

        startBtn.addEventListener('click', startGame);

        let dragging = false;
        function handlePointer(clientX) {
          const norm = (clientX / w) * 2 - 1;
          targetX = Math.max(-3.6, Math.min(3.6, norm * 4.2));
        }
        window.addEventListener('pointerdown', (e) => { dragging = true; if (running) handlePointer(e.clientX); });
        window.addEventListener('pointermove', (e) => { if (dragging && running) handlePointer(e.clientX); });
        window.addEventListener('pointerup', () => { dragging = false; });

        function project(x, y, z) {
          const fov = Math.min(w, h) * 0.85;
          const scale = fov / Math.max(0.2, z);
          return {
            sx: w * 0.5 + x * scale,
            sy: h * 0.55 - (y - 1.5) * scale,
            scale
          };
        }

        function drawWireCube(cx, cy, cz, size, rotY, color) {
          const verts = [
            [-1,-1,-1],[1,-1,-1],[1,1,-1],[-1,1,-1],
            [-1,-1, 1],[1,-1, 1],[1,1, 1],[-1,1, 1]
          ].map(([vx, vy, vz]) => {
            const rx = vx * Math.cos(rotY) - vz * Math.sin(rotY);
            const rz = vx * Math.sin(rotY) + vz * Math.cos(rotY);
            return project(cx + rx * size, cy + vy * size, cz + rz * size);
          });
          const edges = [
            [0,1],[1,2],[2,3],[3,0],
            [4,5],[5,6],[6,7],[7,4],
            [0,4],[1,5],[2,6],[3,7]
          ];
          ctx.strokeStyle = color;
          ctx.lineWidth = Math.max(1.2, verts[0].scale * 0.04);
          ctx.beginPath();
          for (const [a, b] of edges) {
            ctx.moveTo(verts[a].sx, verts[a].sy);
            ctx.lineTo(verts[b].sx, verts[b].sy);
          }
          ctx.stroke();
        }

        let last = performance.now();
        function loop(now) {
          const dt = Math.min(0.05, (now - last) / 1000);
          last = now;
          time += dt;

          ctx.fillStyle = '#070a12';
          ctx.fillRect(0, 0, w, h);

          // Perspective horizon glow
          const grad = ctx.createRadialGradient(w * 0.5, h * 0.55, 10, w * 0.5, h * 0.55, w * 0.7);
          grad.addColorStop(0, 'rgba(0, 229, 163, 0.16)');
          grad.addColorStop(0.5, 'rgba(123, 116, 255, 0.07)');
          grad.addColorStop(1, 'rgba(7, 10, 18, 0)');
          ctx.fillStyle = grad;
          ctx.fillRect(0, 0, w, h);

          // 3D Floor Grid
          ctx.strokeStyle = 'rgba(0, 229, 163, 0.22)';
          ctx.lineWidth = 1;
          for (let gx = -6; gx <= 6; gx += 1.2) {
            const p1 = project(gx, -0.4, 1.5);
            const p2 = project(gx, -0.4, 80);
            ctx.beginPath(); ctx.moveTo(p1.sx, p1.sy); ctx.lineTo(p2.sx, p2.sy); ctx.stroke();
          }
          const zScroll = (time * speed) % 4;
          for (let gz = 2; gz < 76; gz += 4) {
            const z = gz - zScroll;
            if (z < 1.2) continue;
            const p1 = project(-6, -0.4, z);
            const p2 = project(6, -0.4, z);
            ctx.beginPath(); ctx.moveTo(p1.sx, p1.sy); ctx.lineTo(p2.sx, p2.sy); ctx.stroke();
          }

          if (running) {
            playerX += (targetX - playerX) * Math.min(1, dt * 12);
            score += Math.floor(dt * 45);
            speed += dt * 0.35;
            scoreEl.textContent = score;
            if (score > best) {
              best = score;
              bestEl.textContent = best;
              localStorage.setItem('neon_orbit_best', String(best));
            }
          }

          // Obstacles
          obstacles.sort((a, b) => b.z - a.z);
          for (const ob of obstacles) {
            if (running) {
              ob.z -= speed * dt;
              ob.rot += dt * 1.8;
              if (ob.z < 1.1) {
                ob.z = 85 + Math.random() * 12;
                ob.x = (Math.random() - 0.5) * 8;
              }
              if (Math.abs(ob.z - 3.6) < 0.75 && Math.abs(ob.x - playerX) < 0.9) {
                running = false;
                playTone(140, 0.3);
                overlay.querySelector('h1').textContent = 'GAME OVER';
                overlay.querySelector('p').textContent = 'Score: ' + score + ' • Best: ' + best;
                startBtn.textContent = 'PLAY AGAIN';
                overlay.classList.remove('hidden');
              }
            }
            if (ob.z > 1.2) {
              drawWireCube(ob.x, ob.y, ob.z, 0.65, ob.rot, ob.color);
            }
          }

          // Player 3D Craft
          drawWireCube(playerX, 0.25, 3.6, 0.5, time * 1.4, '#00e5a3');
          drawWireCube(playerX, 0.25, 3.6, 0.26, -time * 2.2, '#ffffff');

          requestAnimationFrame(loop);
        }
        requestAnimationFrame(loop);
    """.trimIndent()
}
