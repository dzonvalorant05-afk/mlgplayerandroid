package com.mlg.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full MLG-mode view:
 *  - FFT bars driven by android.media.audiofx.Visualizer (setWaveData/setFft from MainActivity)
 *  - Floating neon particles (triangles / text bursts) animated every frame
 *  - Screen flash + shake trigger on track change / beat
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- Audio data ----
    @Volatile private var fft: ByteArray? = null

    fun updateFft(data: ByteArray) {
        fft = data
        postInvalidateOnAnimation()
    }

    // ---- Particles ----
    private data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var size: Float,
        var rot: Float,
        var vrot: Float,
        var color: Int,
        var text: String? = null,
        var life: Float = 1f
    )

    private val particles = mutableListOf<Particle>()
    private val texts = listOf("MLG", "420", "69", "NO SCOPE", "QUICKSCOPE", "SWAG", "ILLUMINATI", "DORITOS")
    private val neonColors = listOf(
        Color.parseColor("#39FF14"),
        Color.parseColor("#FF073A"),
        Color.parseColor("#B026FF"),
        Color.parseColor("#FFF01F"),
        Color.parseColor("#00E5FF")
    )

    private val barPaint = Paint().apply { isAntiAlias = true }
    private val particlePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize = 46f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val flashPaint = Paint()

    private var flashAlpha = 0f       // screen flash overlay
    private var shakeAmount = 0f      // canvas shake offset
    private var frame = 0L

    init {
        setBackgroundColor(Color.TRANSPARENT)
        repeat(24) { spawnParticle() }
        // continuous animation loop
        postOnAnimation(object : Runnable {
            override fun run() {
                frame++
                stepParticles()
                if (Random.nextInt(100) < 4) spawnBurstText()
                if (flashAlpha > 0f) flashAlpha = (flashAlpha - 0.04f).coerceAtLeast(0f)
                if (shakeAmount > 0f) shakeAmount *= 0.85f
                invalidate()
                postOnAnimation(this)
            }
        })
    }

    /** Call this whenever a new track starts, to trigger a flash+shake burst. */
    fun triggerTrackChangeBurst() {
        flashAlpha = 0.55f
        shakeAmount = 18f
        repeat(10) { spawnBurstText() }
    }

    private fun spawnParticle() {
        val fromLeft = Random.nextBoolean()
        particles.add(
            Particle(
                x = if (fromLeft) -50f else (width.takeIf { it > 0 } ?: 1200) + 50f,
                y = Random.nextFloat() * (height.takeIf { it > 0 } ?: 800),
                vx = (if (fromLeft) 1f else -1f) * (2f + Random.nextFloat() * 4f),
                vy = (Random.nextFloat() - 0.5f) * 2f,
                size = 20f + Random.nextFloat() * 40f,
                rot = Random.nextFloat() * 360f,
                vrot = (Random.nextFloat() - 0.5f) * 6f,
                color = neonColors.random()
            )
        )
    }

    private fun spawnBurstText() {
        particles.add(
            Particle(
                x = Random.nextFloat() * (width.takeIf { it > 0 } ?: 1200),
                y = (height.takeIf { it > 0 } ?: 800).toFloat(),
                vx = (Random.nextFloat() - 0.5f) * 2f,
                vy = -(3f + Random.nextFloat() * 4f),
                size = 30f + Random.nextFloat() * 20f,
                rot = 0f,
                vrot = 0f,
                color = neonColors.random(),
                text = texts.random(),
                life = 1f
            )
        )
    }

    private fun stepParticles() {
        val w = width.toFloat()
        val h = height.toFloat()
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx
            p.y += p.vy
            p.rot += p.vrot
            if (p.text != null) {
                p.life -= 0.012f
                if (p.life <= 0f) { it.remove(); continue }
            } else {
                if (p.x < -80 || p.x > w + 80) { it.remove(); spawnParticle(); }
            }
        }
        while (particles.count { it.text == null } < 24) spawnParticle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        if (shakeAmount > 0.5f) {
            canvas.translate(
                (Random.nextFloat() - 0.5f) * shakeAmount,
                (Random.nextFloat() - 0.5f) * shakeAmount
            )
        }

        // background subtle gradient pulse
        val pulse = (sin(frame / 15.0) * 10).toInt()
        canvas.drawColor(Color.rgb(0, (5 + pulse).coerceIn(0, 20), 0))

        drawParticles(canvas)
        drawBars(canvas)

        canvas.restore()

        if (flashAlpha > 0f) {
            flashPaint.color = Color.argb((flashAlpha * 255).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            particlePaint.color = p.color
            if (p.text != null) {
                textPaint.color = p.color
                textPaint.alpha = (p.life * 255).toInt().coerceIn(0, 255)
                textPaint.textSize = p.size
                canvas.drawText(p.text!!, p.x, p.y, textPaint)
            } else {
                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.rotate(p.rot)
                // neon triangle ("illuminati" style)
                val s = p.size
                val path = android.graphics.Path().apply {
                    moveTo(0f, -s)
                    lineTo(s, s)
                    lineTo(-s, s)
                    close()
                }
                particlePaint.alpha = 180
                canvas.drawPath(path, particlePaint)
                canvas.restore()
            }
        }
    }

    private fun drawBars(canvas: Canvas) {
        val data = fft ?: return
        if (data.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bars = 48
        val barWidth = w / bars
        val baseY = h - 60f

        for (i in 0 until bars) {
            // crude magnitude from FFT byte pairs
            val idx = (i * 2 + 2).coerceAtMost(data.size - 2)
            val re = data[idx].toInt()
            val im = data[idx + 1].toInt()
            var magnitude = Math.sqrt((re * re + im * im).toDouble()).toFloat()
            magnitude = (magnitude * 3.2f).coerceAtMost(h * 0.7f)

            barPaint.color = neonColors[i % neonColors.size]
            barPaint.alpha = 220
            val left = i * barWidth + 4f
            val right = left + barWidth - 8f
            canvas.drawRect(left, baseY - magnitude, right, baseY, barPaint)
            // mirrored reflection, faded
            barPaint.alpha = 70
            canvas.drawRect(left, baseY, right, baseY + magnitude * 0.3f, barPaint)
        }
    }
}
