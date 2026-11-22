package com.ivangames.voxarmy

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile private var running = false
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

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
    private val ground = Array(mapH) { IntArray(mapW) }
    private val trees = Array(mapH) { BooleanArray(mapW) }

    private var camX = 3f * tile * scale
    private var camY = 3f * tile * scale
    private var lastX = 0f
    private var lastY = 0f
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
        while (running) {
            val h = holder
            if (!h.surface.isValid) { Thread.sleep(10); continue }
            val c = h.lockCanvas() ?: continue
            try { drawFrame(c) } finally { h.unlockCanvasAndPost(c) }
            Thread.sleep(16)
        }
    }

    private fun drawBmp(c: Canvas, b: Bitmap, wx: Float, wy: Float) {
        val l = wx * scale - camX
        val t = wy * scale - camY
        dst.set(l, t, l + b.width * scale, t + b.height * scale)
        c.drawBitmap(b, null, dst, paint)
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
        for (i in 0..3) {
            drawBmp(c, footBlue, (9 + i) * tile.toFloat(), 9f * tile)
            drawBmp(c, footRed, (36 - i) * tile.toFloat(), 38f * tile)
        }
    }

    private fun clampCamera() {
        val maxX = (mapW * tile * scale - width).coerceAtLeast(0f)
        val maxY = (mapH * tile * scale - height).coerceAtLeast(0f)
        camX = camX.coerceIn(0f, maxX)
        camY = camY.coerceIn(0f, maxY)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                camX -= e.x - lastX
                camY -= e.y - lastY
                lastX = e.x
                lastY = e.y
                clampCamera()
            }
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { clampCamera() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
