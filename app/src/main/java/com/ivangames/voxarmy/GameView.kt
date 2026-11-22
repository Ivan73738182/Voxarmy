package com.ivangames.voxarmy

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

class Soldier(var x: Float, var y: Float, val team: Int) {
    var tx = x
    var ty = y
    var selected = false
    var moving = false
}

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile private var running = false
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val selPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(80, 255, 80)
    }
    private val tgtPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(255, 230, 80)
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

    private val mapW = 48
    private val mapH = 48
    private val tile = 16
    private val scale = 6f
    private val speed = 40f
    private val ground = Array(mapH) { IntArray(mapW) }
    private val trees = Array(mapH) { BooleanArray(mapW) }
    private val soldiers = CopyOnWriteArrayList<Soldier>()

    private var camX = 3f * tile * scale
    private var camY = 3f * tile * scale
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val dst = RectF()

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
        for (y in 4..12) for (x in 4..12) trees[y][x] = false
        for (y in 34..44) for (x in 34..44) trees[y][x] = false
        for (i in 0..3) {
            soldiers.add(Soldier((9 + i) * tile.toFloat(), 9f * tile, 0))
            soldiers.add(Soldier((36 - i) * tile.toFloat(), 38f * tile, 1))
        }
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
            val c = h.lockCanvas() ?: continue
            try { drawFrame(c) } finally { h.unlockCanvasAndPost(c) }
            Thread.sleep(8)
        }
    }

    private fun update(dt: Float) {
        for (s in soldiers) {
            if (!s.moving) continue
            val dx = s.tx - s.x
            val dy = s.ty - s.y
            val d = hypot(dx, dy)
            val step = speed * dt
            if (d <= step) {
                s.x = s.tx; s.y = s.ty; s.moving = false
            } else {
                s.x += dx / d * step
                s.y += dy / d * step
            }
        }
    }

    private fun drawBmp(c: Canvas, b: Bitmap, wx: Float, wy: Float) {
        val l = wx * scale - camX
        val t = wy * scale - camY
        dst.set(l, t, l + b.width * scale, t + b.height * scale)
        c.drawBitmap(b, null, dst, paint)
    }

    private fun ring(c: Canvas, p: Paint, wx: Float, wy: Float, rx: Float, ry: Float) {
        val cx = wx * scale - camX
        val cy = wy * scale - camY
        dst.set(cx - rx * scale, cy - ry * scale, cx + rx * scale, cy + ry * scale)
        c.drawOval(dst, p)
    }

    private fun drawFrame(c: Canvas) {
        c.drawColor(Color.BLACK)
        val ts = tile * scale
        val x0 = (camX / ts).toInt().coerceAtLeast(0)
        val y0 = (camY / ts).toInt().coerceAtLeast(0)
        val x1 = ((camX + width) / ts).toInt().coerceAtMost(mapW - 1)
        val y1 = ((camY + height) / ts).toInt().coerceAtMost(mapH - 1)
        for (ty in y0..y1) for (tx in x0..x1) {
            val bmp = when (val g = ground[ty][tx]) {
                3 -> water
                4 -> dirt
                else -> grass[g]
            }
            drawBmp(c, bmp, tx * tile.toFloat(), ty * tile.toFloat())
        }
        for (ty in y0..y1) for (tx in x0..x1) {
            if (trees[ty][tx]) drawBmp(c, tree, tx * tile.toFloat(), ty * tile.toFloat())
        }
        drawBmp(c, castleBlue, 6f * tile, 6f * tile)
        drawBmp(c, castleRed, 40f * tile, 40f * tile)
        for (s in soldiers) {
            if (s.selected && s.moving) ring(c, tgtPaint, s.tx + 8, s.ty + 14, 3f, 1.5f)
        }
        for (s in soldiers.sortedBy { it.y }) {
            if (s.selected) ring(c, selPaint, s.x + 8, s.y + 14, 7f, 3f)
            drawBmp(c, if (s.team == 0) footBlue else footRed, s.x, s.y)
        }
    }

    private fun clampCamera() {
        val maxX = (mapW * tile * scale - width).coerceAtLeast(0f)
        val maxY = (mapH * tile * scale - height).coerceAtLeast(0f)
        camX = camX.coerceIn(0f, maxX)
        camY = camY.coerceIn(0f, maxY)
    }

    private fun handleTap(sx: Float, sy: Float) {
        val wx = (sx + camX) / scale
        val wy = (sy + camY) / scale
        val hit = soldiers.lastOrNull {
            it.team == 0 && wx >= it.x && wx <= it.x + 16 && wy >= it.y && wy <= it.y + 16
        }
        if (hit != null) {
            hit.selected = !hit.selected
            return
        }
        val sel = soldiers.filter { it.selected }
        val maxW = mapW * tile - 16f
        val maxH = mapH * tile - 16f
        for ((i, s) in sel.withIndex()) {
            val col = i % 4
            val row = i / 4
            s.tx = (wx - 8f + (col - 1.5f) * 18f).coerceIn(0f, maxW)
            s.ty = (wy - 14f + row * 18f).coerceIn(0f, maxH)
            s.moving = true
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > 25f) dragging = true
                if (dragging) {
                    camX -= e.x - lastX
                    camY -= e.y - lastY
                    clampCamera()
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) handleTap(e.x, e.y)
            }
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { clampCamera() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
