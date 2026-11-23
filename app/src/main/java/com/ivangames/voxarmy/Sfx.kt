package com.ivangames.voxarmy

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class Sfx(private val ctx: Context) {
    private val rate = 22050
    private val tau = (2.0 * PI).toFloat()
    private var pool: SoundPool? = null
    private val ids = ConcurrentHashMap<String, Int>()
    private val last = HashMap<String, Long>()
    private var music: MediaPlayer? = null
    @Volatile var enabled = true
    @Volatile private var wantMusic = false
    @Volatile private var paused = false

    fun init() {
        thread {
            try { build() } catch (e: Exception) { }
        }
    }

    private fun build() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        val fx = HashMap<String, ShortArray>()
        fx["hit"] = genHit(200f)
        fx["hit2"] = genHit(260f)
        fx["thud"] = genThud()
        fx["die"] = genDie()
        fx["coin"] = seq(floatArrayOf(988f, 1319f), 0.05f, 0.3f)
        fx["spawn"] = seq(floatArrayOf(523f, 659f, 784f), 0.07f, 0.3f)
        fx["click"] = seq(floatArrayOf(700f), 0.04f, 0.3f)
        fx["win"] = seq(floatArrayOf(523f, 659f, 784f, 1047f), 0.14f, 0.3f)
        fx["lose"] = seq(floatArrayOf(392f, 330f, 262f, 196f), 0.16f, 0.3f)
        val dir = ctx.cacheDir
        for ((name, data) in fx) {
            val f = File(dir, "sfx_$name.wav")
            f.writeBytes(wav(data))
            ids[name] = sp.load(f.path, 1)
        }
        pool = sp
        val mf = File(dir, "music.wav")
        mf.writeBytes(wav(genMusic()))
        val mp = MediaPlayer()
        mp.setDataSource(mf.path)
        mp.isLooping = true
        mp.setVolume(0.35f, 0.35f)
        mp.prepare()
        music = mp
        applyMusic()
    }

    fun play(name: String) {
        if (!enabled || paused) return
        val id = ids[name] ?: return
        val now = System.currentTimeMillis()
        val l = last[name] ?: 0L
        if (now - l < 70) return
        last[name] = now
        pool?.play(id, 0.8f, 0.8f, 1, 0, 1f)
    }

    fun startMusic() { wantMusic = true; applyMusic() }
    fun setSound(on: Boolean) { enabled = on; applyMusic() }
    fun onPause() { paused = true; applyMusic() }
    fun onResume() { paused = false; applyMusic() }

    private fun applyMusic() {
        val m = music ?: return
        try {
            if (wantMusic && enabled && !paused) {
                if (!m.isPlaying) m.start()
            } else if (m.isPlaying) {
                m.pause()
            }
        } catch (e: Exception) { }
    }

    // ---------- синтез ----------
    private fun shorts(f: FloatArray): ShortArray =
        ShortArray(f.size) { (f[it].coerceIn(-1f, 1f) * 30000f).toInt().toShort() }

    private fun wav(s: ShortArray): ByteArray {
        val dataLen = s.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + dataLen)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(1.toShort())
        b.putInt(rate)
        b.putInt(rate * 2)
        b.putShort(2.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(dataLen)
        for (v in s) b.putShort(v)
        return b.array()
    }

    private fun genHit(pitch: Float): ShortArray {
        val n = (rate * 0.14f).toInt()
        val r = Random(3)
        val f = FloatArray(n)
        for (i in 0 until n) {
            val t = i / rate.toFloat()
            val env = exp(-t * 32f)
            f[i] = ((r.nextFloat() * 2f - 1f) * 0.7f + sin(tau * pitch * t) * 0.6f) * env
        }
        return shorts(f)
    }

    private fun genThud(): ShortArray {
        val n = (rate * 0.22f).toInt()
        val r = Random(4)
        val f = FloatArray(n)
        for (i in 0 until n) {
            val t = i / rate.toFloat()
            f[i] = sin(tau * 70f * t) * exp(-t * 14f) * 0.9f +
                (r.nextFloat() * 2f - 1f) * 0.3f * exp(-t * 40f)
        }
        return shorts(f)
    }

    private fun genDie(): ShortArray {
        val n = (rate * 0.32f).toInt()
        val f = FloatArray(n)
        var ph = 0f
        for (i in 0 until n) {
            val t = i / rate.toFloat()
            val fr = maxOf(80f, 420f - t * 900f)
            ph += fr / rate
            val sq = if (ph % 1f < 0.5f) 1f else -1f
            f[i] = sq * 0.35f * (1f - i.toFloat() / n)
        }
        return shorts(f)
    }

    private fun seq(freqs: FloatArray, dur: Float, vol: Float): ShortArray {
        val per = (rate * dur).toInt()
        val f = FloatArray(per * freqs.size)
        for ((k, fr) in freqs.withIndex()) {
            for (i in 0 until per) {
                val ph = (fr * i / rate.toFloat()) % 1f
                val env = 1f - i.toFloat() / per
                f[k * per + i] = (if (ph < 0.5f) 1f else -1f) * vol * env
            }
        }
        return shorts(f)
    }

    private fun midi(m: Int): Float = 440f * 2f.pow((m - 69) / 12f)

    private fun note(buf: FloatArray, start: Int, len: Int, freq: Float, vol: Float, wave: Int) {
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val ph = (freq * i / rate.toFloat()) % 1f
            val s = when (wave) {
                0 -> if (ph < 0.5f) 1f else -1f
                1 -> 4f * abs(ph - 0.5f) - 1f
                else -> if (ph < 0.25f) 1f else -1f
            }
            val att = minOf(1f, i / (rate * 0.004f))
            val env = (1f - i.toFloat() / len).pow(1.3f) * att
            buf[idx] += s * vol * env
        }
    }

    private fun genMusic(): ShortArray {
        val beat = 60f / 108f
        val bars = 8
        val beatS = (rate * beat).toInt()
        val total = beatS * 4 * bars
        val buf = FloatArray(total)
        val roots = intArrayOf(57, 53, 48, 55)
        val thirds = intArrayOf(3, 4, 4, 4)
        val lead = intArrayOf(
            69, 72, 76, 72, 69, 71, 72, -1,
            69, 72, 77, 72, 69, 72, 69, -1,
            67, 72, 76, 72, 67, 71, 72, -1,
            67, 71, 74, 71, 71, 67, 71, -1)
        val r = Random(5)
        for (bar in 0 until bars) {
            val ch = (bar / 2) % 4
            val root = roots[ch]
            val third = thirds[ch]
            val tones = intArrayOf(root + 12, root + 12 + third, root + 19)
            for (b in 0 until 4) {
                val s = (bar * 4 + b) * beatS
                note(buf, s, (beatS * 0.9f).toInt(), midi(root - 12), 0.22f, 0)
                val lm = lead[bar * 4 + b]
                if (lm > 0) note(buf, s, (beatS * 0.85f).toInt(), midi(lm), 0.13f, 0)
                for (e in 0 until 2) {
                    val es = s + e * beatS / 2
                    note(buf, es, (beatS * 0.4f).toInt(), midi(tones[(b * 2 + e) % 3]), 0.09f, 2)
                }
                if (b % 2 == 0) {
                    val kn = (rate * 0.16f).toInt()
                    var ph = 0f
                    for (i in 0 until kn) {
                        val idx = s + i
                        if (idx >= total) break
                        ph += (110f - 70f * i / kn) / rate
                        buf[idx] += sin(tau * ph) * 0.35f * (1f - i.toFloat() / kn)
                    }
                }
                val hs = s + beatS / 2
                val hn = (rate * 0.03f).toInt()
                for (i in 0 until hn) {
                    val idx = hs + i
                    if (idx >= total) break
                    buf[idx] += (r.nextFloat() * 2f - 1f) * 0.07f * (1f - i.toFloat() / hn)
                }
            }
        }
        var peak = 0.01f
        for (v in buf) if (abs(v) > peak) peak = abs(v)
        val k = 0.7f / peak
        for (i in buf.indices) buf[i] *= k
        return shorts(buf)
    }
}
