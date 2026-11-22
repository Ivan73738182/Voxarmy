package com.ivangames.voxarmy

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

// kind: 0 = солдат, 1 = рабочий
class Unit2(var x: Float, var y: Float, val team: Int, val kind: Int) {
    var tx = x
    var ty = y
    var selected = false
    var moving = false
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
}

class Castle(val x: Float, val y: Float, val team: Int) {
    val maxHp = 500
    var hp = 500
    var hitTimer = 0f
}

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile private var running = false
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
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
    private val overlay = Paint().apply { color = Color.argb(160, 0, 0, 0) }

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
    private val workBlue = load("worker_blue.png")
    private val workRed = load("worker_red.png")
    private val mine = load("goldmine.png")

    private val mapW = 48
    private val mapH = 48
    private val tile = 16
    private val speed = 40f
    private val ground = Array(mapH) { IntArray(mapW) }
    private val trees = Array(mapH) { BooleanArray(mapW) }
    private val units = CopyOnWriteArrayList<Unit2>()

    private val castles = arrayOf(
        Castle(6f * tile, 6f * tile, 0),
        Castle(40f * tile, 40f * tile, 1)
    )
    private val mineXs = floatArrayOf(15f * tile, 31f * tile)
    private val mineYs = floatArrayOf(6f * tile, 40f * tile)

    private val gold = intArrayOf(150, 150)
    @Volatile private var state = 0 // 0 игра, 1 победа, 2 поражение
    private var menuOpen = false
    private var msg = ""
    private var msgTime = 0f
    private var aiTimer = 0f
    private var waveSize = 5

    @Volatile private var zoom = 6f
    private var minZoom = 2f
    private val maxZoom = 16f
    @Volatile private var camX = 3f * tile * 6f
    @Volatile private var camY = 3f * tile * 6f
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
    private val dst = RectF()
    private val btnWorker = RectF()
    private val btnSoldier = RectF()

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                camVx = 0f; camVy = 0f
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val old = zoom
                val nz = (old * d.scaleFactor).coerceIn(minZoom, maxZoom)
                val fx = d.focusX
                val fy = d.focusY
                camX = (fx + camX) / old * nz - fx
                camY = (fy + camY) / old * nz - fy
                zoom = nz
                clampCamera()
                return true
            }
        })

    init {
        holder.addCallback(this)
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
        newGame()
    }

    private fun newGame() {
        units.clear()
        gold[0] = 150
        gold[1] = 150
        for (c in castles) { c.hp = c.maxHp; c.hitTimer = 0f }
        waveSize = 5
        aiTimer = 0f
        menuOpen = false
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

    private fun moveTo(u: Unit2, x: Float, y: Float) {
        u.tx = x; u.ty = y; u.moving = true
    }

    private fun update(dt: Float) {
        if (msgTime > 0f) msgTime -= dt
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
                val dx = u.tx - u.x
                val dy = u.ty - u.y
                val d = hypot(dx, dy)
                val step = speed * dt
                if (d <= step) {
                    u.x = u.tx; u.y = u.ty; u.moving = false
                } else {
                    u.x += dx / d * step
                    u.y += dy / d * step
                }
            }
            if (u.kind == 1) updateWorker(u, dt) else updateSoldier(u, dt)
        }
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

    private fun updateSoldier(u: Unit2, dt: Float) {
        u.cd -= dt
        var t = u.tgtUnit
        if (t != null && t.hp <= 0) { t = null; u.tgtUnit = null }
        if (t != null && u.autoT && hypot(t.x - u.x, t.y - u.y) > 140f) { t = null; u.tgtUnit = null }
        if (t == null && (!u.moving || u.tgtCastle >= 0)) {
            t = nearestEnemy(u, 50f)
            if (t != null) { u.tgtUnit = t; u.autoT = true }
        }
        if (t != null) {
            val d = hypot(t.x - u.x, t.y - u.y)
            if (d <= 16f) {
                u.moving = false
                if (u.cd <= 0f) {
                    t.hp -= 8
                    t.hitTimer = 3f
                    u.hitTimer = 3f
                    u.cd = 0.8f
                }
            } else {
                moveTo(u, t.x, t.y)
            }
            return
        }
        if (u.tgtCastle < 0 && !u.moving) {
            val ec = castles[1 - u.team]
            if (hypot(ec.x + 16f - u.x - 8f, ec.y + 16f - u.y - 8f) <= 40f) u.tgtCastle = ec.team
        }
        if (u.tgtCastle >= 0) {
            val c = castles[u.tgtCastle]
            if (c.hp <= 0) { u.tgtCastle = -1; return }
            val d = hypot(c.x + 16f - u.x - 8f, c.y + 16f - u.y - 8f)
            if (d <= 28f) {
                u.moving = false
                if (u.cd <= 0f) {
                    c.hp -= 8
                    c.hitTimer = 3f
                    u.hitTimer = 3f
                    u.cd = 0.8f
                    if (c.hp <= 0) state = if (c.team == 1) 1 else 2
                }
            } else {
                moveTo(u, c.x + 8f, c.y + 34f)
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

    private fun drawFrame(c: Canvas) {
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
                ring(c, tgtPaint, u.tx + 8f, u.ty + 14f, 3f, 1.5f)
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
        // полоски жизни: только у тех, кто дерётся
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

    private fun drawHud(c: Canvas) {
        c.drawRect(0f, 0f, 330f, 70f, hudBg)
        c.drawCircle(36f, 35f, 14f, goldDot)
        c.drawText("${gold[0]}", 66f, 49f, hudText)
        if (msgTime > 0f) {
            hudText.textAlign = Paint.Align.CENTER
            c.drawText(msg, width / 2f, 110f, hudText)
            hudText.textAlign = Paint.Align.LEFT
        }
        if (menuOpen) {
            val bw = 330f
            val bh = 110f
            val top = height - bh - 24f
            val left = width / 2f - bw - 12f
            btnWorker.set(left, top, left + bw, top + bh)
            btnSoldier.set(left + bw + 24f, top, left + bw * 2 + 24f, top + bh)
            c.drawRoundRect(btnWorker, 16f, 16f, if (gold[0] >= 50) btnPaint else btnOff)
            c.drawRoundRect(btnSoldier, 16f, 16f, if (gold[0] >= 80) btnPaint else btnOff)
            c.drawText("Рабочий", btnWorker.centerX(), top + 46f, btnText)
            c.drawText("50 золота", btnWorker.centerX(), top + 90f, btnText)
            c.drawText("Солдат", btnSoldier.centerX(), top + 46f, btnText)
            c.drawText("80 золота", btnSoldier.centerX(), top + 90f, btnText)
        }
        if (state != 0) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
            bigText.color = if (state == 1) Color.rgb(100, 255, 100) else Color.rgb(255, 90, 90)
            c.drawText(if (state == 1) "ПОБЕДА!" else "ПОРАЖЕНИЕ", width / 2f, height / 2f, bigText)
            btnText.textSize = 40f
            c.drawText("Тапни, чтобы начать заново", width / 2f, height / 2f + 80f, btnText)
            btnText.textSize = 34f
        }
    }

    private fun clampCamera() {
        val maxX = (mapW * tile * zoom - width).coerceAtLeast(0f)
        val maxY = (mapH * tile * zoom - height).coerceAtLeast(0f)
        camX = camX.coerceIn(0f, maxX)
        camY = camY.coerceIn(0f, maxY)
    }

    private fun say(t: String) { msg = t; msgTime = 2f }

    private fun handleTap(sx: Float, sy: Float) {
        if (state != 0) { newGame(); return }
        if (menuOpen) {
            if (btnWorker.contains(sx, sy)) { if (!train(0, 1, 50)) say("Не хватает золота"); return }
            if (btnSoldier.contains(sx, sy)) { if (!train(0, 0, 80)) say("Не хватает золота"); return }
        }
        val wx = (sx + camX) / zoom
        val wy = (sy + camY) / zoom

        // свой юнит: выделить / снять
        val own = units.lastOrNull {
            it.team == 0 && wx >= it.x && wx <= it.x + 16 && wy >= it.y && wy <= it.y + 16
        }
        if (own != null) {
            own.selected = !own.selected
            menuOpen = false
            return
        }
        val sel = units.filter { it.selected }

        // вражеский юнит: атака
        val foe = units.lastOrNull {
            it.team == 1 && wx >= it.x - 3 && wx <= it.x + 19 && wy >= it.y - 3 && wy <= it.y + 19
        }
        if (foe != null) {
            for (u in sel) if (u.kind == 0) {
                u.tgtUnit = foe; u.autoT = false; u.tgtCastle = -1; u.job = 0
            }
            return
        }
        // вражеский замок: атака
        val ec = castles[1]
        if (wx >= ec.x && wx <= ec.x + 32 && wy >= ec.y && wy <= ec.y + 32) {
            for (u in sel) if (u.kind == 0) {
                u.tgtCastle = 1; u.tgtUnit = null; u.job = 0
            }
            return
        }
        // свой замок: меню найма
        val mc = castles[0]
        if (wx >= mc.x && wx <= mc.x + 32 && wy >= mc.y && wy <= mc.y + 32) {
            menuOpen = !menuOpen
            return
        }
        menuOpen = false
        // шахта: выделенные рабочие идут копать
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
        // просто идти
        val maxW = mapW * tile - 16f
        val maxH = mapH * tile - 16f
        for ((i, u) in sel.withIndex()) {
            val col = i % 4
            val row = i / 4
            u.job = 0; u.carrying = false
            u.tgtUnit = null; u.tgtCastle = -1
            moveTo(u,
                (wx - 8f + (col - 1.5f) * 18f).coerceIn(0f, maxW),
                (wy - 14f + row * 18f).coerceIn(0f, maxH))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                camVx = 0f; camVy = 0f
                lastX = e.x; lastY = e.y; lastT = e.eventTime
                downX = e.x; downY = e.y
                velX = 0f; velY = 0f
                dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = true
                camVx = 0f; camVy = 0f
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i); lastY = e.getY(i); lastT = e.eventTime
                velX = 0f; velY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || scaleDetector.isInProgress) {
                    lastX = e.x; lastY = e.y; lastT = e.eventTime
                    return true
                }
                if (!dragging && hypot(e.x - downX, e.y - downY) > 25f) dragging = true
                if (dragging) {
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
                lastX = e.x; lastY = e.y; lastT = e.eventTime
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    handleTap(e.x, e.y)
                } else if (e.pointerCount == 1) {
                    val fresh = (e.eventTime - lastT) < 80
                    if (fresh && hypot(velX, velY) > 300f) {
                        camVx = -velX.coerceIn(-4000f, 4000f)
                        camVy = -velY.coerceIn(-4000f, 4000f)
                    }
                }
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
