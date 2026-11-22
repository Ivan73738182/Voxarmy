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
    // состояния рабочего: 0 = свободен, 1 = идёт к шахте, 2 = копает, 3 = несёт в замок
    var job = 0
    var timer = 0f
    var carrying = false
}

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile private var running = false
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val selPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(80, 255, 80) }
    private val tgtPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(255, 230, 80) }
    private val goldDot = Paint().apply { color = Color.rgb(250, 215, 70) }
    private val hudBg = Paint().apply { color = Color.argb(170, 20, 20, 30) }
    private val hudText = Paint().apply {
        color = Color.WHITE; textSize = 40f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
    }
    private val btnPaint = Paint().apply { color = Color.rgb(60, 70, 110) }
    private val btnOff = Paint().apply { color = Color.rgb(70, 70, 70) }
    private val btnText = Paint().apply {
        color = Color.WHITE; textSize = 34f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

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

    // постройки (левый верхний угол в мировых пикселях)
    private val castleX = 6f * tile
    private val castleY = 6f * tile
    private val mineX = 15f * tile
    private val mineY = 6f * tile
    private val castleRX = 40f * tile
    private val castleRY = 40f * tile

    @Volatile private var gold = 150
    private var menuOpen = false
    private var msg = ""
    private var msgTime = 0f

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

    // кнопки меню (экранные координаты, считаются в drawFrame)
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
        for (y in 34..44) for (x in 34..44) trees[y][x] = false
        for (i in 0..2) units.add(Unit2((9 + i) * tile.toFloat(), 10f * tile, 0, 0))
        for (i in 0..1) units.add(Unit2((9 + i) * tile.toFloat(), 12f * tile, 0, 1))
        for (i in 0..3) units.add(Unit2((36 - i) * tile.toFloat(), 38f * tile, 1, 0))
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
        for (u in units) {
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
            if (u.kind == 1 && u.team == 0) updateWorker(u, dt)
        }
    }

    private fun updateWorker(u: Unit2, dt: Float) {
        when (u.job) {
            1 -> if (!u.moving) { u.job = 2; u.timer = 3f }
            2 -> {
                u.timer -= dt
                if (u.timer <= 0f) {
                    u.carrying = true
                    u.job = 3
                    moveTo(u, castleX + 8f, castleY + 24f)
                }
            }
            3 -> if (!u.moving) {
                gold += 10
                u.carrying = false
                u.job = 1
                moveTo(u, mineX + 8f, mineY + 24f)
            }
        }
    }

    private fun ring(c: Canvas, p: Paint, cx: Float, cy: Float, rx: Float, ry: Float) {
        p.strokeWidth = 3f / zoom
        dst.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawOval(dst, p)
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
        c.drawBitmap(mine, mineX, mineY, paint)
        c.drawBitmap(castleBlue, castleX, castleY, paint)
        c.drawBitmap(castleRed, castleRX, castleRY, paint)
        if (menuOpen) ring(c, tgtPaint, castleX + 16, castleY + 28, 18f, 6f)

        for (u in units) {
            if (u.selected && u.moving && u.job == 0) ring(c, tgtPaint, u.tx + 8, u.ty + 14, 3f, 1.5f)
        }
        for (u in units.sortedBy { it.y }) {
            val sx = u.x.roundToInt().toFloat()
            val sy = u.y.roundToInt().toFloat()
            if (u.selected) ring(c, selPaint, sx + 8, sy + 14, 7f, 3f)
            val bmp = if (u.kind == 1) (if (u.team == 0) workBlue else workRed)
                      else (if (u.team == 0) footBlue else footRed)
            c.drawBitmap(bmp, sx, sy, paint)
            if (u.carrying) c.drawRect(sx + 6, sy - 2, sx + 10, sy + 2, goldDot)
        }
        c.restore()
        drawHud(c)
    }

    private fun drawHud(c: Canvas) {
        c.drawRect(0f, 0f, 330f, 70f, hudBg)
        c.drawCircle(36f, 35f, 14f, goldDot)
        c.drawText("$gold", 66f, 49f, hudText)
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
            c.drawRoundRect(btnWorker, 16f, 16f, if (gold >= 50) btnPaint else btnOff)
            c.drawRoundRect(btnSoldier, 16f, 16f, if (gold >= 80) btnPaint else btnOff)
            c.drawText("Рабочий", btnWorker.centerX(), top + 46f, btnText)
            c.drawText("50 золота", btnWorker.centerX(), top + 90f, btnText)
            c.drawText("Солдат", btnSoldier.centerX(), top + 46f, btnText)
            c.drawText("80 золота", btnSoldier.centerX(), top + 90f, btnText)
        }
    }

    private fun clampCamera() {
        val maxX = (mapW * tile * zoom - width).coerceAtLeast(0f)
        val maxY = (mapH * tile * zoom - height).coerceAtLeast(0f)
        camX = camX.coerceIn(0f, maxX)
        camY = camY.coerceIn(0f, maxY)
    }

    private fun say(t: String) { msg = t; msgTime = 2f }

    private fun train(kind: Int, cost: Int) {
        if (gold < cost) { say("Не хватает золота"); return }
        gold -= cost
        val n = units.count { it.team == 0 }
        val u = Unit2(castleX + 8f + (n % 5) * 4f, castleY + 34f, 0, kind)
        units.add(u)
        moveTo(u, castleX + 4f + (n % 6) * 12f, castleY + 50f + ((n / 6) % 3) * 16f)
    }

    private fun handleTap(sx: Float, sy: Float) {
        if (menuOpen) {
            if (btnWorker.contains(sx, sy)) { train(1, 50); return }
            if (btnSoldier.contains(sx, sy)) { train(0, 80); return }
        }
        val wx = (sx + camX) / zoom
        val wy = (sy + camY) / zoom

        // тап по юниту
        val hit = units.lastOrNull {
            it.team == 0 && wx >= it.x && wx <= it.x + 16 && wy >= it.y && wy <= it.y + 16
        }
        if (hit != null) {
            hit.selected = !hit.selected
            if (hit.selected) { hit.job = 0 }
            menuOpen = false
            return
        }
        // тап по замку
        if (wx >= castleX && wx <= castleX + 32 && wy >= castleY && wy <= castleY + 32) {
            menuOpen = !menuOpen
            return
        }
        menuOpen = false
        val sel = units.filter { it.selected }
        // тап по шахте: выделенные рабочие идут копать
        if (wx >= mineX && wx <= mineX + 32 && wy >= mineY && wy <= mineY + 32) {
            var any = false
            for (u in sel) if (u.kind == 1) {
                u.job = 1; u.carrying = false
                moveTo(u, mineX + 8f + (any.compareTo(false) * 0) , mineY + 24f)
                any = true
            }
            if (any) return
        }
        val maxW = mapW * tile - 16f
        val maxH = mapH * tile - 16f
        for ((i, u) in sel.withIndex()) {
            val col = i % 4
            val row = i / 4
            u.job = 0; u.carrying = false
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
