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

class Soldier(var x: Float, var y: Float, val team: Int) {
    var tx = x
    var ty = y
    var selected = false
    var moving = false
}

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile private var running = false
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val selPaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.rgb(80, 255, 80); isAntiAlias = false
    }
    private val tgtPaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.rgb(255, 230, 80); isAntiAlias = false
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
    private val speed = 40f
    private val ground = Array(mapH) { IntArray(mapW) }
    private val trees = Array(mapH) { BooleanArray(mapW) }
    private val soldiers = CopyOnWriteArrayList<Soldier>()

    // камера в экранных пикселях, zoom = пикселей экрана на 1 пиксель спрайта
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
            val c = (if (Build.VERSION.SDK_INT >= 26) h.lockHardwareCanvas() else h.lockCanvas())
                ?: continue
            try { drawFrame(c) } finally { h.unlockCanvasAndPost(c) }
        }
    }

    private fun update(dt: Float) {
        // инерция камеры
        if (camVx != 0f || camVy != 0f) {
            camX += camVx * dt
            camY += camVy * dt
            val f = 0.92f.pow(dt * 60f)
            camVx *= f
            camVy *= f
            if (hypot(camVx, camVy) < 15f) { camVx = 0f; camVy = 0f }
            clampCamera()
        }
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
        c.drawBitmap(castleBlue, 6f * tile, 6f * tile, paint)
        c.drawBitmap(castleRed, 40f * tile, 40f * tile, paint)
        for (s in soldiers) {
            if (s.selected && s.moving) ring(c, tgtPaint, s.tx + 8, s.ty + 14, 3f, 1.5f)
        }
        for (s in soldiers.sortedBy { it.y }) {
            val sx = s.x.roundToInt().toFloat()
            val sy = s.y.roundToInt().toFloat()
            if (s.selected) ring(c, selPaint, sx + 8, sy + 14, 7f, 3f)
            c.drawBitmap(if (s.team == 0) footBlue else footRed, sx, sy, paint)
        }
        c.restore()
    }

    private fun clampCamera() {
        val maxX = (mapW * tile * zoom - width).coerceAtLeast(0f)
        val maxY = (mapH * tile * zoom - height).coerceAtLeast(0f)
        camX = camX.coerceIn(0f, maxX)
        camY = camY.coerceIn(0f, maxY)
    }

    private fun handleTap(sx: Float, sy: Float) {
        val wx = (sx + camX) / zoom
        val wy = (sy + camY) / zoom
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
