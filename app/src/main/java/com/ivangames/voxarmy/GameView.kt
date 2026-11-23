package com.ivangames.voxarmy

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.PriorityQueue
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// kind: 0 = солдат, 1 = рабочий
class Unit2(var x: Float, var y: Float, val team: Int, val kind: Int) {
    var selected = false
    var job = 0
    var timer = 0f
    var carrying = false
    var hp = if (kind == 0) 40 else 20
    val maxHp = hp
    var cd = 0f
    var hitTimer = 0f
    var tgtUnit: Unit2? = null
    var tgtCastle = -1
    var autoT = false
    var repath = 0f
    var goalX = 0f
    var goalY = 0f
    val path = ArrayList<Float>()
    var pi = 0
    val moving: Boolean get() = pi * 2 < path.size
}

class Castle(val x: Float, val y: Float, val team: Int) {
    val maxHp = 500
    var hp = 500
    var hitTimer = 0f
}

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private class Node(val i: Int, val f: Float)

    private var thread: Thread? = null
    @Volatile private var running = false

    // ---------- краски ----------
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val fill = Paint()
    private val skyPaint = Paint()
    private val selPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(80, 255, 80) }
    private val tgtPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(255, 230, 80) }
    private val goldDot = Paint().apply { color = Color.rgb(250, 215, 70) }
    private val barBg = Paint().apply { color = Color.rgb(20, 20, 20) }
    private val barFg = Paint()
    private val hudBg = Paint().apply { color = Color.argb(170, 20, 20, 30) }
    private val hudText = Paint().apply {
        color = Color.WHITE; textSize = 40f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
    }
    private val btnPaint = Paint().apply { color = Color.rgb(60, 70, 110) }
    private val btnOff = Paint().apply { color = Color.rgb(70, 70, 70) }
    private val btnText = Paint().apply {
        color = Color.WHITE; textSize = 34f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val bigText = Paint().apply {
        textSize = 110f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val textP = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val titleP = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.05f
    }
    private val overlay = Paint().apply { color = Color.argb(160, 0, 0, 0) }

    // ---------- спрайты ----------
    private fun load(name: String): Bitmap =
        context.assets.open("sprites/$name").use { BitmapFactory.decodeStream(it) }

    private val grass = listOf(load("grass0.png"), load("grass1.png"), load("grass2.png"))
    private val water = load("water.png")
    private val dirt = load("dirt.png")
    private val tree = load("tree.png")
    private val castleBlue = load("castle_blue.png")
    private val castleRed = load("castle_red.png")
    private val footBlue = load("footman_blue.png")
    private val footRed = load("footman_red.png")
    private val workRed = load("worker_red.png")
    private val mine = load("goldmine.png")

    // ---------- карта ----------
    private val mapW = 48
    private val mapH = 48
    private val tile = 16
    private val speed = 40f
    private val ground = Array(mapH) { IntArray(mapW) }
    private val trees = Array(mapH) { BooleanArray(mapW) }
    private val blocked = BooleanArray(mapW * mapH)
    private val units = CopyOnWriteArrayList<Unit2>()

    private val castles = arrayOf(
        Castle(6f * tile, 6f * tile, 0),
        Castle(40f * tile, 40f * tile, 1)
    )
    private val mineXs = floatArrayOf(15f * tile, 31f * tile)
    private val mineYs = floatArrayOf(6f * tile, 40f * tile)

    // ---------- состояние ----------
    private val gold = intArrayOf(150, 150)
    @Volatile private var screenState = 0 // 0 меню, 1 игра, 2 заглушка
    @Volatile private var state = 0       // 0 идёт, 1 победа, 2 поражение
    private var stubTitle = ""
    private var menuOpen = false
    @Volatile private var rectMode = false
    @Volatile private var selecting = false
    private var msg = ""
    private var msgTime = 0f
    private var aiTimer = 0f
    private var waveSize = 5
    private var menuT = 0f
    private var skyH = -1
    private val actions = ConcurrentLinkedQueue<() -> Unit>()

    // ---------- камера ----------
    @Volatile private var zoom = 6f
    private var minZoom = 2f
    private val maxZoom = 16f
    @Volatile private var camX = 0f
    @Volatile private var camY = 0f
    @Volatile private var camVx = 0f
    @Volatile private var camVy = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var velX = 0f
    private var velY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var touchMode = 0 // 0 мир, 1 ползунок, 2 кнопка HUD
    private var pressedBtn = -1
    private val dst = RectF()

    // кнопки (экранные координаты)
    private val btnWorker = RectF()
    private val btnSoldier = RectF()
    private val btnHud = Array(3) { RectF() }
    private val menuBtns = Array(3) { RectF() }
    private val backBtn = RectF()

    private val starX = FloatArray(60)
    private val starY = FloatArray(60)

    init {
        holder.addCallback(this)
        val rr = Random(7)
        for (i in 0 until 60) { starX[i] = rr.nextFloat(); starY[i] = rr.nextFloat() }
        val rnd = Random(42)
        for (y in 0 until mapH) for (x in 0 until mapW) {
            val edge = x < 2 || y < 2 || x >= mapW - 2 || y >= mapH - 2
            ground[y][x] = if (edge) 3 else rnd.nextInt(3)
            if (!edge && rnd.nextInt(100) < 8) trees[y][x] = true
        }
        for (y in 20..26) for (x in 10..38) {
            ground[y][x] = 4
            trees[y][x] = false
        }
        for (y in 4..12) for (x in 4..18) trees[y][x] = false
        for (y in 34..44) for (x in 28..44) trees[y][x] = false
        for (y in 0 until mapH) for (x in 0 until mapW) {
            blocked[y * mapW + x] = ground[y][x] == 3 || trees[y][x]
        }
        for (c in castles) block(c.x, c.y)
        for (i in 0..1) block(mineXs[i], mineYs[i])
    }

    private fun block(px: Float, py: Float) {
        val tx = (px / tile).toInt()
        val ty = (py / tile).toInt()
        for (dy in 0..1) for (dx in 0..1) blocked[(ty + dy) * mapW + tx + dx] = true
    }

    private fun startGame() {
        newGame()
        zoom = 6f.coerceIn(minZoom, maxZoom)
        camX = (castles[0].x + 16f) * zoom - width / 2f
        camY = (castles[0].y + 16f) * zoom - height / 2f
        clampCamera()
        screenState = 1
    }

    private fun newGame() {
        units.clear()
        gold[0] = 150
        gold[1] = 150
        for (c in castles) { c.hp = c.maxHp; c.hitTimer = 0f }
        waveSize = 5
        aiTimer = 0f
        menuOpen = false
        rectMode = false
        selecting = false
        for (i in 0..2) units.add(Unit2((9 + i) * tile.toFloat(), 10f * tile, 0, 0))
        for (i in 0..1) units.add(Unit2((9 + i) * tile.toFloat(), 12f * tile, 0, 1))
        for (i in 0..1) units.add(Unit2((36 - i) * tile.toFloat(), 38f * tile, 1, 0))
        for (i in 0..1) {
            val w = Unit2((36 + i) * tile.toFloat(), 42f * tile, 1, 1)
            w.job = 1
            moveTo(w, mineXs[1] + 8f, mineYs[1] + 24f)
            units.add(w)
        }
        state = 0
    }

    fun resume() {
        running = true
        thread = Thread(this).also { it.start() }
    }

    fun pause() {
        running = false
        thread?.join()
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val h = holder
            if (!h.surface.isValid) { Thread.sleep(10); continue }
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
            last = now
            update(dt)
            val c = (if (Build.VERSION.SDK_INT >= 26) h.lockHardwareCanvas() else h.lockCanvas())
                ?: continue
            try { drawFrame(c) } finally { h.unlockCanvasAndPost(c) }
        }
    }

    // ================= ПУТЬ (A*) =================

    private fun idx(x: Int, y: Int) = y * mapW + x

    private fun heur(x: Int, y: Int, gx: Int, gy: Int): Float {
        val dx = abs(x - gx)
        val dy = abs(y - gy)
        return (dx + dy) - 0.586f * minOf(dx, dy)
    }

    private fun nearestFree(gx: Int, gy: Int, sx: Int, sy: Int): Int {
        for (r in 1..10) {
            var best = -1
            var bd = Float.MAX_VALUE
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(abs(dx), abs(dy)) != r) continue
                val nx = gx + dx
                val ny = gy + dy
                if (nx < 0 || ny < 0 || nx >= mapW || ny >= mapH) continue
                if (blocked[idx(nx, ny)]) continue
                val d = hypot((nx - sx).toFloat(), (ny - sy).toFloat())
                if (d < bd) { bd = d; best = idx(nx, ny) }
            }
            if (best >= 0) return best
        }
        return -1
    }

    private fun findPath(sx: Int, sy: Int, gx: Int, gy: Int): IntArray? {
        val n = mapW * mapH
        val g = FloatArray(n) { Float.MAX_VALUE }
        val from = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        val open = PriorityQueue<Node>(64, Comparator<Node> { a, b -> a.f.compareTo(b.f) })
        val s = idx(sx, sy)
        val goal = idx(gx, gy)
        g[s] = 0f
        open.add(Node(s, heur(sx, sy, gx, gy)))
        while (open.isNotEmpty()) {
            val node = open.poll() ?: break
            val ci = node.i
            if (closed[ci]) continue
            closed[ci] = true
            if (ci == goal) break
            val cx = ci % mapW
            val cy = ci / mapW
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= mapW || ny >= mapH) continue
                val ni = idx(nx, ny)
                if (blocked[ni] || closed[ni]) continue
                if (dx != 0 && dy != 0 && (blocked[idx(cx + dx, cy)] || blocked[idx(cx, cy + dy)])) continue
                val ng = g[ci] + (if (dx != 0 && dy != 0) 1.414f else 1f)
                if (ng < g[ni]) {
                    g[ni] = ng
                    from[ni] = ci
                    open.add(Node(ni, ng + heur(nx, ny, gx, gy)))
                }
            }
        }
        if (from[goal] == -1 && goal != s) return null
        val list = ArrayList<Int>()
        var cur = goal
        while (cur != -1) { list.add(cur); cur = from[cur] }
        list.reverse()
        return list.toIntArray()
    }

    private fun moveTo(u: Unit2, tx0: Float, ty0: Float) {
        val tx = tx0.coerceIn(0f, mapW * tile - 16f)
        val ty = ty0.coerceIn(0f, mapH * tile - 16f)
        u.goalX = tx
        u.goalY = ty
        u.path.clear()
        u.pi = 0
        val sx = ((u.x + 8f) / tile).toInt().coerceIn(0, mapW - 1)
        val sy = ((u.y + 8f) / tile).toInt().coerceIn(0, mapH - 1)
        var gx = ((tx + 8f) / tile).toInt().coerceIn(0, mapW - 1)
        var gy = ((ty + 8f) / tile).toInt().coerceIn(0, mapH - 1)
        var exact = true
        if (blocked[idx(gx, gy)]) {
            val f = nearestFree(gx, gy, sx, sy)
            if (f < 0) return
            gx = f % mapW
            gy = f / mapW
            exact = false
        }
        if (sx == gx && sy == gy) {
            if (exact) { u.path.add(tx); u.path.add(ty) }
            else { u.path.add(gx * tile.toFloat()); u.path.add(gy * tile.toFloat()) }
            return
        }
        val p = findPath(sx, sy, gx, gy) ?: return
        for (k in 1 until p.size) {
            u.path.add((p[k] % mapW) * tile.toFloat())
            u.path.add((p[k] / mapW) * tile.toFloat())
        }
        if (exact && u.path.size >= 2) {
            u.path[u.path.size - 2] = tx
            u.path[u.path.size - 1] = ty
        }
    }

    private fun stop(u: Unit2) {
        u.path.clear()
        u.pi = 0
    }

    // ================= СТОЛКНОВЕНИЯ =================

    private fun blockedBox(px: Float, py: Float, r: Float): Boolean {
        val xs = floatArrayOf(px - r, px + r)
        val ys = floatArrayOf(py - r, py + r)
        for (xx in xs) for (yy in ys) {
            val tx = (xx / tile).toInt().coerceIn(0, mapW - 1)
            val ty = (yy / tile).toInt().coerceIn(0, mapH - 1)
            if (blocked[idx(tx, ty)]) return true
        }
        return false
    }

    private fun tryShift(u: Unit2, dx: Float, dy: Float) {
        val nx = u.x + dx
        val ny = u.y + dy
        if (!blockedBox(nx + 8f, ny + 8f, 5f)) { u.x = nx; u.y = ny }
    }

    private fun separate() {
        val n = units.size
        for (i in 0 until n) {
            val a = units[i]
            for (j in i + 1 until n) {
                val b = units[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = hypot(dx, dy)
                if (d < 12f) {
                    val nx: Float
                    val ny: Float
                    if (d < 0.01f) { nx = 1f; ny = 0f } else { nx = dx / d; ny = dy / d }
                    val push = (12f - d) * 0.35f
                    tryShift(a, -nx * push, -ny * push)
                    tryShift(b, nx * push, ny * push)
                }
            }
        }
    }

    // ================= ЛОГИКА =================

    private fun update(dt: Float) {
        menuT += dt
        while (true) {
            val a = actions.poll() ?: break
            a()
        }
        if (msgTime > 0f) msgTime -= dt
        if (screenState != 1) return
        if (camVx != 0f || camVy != 0f) {
            camX += camVx * dt
            camY += camVy * dt
            val f = 0.92f.pow(dt * 60f)
            camVx *= f
            camVy *= f
            if (hypot(camVx, camVy) < 15f) { camVx = 0f; camVy = 0f }
            clampCamera()
        }
        if (state != 0) return
        updateAi(dt)
        for (u in units) {
            if (u.hitTimer > 0f) u.hitTimer -= dt
            if (u.moving) {
                val tx = u.path[u.pi * 2]
                val ty = u.path[u.pi * 2 + 1]
                val dx = tx - u.x
                val dy = ty - u.y
                val d = hypot(dx, dy)
                val step = speed * dt
                if (d <= step) {
                    u.x = tx; u.y = ty; u.pi++
                } else {
                    u.x += dx / d * step
                    u.y += dy / d * step
                }
                if (!u.moving) { u.path.clear(); u.pi = 0 }
            }
            if (u.kind == 1) updateWorker(u, dt) else updateSoldier(u, dt)
        }
        separate()
        for (c in castles) if (c.hitTimer > 0f) c.hitTimer -= dt
        val dead = units.filter { it.hp <= 0 }
        if (dead.isNotEmpty()) units.removeAll(dead)
    }

    private fun updateWorker(u: Unit2, dt: Float) {
        val c = castles[u.team]
        when (u.job) {
            1 -> if (!u.moving) { u.job = 2; u.timer = 3f }
            2 -> {
                u.timer -= dt
                if (u.timer <= 0f) {
                    u.carrying = true
                    u.job = 3
                    moveTo(u, c.x + 8f, c.y + 24f)
                }
            }
            3 -> if (!u.moving) {
                gold[u.team] += 10
                u.carrying = false
                u.job = 1
                moveTo(u, mineXs[u.team] + 8f, mineYs[u.team] + 24f)
            }
        }
    }

    private fun nearestEnemy(u: Unit2, range: Float): Unit2? {
        var best: Unit2? = null
        var bd = range
        for (o in units) {
            if (o.team == u.team || o.hp <= 0) continue
            val d = hypot(o.x - u.x, o.y - u.y)
            if (d < bd) { bd = d; best = o }
        }
        return best
    }

    private fun rectDist(u: Unit2, rx: Float, ry: Float, rw: Float, rh: Float): Float {
        val cx = u.x + 8f
        val cy = u.y + 8f
        val dx = maxOf(rx - cx, 0f, cx - (rx + rw))
        val dy = maxOf(ry - cy, 0f, cy - (ry + rh))
        return hypot(dx, dy)
    }

    private fun updateSoldier(u: Unit2, dt: Float) {
        u.cd -= dt
        u.repath -= dt
        var t = u.tgtUnit
        if (t != null && t.hp <= 0) { t = null; u.tgtUnit = null }
        if (t != null && u.autoT && hypot(t.x - u.x, t.y - u.y) > 140f) { t = null; u.tgtUnit = null }
        if (t == null && (!u.moving || u.tgtCastle >= 0)) {
            t = nearestEnemy(u, 50f)
            if (t != null) { u.tgtUnit = t; u.autoT = true }
        }
        if (t != null) {
            val d = hypot(t.x - u.x, t.y - u.y)
            if (d <= 18f) {
                stop(u)
if (u.cd <= 0f) {
                    t.hp -= 8
                    t.hitTimer = 3f
                    u.hitTimer = 3f
                    u.cd = 0.8f
                }
            } else if (u.repath <= 0f || !u.moving) {
                moveTo(u, t.x, t.y)
                u.repath = 0.5f
            }
            return
        }
        if (u.tgtCastle < 0 && !u.moving) {
            val ec = castles[1 - u.team]
            if (ec.hp > 0 && rectDist(u, ec.x, ec.y, 32f, 32f) <= 30f) u.tgtCastle = ec.team
        }
        if (u.tgtCastle >= 0) {
            val c = castles[u.tgtCastle]
            if (c.hp <= 0) { u.tgtCastle = -1; return }
            if (rectDist(u, c.x, c.y, 32f, 32f) <= 22f) {
                stop(u)
                if (u.cd <= 0f) {
                    c.hp -= 8
                    c.hitTimer = 3f
                    u.hitTimer = 3f
                    u.cd = 0.8f
                    if (c.hp <= 0) state = if (c.team == 1) 1 else 2
                }
            } else if (!u.moving || u.repath <= 0f) {
                moveTo(u, c.x + 8f, c.y + 34f)
                u.repath = 1f
            }
        }
    }

    private fun train(team: Int, kind: Int, cost: Int): Boolean {
        if (gold[team] < cost) return false
        gold[team] -= cost
        val c = castles[team]
        val n = units.count { it.team == team }
        val u = Unit2(c.x + 8f + (n % 5) * 4f, c.y + 34f, team, kind)
        units.add(u)
        moveTo(u, c.x + 4f + (n % 6) * 12f, c.y + 50f + ((n / 6) % 3) * 16f)
        if (kind == 1 && team == 1) {
            u.job = 1
            moveTo(u, mineXs[1] + 8f, mineYs[1] + 24f)
        }
        return true
    }

    private fun updateAi(dt: Float) {
        aiTimer += dt
        if (aiTimer < 1.5f) return
        aiTimer = 0f
        val workers = units.count { it.team == 1 && it.kind == 1 }
        if (workers < 4) train(1, 1, 50) else train(1, 0, 80)
        for (u in units) {
            if (u.team == 1 && u.kind == 1 && u.job == 0 && !u.moving) {
                u.job = 1
                moveTo(u, mineXs[1] + 8f, mineYs[1] + 24f)
            }
        }
        val idle = units.filter { it.team == 1 && it.kind == 0 && it.tgtCastle < 0 }
        if (idle.size >= waveSize) {
            for (u in idle) { u.tgtCastle = 0; u.tgtUnit = null }
            waveSize += 2
        }
    }

    // ================= РИСОВАНИЕ =================

    private fun drawFrame(c: Canvas) {
        when (screenState) {
            0 -> drawMenu(c)
            2 -> drawStub(c)
            else -> drawGame(c)
        }
    }

    private fun drawSprite(c: Canvas, b: Bitmap, x: Float, y: Float, s: Float) {
        dst.set(x, y, x + b.width * s, y + b.height * s)
        c.drawBitmap(b, null, dst, paint)
    }

    private fun pixelButton(c: Canvas, r: RectF, label: String, ts: Float, base: Int, light: Int, dark: Int) {
        val b = maxOf(3f, r.height() * 0.07f)
        fill.color = Color.rgb(18, 12, 30)
        c.drawRect(r, fill)
        fill.color = dark
        c.drawRect(r.left + b, r.top + b, r.right - b, r.bottom - b, fill)
        fill.color = base
        c.drawRect(r.left + b, r.top + b, r.right - b, r.bottom - b * 2.2f, fill)
        fill.color = light
        c.drawRect(r.left + b, r.top + b, r.right - b, r.top + b * 2f, fill)
        textP.textSize = ts
        textP.color = Color.rgb(20, 14, 34)
        c.drawText(label, r.centerX() + 2f, r.centerY() + ts * 0.35f + 2f, textP)
        textP.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + ts * 0.35f, textP)
    }

    private fun drawBackground(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = maxOf(4f, (h / 110f).roundToInt().toFloat())
        if (skyH != height) {
            skyH = height
            skyPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.8f,
                intArrayOf(Color.rgb(16, 12, 46), Color.rgb(52, 28, 100), Color.rgb(190, 70, 120), Color.rgb(255, 150, 80)),
                floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, skyPaint)

        // звёзды
        for (i in 0 until 60) {
            val a = (130 + 120 * sin(menuT * 1.5f + i)).toInt().coerceIn(0, 255)
            fill.color = Color.argb(a, 255, 255, 230)
            val sx = starX[i] * w
            val sy = starY[i] * h * 0.45f
            c.drawRect(sx, sy, sx + u * 0.6f, sy + u * 0.6f, fill)
        }

        // солнце
        val sr = 12
        val scx = (w * 0.70f / u).toInt()
        val scy = (h * 0.55f / u).toInt()
        fill.color = Color.rgb(255, 226, 130)
        for (dy in -sr..sr) for (dx in -sr..sr) {
            if (dx * dx + dy * dy <= sr * sr) {
                c.drawRect((scx + dx) * u, (scy + dy) * u, (scx + dx + 1) * u, (scy + dy + 1) * u, fill)
            }
        }

        val gTop = h * 0.80f
        val cols = (w / u).toInt() + 1

        // дальние горы
        fill.color = Color.rgb(84, 44, 120)
        for (i in 0 until cols) {
            val hh = (10 + 7 * sin(i * 0.09f + 0.5f) + 4 * sin(i * 0.23f + 2f)).toInt().coerceAtLeast(3)
            c.drawRect(i * u, gTop - hh * u, (i + 1) * u, gTop + u, fill)
        }
        // ближние горы
        fill.color = Color.rgb(44, 26, 84)
        for (i in 0 until cols) {
            val hh = (5 + 4 * sin(i * 0.14f + 3f) + 3 * sin(i * 0.31f)).toInt().coerceAtLeast(2)
            c.drawRect(i * u, gTop - hh * u, (i + 1) * u, gTop + u, fill)
        }

        // облака
        fill.color = Color.argb(150, 255, 190, 205)
        for (k in 0 until 4) {
            val cx = ((menuT * (14f + k * 6f) + k * w / 4f) % (w + 20f * u)) - 10f * u
            val cy = h * (0.10f + 0.09f * k)
            c.drawRect(cx, cy + u, cx + u * 8f, cy + u * 2f, fill)
            c.drawRect(cx + u * 2f, cy, cx + u * 6f, cy + u, fill)
            c.drawRect(cx + u, cy + u * 2f, cx + u * 7f, cy + u * 3f, fill)
        }

        // земля
        val sp = maxOf(3f, (h / 170f).roundToInt().toFloat())
        val ts = 16f * sp
        var row = 0
        var yy = gTop
        while (yy < h) {
            var col = 0
            var xx = 0f
            while (xx < w) {
                drawSprite(c, grass[(col + row * 2) % 3], xx, yy, sp)
                xx += ts
                col++
            }
            yy += ts
            row++
        }

        // замки и деревья
        val base = gTop + ts * 0.9f
        drawSprite(c, castleBlue, w * 0.05f, base - 32f * sp, sp)
        drawSprite(c, castleRed, w * 0.95f - 32f * sp, base - 32f * sp, sp)
        for (fx in floatArrayOf(0.22f, 0.29f, 0.71f, 0.78f)) {
            drawSprite(c, tree, w * fx, base - 16f * sp, sp)
        }

        // маршируют солдаты
        val span = w + 400f
        for (i in 0..3) {
            val bx = ((menuT * 70f + i * 150f) % span) - 200f
            val by = gTop + ts * 0.70f - abs(sin(menuT * 9f + i)) * sp * 1.5f
            drawSprite(c, footBlue, bx, by, sp)
            val rx = (w + 200f) - ((menuT * 70f + i * 150f + 75f) % span)
            val ry = gTop + ts * 1.05f - abs(sin(menuT * 9f + i + 1f)) * sp * 1.5f
            drawSprite(c, footRed, rx, ry, sp)
        }
    }

    private fun drawTitle(c: Canvas, text: String, ty: Float, size: Float) {
        val h = height.toFloat()
        val w = width.toFloat()
        val off = maxOf(4f, h * 0.008f)
        titleP.textSize = size
        titleP.color = Color.rgb(40, 8, 40)
        c.drawText(text, w / 2f + off * 1.6f, ty + off * 1.6f, titleP)
        c.drawText(text, w / 2f - off, ty, titleP)
        c.drawText(text, w / 2f + off, ty, titleP)
        c.drawText(text, w / 2f, ty - off, titleP)
        c.drawText(text, w / 2f, ty + off, titleP)
        titleP.color = Color.rgb(255, 226, 120)
        c.drawText(text, w / 2f, ty, titleP)
    }

    private fun drawMenu(c: Canvas) {
        drawBackground(c)
        val w = width.toFloat()
        val h = height.toFloat()
        drawTitle(c, "VOXARMY", h * 0.25f, h * 0.16f)
        textP.textSize = h * 0.045f
        textP.color = Color.rgb(255, 225, 240)
        c.drawText("ПИКСЕЛЬНАЯ СТРАТЕГИЯ", w / 2f, h * 0.32f, textP)

        val bw = minOf(w * 0.5f, 640f)
        val bh = (h * 0.135f).coerceIn(80f, 150f)
        val gap = bh * 0.25f
        var y = h * 0.37f
        val labels = arrayOf("ИГРАТЬ", "МУЛЬТИПЛЕЕР", "НАСТРОЙКИ")
        for (i in 0..2) {
            menuBtns[i].set(w / 2f - bw / 2f, y, w / 2f + bw / 2f, y + bh)
            if (i == 0) pixelButton(c, menuBtns[i], labels[i], bh * 0.42f,
                Color.rgb(70, 200, 90), Color.rgb(140, 255, 150), Color.rgb(30, 110, 50))
            else pixelButton(c, menuBtns[i], labels[i], bh * 0.40f,
                Color.rgb(90, 110, 200), Color.rgb(150, 170, 255), Color.rgb(45, 55, 120))
            y += bh + gap
        }
    }

    private fun drawStub(c: Canvas) {
        drawBackground(c)
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, overlay)
        drawTitle(c, stubTitle, h * 0.28f, h * 0.10f)
        textP.textSize = h * 0.06f
        textP.color = Color.WHITE
        c.drawText("Здесь пока пусто", w / 2f, h * 0.50f, textP)
        val bw = minOf(w * 0.4f, 520f)
        val bh = (h * 0.12f).coerceIn(80f, 140f)
        backBtn.set(w / 2f - bw / 2f, h * 0.62f, w / 2f + bw / 2f, h * 0.62f + bh)
        pixelButton(c, backBtn, "НАЗАД", bh * 0.42f,
            Color.rgb(90, 110, 200), Color.rgb(150, 170, 255), Color.rgb(45, 55, 120))
    }

    private fun ring(c: Canvas, p: Paint, cx: Float, cy: Float, rx: Float, ry: Float) {
        p.strokeWidth = 3f / zoom
        dst.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawOval(dst, p)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, ratio: Float) {
        c.drawRect(x - 1f, y - 1f, x + w + 1f, y + 3f, barBg)
        barFg.color = when {
            ratio > 0.5f -> Color.rgb(80, 220, 80)
            ratio > 0.25f -> Color.rgb(240, 200, 60)
            else -> Color.rgb(230, 60, 60)
        }
        c.drawRect(x, y, x + w * ratio.coerceIn(0f, 1f), y + 2f, barFg)
    }

    private fun drawGame(c: Canvas) {
        c.drawColor(Color.BLACK)
        val z = zoom
        val cx = camX.roundToInt()
        val cy = camY.roundToInt()
        c.save()
        c.translate(-cx.toFloat(), -cy.toFloat())
        c.scale(z, z)

        val x0 = (cx / z / tile).toInt().coerceAtLeast(0)
        val y0 = (cy / z / tile).toInt().coerceAtLeast(0)
        val x1 = ((cx + width) / z / tile).toInt().coerceAtMost(mapW - 1)
        val y1 = ((cy + height) / z / tile).toInt().coerceAtMost(mapH - 1)

        for (ty in y0..y1) for (tx in x0..x1) {
            val bmp = when (val g = ground[ty][tx]) {
                3 -> water
                4 -> dirt
                else -> grass[g]
            }
            c.drawBitmap(bmp, (tx * tile).toFloat(), (ty * tile).toFloat(), paint)
        }
        for (ty in y0..y1) for (tx in x0..x1) {
            if (trees[ty][tx]) c.drawBitmap(tree, (tx * tile).toFloat(), (ty * tile).toFloat(), paint)
        }
        for (i in 0..1) c.drawBitmap(mine, mineXs[i], mineYs[i], paint)
        c.drawBitmap(castleBlue, castles[0].x, castles[0].y, paint)
        c.drawBitmap(castleRed, castles[1].x, castles[1].y, paint)
        if (menuOpen) ring(c, tgtPaint, castles[0].x + 16f, castles[0].y + 28f, 18f, 6f)

        for (u in units) {
            if (u.selected && u.moving && u.job == 0 && u.tgtUnit == null) {
                ring(c, tgtPaint, u.goalX + 8f, u.goalY + 14f, 3f, 1.5f)
            }
        }
        for (u in units.sortedBy { it.y }) {
            val sx = u.x.roundToInt().toFloat()
            val sy = u.y.roundToInt().toFloat()
            if (u.selected) ring(c, selPaint, sx + 8f, sy + 14f, 7f, 3f)
            val bmp = if (u.kind == 1) (if (u.team == 0) workBlue else workRed)
                      else (if (u.team == 0) footBlue else footRed)
            c.drawBitmap(bmp, sx, sy, paint)
            if (u.carrying) c.drawRect(sx + 6f, sy - 2f, sx + 10f, sy + 2f, goldDot)
        }
        for (u in units) {
            if (u.hitTimer > 0f && u.hp > 0) {
                bar(c, u.x.roundToInt() + 2f, u.y.roundToInt() - 4f, 12f, u.hp.toFloat() / u.maxHp)
            }
        }
        for (cs in castles) {
            if (cs.hitTimer > 0f && cs.hp > 0) {
                bar(c, cs.x + 2f, cs.y - 6f, 28f, cs.hp.toFloat() / cs.maxHp)
            }
        }
        c.restore()
        drawHud(c)
    }

    private fun sliderTop() = 170f
    private fun sliderBot() = height - 150f

    private fun drawHud(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // золото
        c.drawRect(0f, 0f, 330f, 70f, hudBg)
        c.drawCircle(36f, 35f, 14f, goldDot)
        hudText.textAlign = Paint.Align.LEFT
        c.drawText("${gold[0]}", 66f, 49f, hudText)
        val selN = units.count { it.selected }
        if (selN > 0) {
            c.drawRect(0f, 76f, 330f, 134f, hudBg)
            hudText.textSize = 32f
            c.drawText("Выбрано: $selN", 20f, 118f, hudText)
            hudText.textSize = 40f
        }

        // кнопки справа
        val bw = 250f
        val bh = 76f
        val left = w - 24f - bw
        for (i in 0..2) btnHud[i].set(left, 24f + i * (bh + 14f), left + bw, 24f + i * (bh + 14f) + bh)
        pixelButton(c, btnHud[0], "Меню", 32f,
            Color.rgb(110, 120, 160), Color.rgb(170, 180, 220), Color.rgb(60, 66, 100))
        pixelButton(c, btnHud[1], "Снять выделение", 26f,
            Color.rgb(200, 80, 80), Color.rgb(255, 140, 140), Color.rgb(120, 40, 40))
        if (rectMode) pixelButton(c, btnHud[2], "Рамка: ВКЛ", 30f,
            Color.rgb(80, 200, 100), Color.rgb(150, 255, 160), Color.rgb(40, 110, 55))
        else pixelButton(c, btnHud[2], "Рамка", 32f,
            Color.rgb(80, 110, 200), Color.rgb(140, 170, 255), Color.rgb(40, 55, 120))

        // ползунок зума
        val top = sliderTop()
        val bot = sliderBot()
        fill.color = Color.argb(170, 20, 20, 30)
        c.drawRect(12f, top - 70f, 100f, bot + 80f, fill)
        fill.color = Color.rgb(18, 12, 30)
        c.drawRect(46f, top, 66f, bot, fill)
        val t = (ln(zoom / minZoom) / ln(maxZoom / minZoom)).coerceIn(0f, 1f)
        val ky = bot - t * (bot - top)
        fill.color = Color.rgb(80, 110, 200)
        c.drawRect(50f, ky, 62f, bot, fill)
        fill.color = Color.rgb(18, 12, 30)
        c.drawRect(22f, ky - 24f, 90f, ky + 24f, fill)
        fill.color = Color.rgb(150, 170, 255)
        c.drawRect(26f, ky - 20f, 86f, ky + 20f, fill)
        fill.color = Color.rgb(90, 110, 200)
        c.drawRect(26f, ky, 86f, ky + 20f, fill)
        textP.textSize = 44f
        textP.color = Color.WHITE
        c.drawText("+", 56f, top - 22f, textP)
        c.drawText("-", 56f, bot + 56f, textP)

        // рамка выделения
        if (selecting) {
            val l = minOf(downX, lastX)
            val r = maxOf(downX, lastX)
            val tt = minOf(downY, lastY)
            val bb = maxOf(downY, lastY)
            fill.color = Color.argb(60, 80, 255, 80)
            c.drawRect(l, tt, r, bb, fill)
            selPaint.strokeWidth = 4f
            c.drawRect(l, tt, r, bb, selPaint)
        }

        if (msgTime > 0f) {
            textP.textSize = 40f
            textP.color = Color.WHITE
            c.drawText(msg, w / 2f, 110f, textP)
        }
        if (menuOpen) {
            val cbw = 330f
            val cbh = 110f
            val ctop = h - cbh - 24f
            val cleft = w / 2f - cbw - 12f
            btnWorker.set(cleft, ctop, cleft + cbw, ctop + cbh)
            btnSoldier.set(cleft + cbw + 24f, ctop, cleft + cbw * 2 + 24f, ctop + cbh)
            c.drawRoundRect(btnWorker, 16f, 16f, if (gold[0] >= 50) btnPaint else btnOff)
            c.drawRoundRect(btnSoldier, 16f, 16f, if (gold[0] >= 80) btnPaint else btnOff)
            c.drawText("Рабочий", btnWorker.centerX(), ctop + 46f, btnText)
            c.drawText("50 золота", btnWorker.centerX(), ctop + 90f, btnText)
            c.drawText("Солдат", btnSoldier.centerX(), ctop + 46f, btnText)
            c.drawText("80 золота", btnSoldier.centerX(), ctop + 90f, btnText)
        }
        if (state != 0) {
            c.drawRect(0f, 0f, w, h, overlay)
            bigText.color = if (state == 1) Color.rgb(100, 255, 100) else Color.rgb(255, 90, 90)
            c.drawText(if (state == 1) "ПОБЕДА!" else "ПОРАЖЕНИЕ", w / 2f, h / 2f, bigText)
            btnText.textSize = 40f
            c.drawText("Тапни, чтобы выйти в меню", w / 2f, h / 2f + 80f, btnText)
            btnText.textSize = 34f
        }
    }

    // ================= УПРАВЛЕНИЕ =================

    private fun clampCamera() {
        val maxX = (mapW * tile * zoom - width).coerceAtLeast(0f)
        val maxY = (mapH * tile * zoom - height).coerceAtLeast(0f)
        camX = camX.coerceIn(0f, maxX)
        camY = camY.coerceIn(0f, maxY)
    }

    private fun setZoom(nz: Float) {
        val old = zoom
        val z = nz.coerceIn(minZoom, maxZoom)
        val cxw = (camX + width / 2f) / old
        val cyw = (camY + height / 2f) / old
        zoom = z
        camX = cxw * z - width / 2f
        camY = cyw * z - height / 2f
        clampCamera()
    }

    private fun onSlider(x: Float, y: Float): Boolean =
        x < 110f && y >= sliderTop() - 70f && y <= sliderBot() + 80f

    private fun setSliderFromY(y: Float) {
        val top = sliderTop()
        val bot = sliderBot()
        val t = ((bot - y) / (bot - top)).coerceIn(0f, 1f)
        setZoom(minZoom * (maxZoom / minZoom).pow(t))
    }

    private fun hudButtonAt(x: Float, y: Float): Int {
        for (i in 0..2) if (btnHud[i].contains(x, y)) return i
        return -1
    }

    private fun say(t: String) { msg = t; msgTime = 2f }

    private fun hudAction(b: Int) {
        when (b) {
            0 -> screenState = 0
            1 -> {
                for (u in units) u.selected = false
                menuOpen = false
                rectMode = false
            }
            2 -> rectMode = !rectMode
        }
    }

    private fun selectRect(ax: Float, ay: Float, bx: Float, by: Float) {
        val l = minOf(ax, bx)
        val r = maxOf(ax, bx)
        val t = minOf(ay, by)
        val b = maxOf(ay, by)
        for (u in units) {
            if (u.team != 0) continue
            val cx = (u.x + 8f) * zoom - camX
            val cy = (u.y + 8f) * zoom - camY
            u.selected = cx >= l && cx <= r && cy >= t && cy <= b
        }
        rectMode = false
        menuOpen = false
    }

    private fun handleTap(sx: Float, sy: Float, wx: Float, wy: Float) {
        if (menuOpen) {
            if (btnWorker.contains(sx, sy)) { if (!train(0, 1, 50)) say("Не хватает золота"); return }
            if (btnSoldier.contains(sx, sy)) { if (!train(0, 0, 80)) say("Не хватает золота"); return }
        }
        val own = units.lastOrNull {
            it.team == 0 && wx >= it.x && wx <= it.x + 16 && wy >= it.y && wy <= it.y + 16
        }
        if (own != null) {
            own.selected = !own.selected
            menuOpen = false
            return
        }
        val sel = units.filter { it.selected }

        val foe = units.lastOrNull {
            it.team == 1 && wx >= it.x - 3 && wx <= it.x + 19 && wy >= it.y - 3 && wy <= it.y + 19
        }
        if (foe != null) {
            for (u in sel) if (u.kind == 0) {
                u.tgtUnit = foe; u.autoT = false; u.tgtCastle = -1; u.job = 0
                u.repath = 0f
            }
            return
        }
        val ec = castles[1]
        if (wx >= ec.x && wx <= ec.x + 32 && wy >= ec.y && wy <= ec.y + 32) {
            for (u in sel) if (u.kind == 0) {
                u.tgtCastle = 1; u.tgtUnit = null; u.job = 0
                u.repath = 0f
            }
            return
        }
        val mc = castles[0]
    if (wx >= mc.x && wx <= mc.x + 32 && wy >= mc.y && wy <= mc.y + 32) {
            menuOpen = !menuOpen
            return
        }
        menuOpen = false
        if (wx >= mineXs[0] && wx <= mineXs[0] + 32 && wy >= mineYs[0] && wy <= mineYs[0] + 32) {
            var k = 0
            for (u in sel) if (u.kind == 1) {
                u.job = 1; u.carrying = false
                u.tgtUnit = null; u.tgtCastle = -1
                moveTo(u, mineXs[0] + 8f + k * 3f, mineYs[0] + 24f)
                k++
            }
            if (k > 0) return
        }
        for ((i, u) in sel.withIndex()) {
            val col = i % 4
            val row = i / 4
            u.job = 0; u.carrying = false
            u.tgtUnit = null; u.tgtCastle = -1
            moveTo(u, wx - 8f + (col - 1.5f) * 18f, wy - 14f + row * 18f)
        }
    }

    private fun menuTap(x: Float, y: Float) {
        when {
            menuBtns[0].contains(x, y) -> startGame()
            menuBtns[1].contains(x, y) -> { stubTitle = "МУЛЬТИПЛЕЕР"; screenState = 2 }
            menuBtns[2].contains(x, y) -> { stubTitle = "НАСТРОЙКИ"; screenState = 2 }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                camVx = 0f; camVy = 0f
                lastX = e.x; lastY = e.y; lastT = e.eventTime
                downX = e.x; downY = e.y
                velX = 0f; velY = 0f
                dragging = false
                selecting = false
                touchMode = 0
                pressedBtn = -1
                if (screenState == 1 && state == 0) {
                    if (onSlider(e.x, e.y)) {
                        touchMode = 1
                        setSliderFromY(e.y)
                    } else {
                        val b = hudButtonAt(e.x, e.y)
                        if (b >= 0) { touchMode = 2; pressedBtn = b }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (screenState != 1 || state != 0) return true
                if (touchMode == 1) {
                    setSliderFromY(e.y)
                } else if (touchMode == 0) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > 25f) dragging = true
                    if (dragging) {
                        if (rectMode) {
                            selecting = true
                        } else {
                            val dx = e.x - lastX
                            val dy = e.y - lastY
                            camX -= dx
                            camY -= dy
                            clampCamera()
                            val dt = (e.eventTime - lastT) / 1000f
                            if (dt > 0f) {
                                velX = velX * 0.6f + (dx / dt) * 0.4f
                                velY = velY * 0.6f + (dy / dt) * 0.4f
                            }
                        }
                    }
                }
                lastX = e.x; lastY = e.y; lastT = e.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val x = e.x
                val y = e.y
                when (screenState) {
                    0 -> actions.add { menuTap(x, y) }
                    2 -> actions.add { if (backBtn.contains(x, y)) screenState = 0 }
                    else -> {
                        if (state != 0) {
                            actions.add { screenState = 0 }
                        } else if (touchMode == 2) {
                            val b = pressedBtn
                            if (hudButtonAt(x, y) == b && b >= 0) actions.add { hudAction(b) }
                        } else if (touchMode == 0) {
                            if (!dragging) {
                                val wx = (x + camX) / zoom
                                val wy = (y + camY) / zoom
                                actions.add { handleTap(x, y, wx, wy) }
                            } else if (selecting) {
                                val ax = downX
                                val ay = downY
                                selecting = false
                                actions.add { selectRect(ax, ay, x, y) }
                            } else {
                                val fresh = (e.eventTime - lastT) < 80
                                if (fresh && hypot(velX, velY) > 300f) {
                                    camVx = -velX.coerceIn(-4000f, 4000f)
                                    camVy = -velY.coerceIn(-4000f, 4000f)
                                }
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                selecting = false
            }
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        minZoom = maxOf(w.toFloat() / (mapW * tile), h.toFloat() / (mapH * tile))
        zoom = zoom.coerceIn(minZoom, maxZoom)
        clampCamera()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
