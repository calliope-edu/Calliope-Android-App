package cc.calliope.mini.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import cc.calliope.mini.R
import kotlin.random.Random

class SnowfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Bitmaps for snowflakes (from R.mipmap).
     * Replace them with your own resources or VectorDrawable → Bitmap.
     */
    private val snowflakeBitmaps: List<Bitmap> by lazy {
        listOf(
            BitmapFactory.decodeResource(resources, R.mipmap.ic_snowflake1),
            BitmapFactory.decodeResource(resources, R.mipmap.ic_snowflake2),
            BitmapFactory.decodeResource(resources, R.mipmap.ic_snowflake3)
        )
    }

    /**
     * Snowflake model that includes:
     *  - a ready (scaled) bitmap,
     *  - position (x, y) on the screen,
     *  - falling speed (speed) for the normal fall mode,
     *  - rotation and rotationSpeed,
     *  - alpha (transparency),
     *  - bitmap width/height,
     *  - velocityX / velocityY for movement during the shake effect.
     */
    data class Snowflake(
        val bitmap: Bitmap,
        var x: Float,
        var y: Float,
        var speed: Float,           // vertical falling speed (normal mode)
        var rotation: Float,
        var rotationSpeed: Float,
        val alpha: Int,
        val width: Int,
        val height: Int,

        // During the shake effect, we'll move the snowflake with velocityX/velocityY.
        var velocityX: Float = 0f,
        var velocityY: Float = 0f
    )

    private val snowflakes = mutableListOf<Snowflake>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Snowflake count
    private val snowflakeCount = 30

    // Random scale range
    private val minScale = 0.01f
    private val maxScale = 0.03f

    // Normal falling speed range
    private val minSpeed = 1f
    private val maxSpeed = 3f

    // Rotation speed range
    private val minRotationSpeed = -1f
    private val maxRotationSpeed = 1f

    // Flag to check if snowflakes have been generated
    private var isSnowflakesGenerated = false

    // Fields for the shake effect
    private var isShaking = false
    private var shakeEndTime = 0L // Time (System.currentTimeMillis) when the shake effect should end

    init {
        // Start the "frame-by-frame" animation
        startSnowAnimation()
    }

    /**
     * When the View dimensions become known, create the snowflakes.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isSnowflakesGenerated && w > 0 && h > 0) {
            generateSnowflakes(w, h)
            isSnowflakesGenerated = true
        }
    }

    /**
     * Create an array of snowflakes with random parameters.
     */
    private fun generateSnowflakes(viewWidth: Int, viewHeight: Int) {
        snowflakes.clear()
        repeat(snowflakeCount) {
            val originalBmp = snowflakeBitmaps.random()

            // Random scale
            val scale = Random.nextFloat() * (maxScale - minScale) + minScale
            val bmpWidth = (originalBmp.width * scale).toInt()
            val bmpHeight = (originalBmp.height * scale).toInt()

            val scaledBmp = Bitmap.createScaledBitmap(originalBmp, bmpWidth, bmpHeight, true)

            // Initial position
            val x = Random.nextFloat() * viewWidth
            val y = Random.nextFloat() * viewHeight

            // Falling speed (for example, 1..3)
            val speed = Random.nextFloat() * (maxSpeed - minSpeed) + minSpeed

            // Initial rotation angle (0..360)
            val rotation = Random.nextFloat() * 360f

            // Rotation speed
            val rotationSpeed =
                Random.nextFloat() * (maxRotationSpeed - minRotationSpeed) + minRotationSpeed

            // Alpha (transparency) range: 150..255
            val alpha = (150..255).random()

            snowflakes.add(
                Snowflake(
                    bitmap = scaledBmp,
                    x = x,
                    y = y,
                    speed = speed,
                    rotation = rotation,
                    rotationSpeed = rotationSpeed,
                    alpha = alpha,
                    width = bmpWidth,
                    height = bmpHeight
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        snowflakes.forEach { flake ->
            paint.alpha = flake.alpha

            val matrix = Matrix()
            // Shift the rotation pivot to the center of the bitmap
            matrix.postTranslate(-flake.width / 2f, -flake.height / 2f)

            // Apply rotation
            matrix.postRotate(flake.rotation)

            // Then move the snowflake to (x, y)
            matrix.postTranslate(flake.x, flake.y)

            canvas.drawBitmap(flake.bitmap, matrix, paint)
        }
    }

    private fun startSnowAnimation() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30 // ~30 fps
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                updateSnowflakes()
                invalidate()
            }
            start()
        }
    }

    /**
     * Update snowflake positions depending on whether shake mode is active.
     */
    private fun updateSnowflakes() {
        if (snowflakes.isEmpty()) return

        val now = System.currentTimeMillis()
        val viewWidth = width
        val viewHeight = height

        // If the shake time is over, return to the normal fall mode
        if (isShaking && now > shakeEndTime) {
            isShaking = false
            // Reset velocityX/velocityY so snowflakes go back to normal falling
            snowflakes.forEach { flake ->
                flake.velocityX = 0f
                flake.velocityY = 0f
            }
        }

        snowflakes.forEach { flake ->
            if (isShaking) {
                // Move according to velocityX/velocityY
                flake.x += flake.velocityX
                flake.y += flake.velocityY
            } else {
                // Normal falling
                flake.y += flake.speed
            }

            // Rotation
            flake.rotation += flake.rotationSpeed

            // If it goes out of the lower boundary, reset it to the top
            if (flake.y > viewHeight + flake.height) {
                flake.y = -flake.height.toFloat()
                flake.x = Random.nextFloat() * viewWidth
            }

            // If it goes out to the left
            if (flake.x < -flake.width) {
                flake.x = viewWidth + flake.width.toFloat()
            }
            // If it goes out to the right
            if (flake.x > viewWidth + flake.width) {
                flake.x = -flake.width.toFloat()
            }
        }
    }

    /**
     * Launch the "shake effect" for a few seconds:
     *  - snowflakes get random velocityX/velocityY,
     *  - they start "spreading out",
     *  - after 3 seconds, they return to normal falling.
     */
    fun shakeEffect() {
        isShaking = true
        // Ends after 3 seconds (3000 ms)
        shakeEndTime = System.currentTimeMillis() + 3000

        snowflakes.forEach { flake ->
            // Generate a random movement vector for X/Y, e.g., -10..10
            flake.velocityX = Random.nextFloat() * 20f - 10f
            flake.velocityY = Random.nextFloat() * 20f - 10f

            // Increase rotationSpeed for a stronger spin
            flake.rotationSpeed = (Random.nextFloat() - 0.5f) * 8f
        }
    }

    // For random Int in range
    private fun ClosedRange<Int>.random() =
        (Random.nextInt((endInclusive + 1) - start) + start)
}