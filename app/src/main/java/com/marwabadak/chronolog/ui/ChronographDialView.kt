package com.marwabadak.chronolog.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 정밀 아날로그 크로노그래프 다이얼.
 *
 * 정적인 요소([buildDial])는 크기가 바뀔 때만 비트맵으로 한 번 그리고,
 * 매 프레임에는 바늘만 다시 그려 60fps 로 부드럽게 회전시킨다.
 *
 * - 외곽 베젤: 300개(1/5초) 미세 눈금 + 60개 초 눈금 + 5초 단위 00~55 숫자
 * - 테이퍼드 메탈릭 초침 + 카운터웨이트(꼬리 균형추) + 중심 허브 + 야광 포인트
 * - 30분 적산계 서브다이얼(9시 방향) · 1/100초 서브다이얼(3시 방향)
 */
class ChronographDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 표시할 경과 시간(밀리초). */
    var elapsedMs: Long = 0L
        set(value) {
            if (field != value) {
                field = value
                postInvalidateOnAnimation()
            }
        }

    /** 측정 중이면 바늘을 계속 갱신한다. */
    var running: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                postInvalidateOnAnimation()
            }
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private var dialBitmap: Bitmap? = null

    private var centerX = 0f
    private var centerY = 0f
    private var bezelRadius = 0f
    private var faceRadius = 0f

    private var subLeftX = 0f
    private var subLeftY = 0f
    private var subRightX = 0f
    private var subRightY = 0f
    private var subRadius = 0f

    private var handShader: Shader? = null
    private var hubShader: Shader? = null

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.24f
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val subHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xFFE6EFF7.toInt()
    }
    private val lumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9DFFD0.toInt() }
    private val lumeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x339DFFD0 }

    private val needlePath = Path()
    private val tailPath = Path()
    private val rectF = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val width = when (widthMode) {
            MeasureSpec.UNSPECIFIED -> dp(300f).toInt()
            else -> widthSize
        }
        setMeasuredDimension(width, width)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildDial(w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dialBitmap?.recycle()
        dialBitmap = null
    }

    private fun buildDial(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        dialBitmap?.recycle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        centerX = width / 2f
        centerY = height / 2f
        bezelRadius = minOf(width, height) / 2f * 0.965f
        faceRadius = bezelRadius * 0.885f

        subRadius = faceRadius * 0.255f
        subLeftX = centerX - faceRadius * 0.455f
        subLeftY = centerY - faceRadius * 0.055f
        subRightX = centerX + faceRadius * 0.455f
        subRightY = centerY - faceRadius * 0.055f

        drawBezel(canvas)
        drawFace(canvas)
        drawTicks(canvas)
        drawLabels(canvas)
        drawBrand(canvas)
        drawSubdial(canvas, subLeftX, subLeftY, ticks = 30, majorEvery = 5, label = "30 MIN")
        drawSubdial(canvas, subRightX, subRightY, ticks = 10, majorEvery = 1, label = "1/100 s")
        drawTwelveMarker(canvas)

        handShader = LinearGradient(
            -faceRadius * 0.05f, 0f, faceRadius * 0.05f, 0f,
            intArrayOf(
                0xFF6F7C89.toInt(),
                0xFFD9E4EE.toInt(),
                0xFFFDFEFF.toInt(),
                0xFF9FADBA.toInt(),
                0xFF69747F.toInt()
            ),
            floatArrayOf(0f, 0.32f, 0.5f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        hubShader = RadialGradient(
            centerX - faceRadius * 0.03f,
            centerY - faceRadius * 0.03f,
            faceRadius * 0.13f,
            intArrayOf(0xFFFBFDFF.toInt(), 0xFFB7C4D0.toInt(), 0xFF56616C.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        dialBitmap = bitmap
        invalidate()
    }

    private fun drawBezel(canvas: Canvas) {
        val cx = centerX
        val cy = centerY
        val r = bezelRadius

        // 바깥 그림자
        bezelPaint.shader = RadialGradient(
            cx, cy, r * 1.08f,
            intArrayOf(0x00000000, 0x00000000, 0x66000000),
            floatArrayOf(0f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.04f, bezelPaint)

        // 메탈릭 베젤
        bezelPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFF3C444E.toInt(),
                0xFFAEBAC7.toInt(),
                0xFFE9F1F8.toInt(),
                0xFF818D9A.toInt(),
                0xFF333B44.toInt(),
                0xFF9DA9B6.toInt(),
                0xFFE4EDF5.toInt(),
                0xFF6B7683.toInt(),
                0xFF3C444E.toInt()
            ),
            floatArrayOf(0f, 0.1f, 0.22f, 0.34f, 0.46f, 0.6f, 0.74f, 0.88f, 1f)
        )
        canvas.drawCircle(cx, cy, r, bezelPaint)

        // 베젤 안쪽 어두운 홈
        bezelPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(0xFF141B22.toInt(), 0xFF141B22.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, faceRadius * 1.012f, bezelPaint)

        // 얇은 하이라이트 링
        linePaint.shader = null
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(0.9f)
        linePaint.color = 0x55FFFFFF
        canvas.drawCircle(cx, cy, r * 0.995f, linePaint)
        linePaint.color = 0x22000000
        linePaint.strokeWidth = dp(1.6f)
        canvas.drawCircle(cx, cy, faceRadius * 1.014f, linePaint)
        linePaint.style = Paint.Style.FILL
    }

    private fun drawFace(canvas: Canvas) {
        facePaint.shader = RadialGradient(
            centerX - faceRadius * 0.22f,
            centerY - faceRadius * 0.32f,
            faceRadius * 1.5f,
            intArrayOf(
                0xFF1E2A36.toInt(),
                0xFF131C24.toInt(),
                0xFF0A0F14.toInt(),
                0xFF05080B.toInt()
            ),
            floatArrayOf(0f, 0.42f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, faceRadius, facePaint)

        // 다이얼 미세 결
        facePaint.shader = null
        facePaint.color = 0x0DFFFFFF
        canvas.drawCircle(centerX, centerY, faceRadius * 0.62f, facePaint)
    }

    private fun drawTicks(canvas: Canvas) {
        val outer = faceRadius * 0.975f
        val secondInner = faceRadius * 0.905f
        val microInner = faceRadius * 0.935f
        val fiveInner = faceRadius * 0.875f

        for (i in 0 until 300) {
            val angleDeg = i * (360.0 / 300.0)
            val rad = Math.toRadians(angleDeg)
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            val isSecond = i % 5 == 0
            val isFive = i % 25 == 0

            val inner = when {
                isFive -> fiveInner
                isSecond -> secondInner
                else -> microInner
            }
            val width = when {
                isFive -> faceRadius * 0.016f
                isSecond -> faceRadius * 0.008f
                else -> dp(0.75f)
            }
            linePaint.style = Paint.Style.STROKE
            linePaint.strokeWidth = width
            linePaint.color = when {
                isFive -> 0xFFF4F9FD.toInt()
                isSecond -> 0xFFD3DEE9.toInt()
                else -> 0x7A7C8B99
            }
            linePaint.strokeCap = Paint.Cap.BUTT
            canvas.drawLine(
                centerX + outer * sinA, centerY - outer * cosA,
                centerX + inner * sinA, centerY - inner * cosA,
                linePaint
            )
        }
        linePaint.style = Paint.Style.FILL
        linePaint.strokeCap = Paint.Cap.ROUND
    }

    private fun drawLabels(canvas: Canvas) {
        textPaint.textSize = faceRadius * 0.112f
        textPaint.color = 0xFFE6EFF7.toInt()
        textPaint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val radius = faceRadius * 0.775f
        for (i in 0 until 12) {
            val angleDeg = i * 30.0
            val rad = Math.toRadians(angleDeg)
            val x = centerX + radius * sin(rad).toFloat()
            val y = centerY - radius * cos(rad).toFloat()
            val label = String.format(java.util.Locale.KOREA, "%02d", i * 5)
            canvas.save()
            canvas.rotate(angleDeg.toFloat(), x, y)
            canvas.drawText(label, x, y + textPaint.textSize * 0.36f, textPaint)
            canvas.restore()
        }
        textPaint.typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    private fun drawBrand(canvas: Canvas) {
        brandPaint.textSize = faceRadius * 0.088f
        brandPaint.color = 0xFFDCE7F1.toInt()
        canvas.drawText("CHRONOLOG", centerX, centerY - faceRadius * 0.335f, brandPaint)

        textPaint.textSize = faceRadius * 0.055f
        textPaint.color = 0xFF6F8496.toInt()
        textPaint.letterSpacing = 0.18f
        canvas.drawText("CHRONOGRAPH", centerX, centerY + faceRadius * 0.40f, textPaint)
        textPaint.letterSpacing = 0f
    }

    private fun drawSubdial(canvas: Canvas, scx: Float, scy: Float, ticks: Int, majorEvery: Int, label: String) {
        // 접시
        facePaint.shader = RadialGradient(
            scx, scy - subRadius * 0.25f, subRadius * 1.3f,
            intArrayOf(0xFF121A22.toInt(), 0xFF0A0F14.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(scx, scy, subRadius, facePaint)

        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(0.9f)
        linePaint.color = 0xFF2C3A47.toInt()
        canvas.drawCircle(scx, scy, subRadius, linePaint)

        for (i in 0 until ticks) {
            val angleDeg = i * (360.0 / ticks)
            val rad = Math.toRadians(angleDeg)
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            val isMajor = i % majorEvery == 0
            val inner = if (isMajor) subRadius * 0.74f else subRadius * 0.815f
            linePaint.strokeWidth = if (isMajor) dp(1.15f) else dp(0.6f)
            linePaint.color = if (isMajor) 0xFFCBD8E4.toInt() else 0x8A7C8B99.toInt()
            canvas.drawLine(
                scx + subRadius * 0.93f * sinA, scy - subRadius * 0.93f * cosA,
                scx + inner * sinA, scy - inner * cosA,
                linePaint
            )
        }
        linePaint.style = Paint.Style.FILL

        // 라벨
        textPaint.textSize = faceRadius * 0.058f
        textPaint.color = 0xFF7C8FA0.toInt()
        textPaint.letterSpacing = 0.12f
        canvas.drawText(label, scx, scy + subRadius + faceRadius * 0.075f, textPaint)
        textPaint.letterSpacing = 0f
    }

    private fun drawTwelveMarker(canvas: Canvas) {
        val size = faceRadius * 0.045f
        val topY = centerY - faceRadius * 0.845f
        val path = Path()
        path.moveTo(centerX - size, topY - size)
        path.lineTo(centerX + size, topY - size)
        path.lineTo(centerX, topY + size * 0.9f)
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF2FE3A6.toInt()
        canvas.drawPath(path, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = dialBitmap ?: return
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        if (faceRadius <= 0f) return

        val elapsed = if (elapsedMs < 0L) 0L else elapsedMs

        // 초침: 60초에 한 바퀴
        val secondOfMinute = (elapsed % 60_000L) / 1000.0
        val secondAngle = (secondOfMinute / 60.0 * 360.0).toFloat()

        // 30분 적산계
        val minuteOfHalfHour = (elapsed % 1_800_000L) / 1_800_000.0
        val minuteAngle = (minuteOfHalfHour * 360.0).toFloat()

        // 1/100초
        val hundredOfSecond = (elapsed % 1_000L) / 1_000.0
        val hundredAngle = (hundredOfSecond * 360.0).toFloat()

        drawSubHand(canvas, subLeftX, subLeftY, minuteAngle)
        drawSubHand(canvas, subRightX, subRightY, hundredAngle)
        drawSecondHand(canvas, secondAngle)
        drawHub(canvas)
    }

    private fun drawSubHand(canvas: Canvas, scx: Float, scy: Float, angleDeg: Float) {
        canvas.save()
        canvas.rotate(angleDeg, scx, scy)
        subHandPaint.strokeWidth = dp(1.5f)
        subHandPaint.color = 0xFFE6EFF7.toInt()
        canvas.drawLine(scx, scy + subRadius * 0.22f, scx, scy - subRadius * 0.74f, subHandPaint)
        subHandPaint.color = 0xFF0B0F14.toInt()
        canvas.drawCircle(scx, scy, dp(1.4f), subHandPaint)
        canvas.restore()
    }

    private fun drawSecondHand(canvas: Canvas, angleDeg: Float) {
        val length = faceRadius * 0.985f
        val tailLength = faceRadius * 0.235f
        val hubWidth = faceRadius * 0.058f

        needlePath.reset()
        needlePath.moveTo(-hubWidth * 0.52f, 0f)
        needlePath.cubicTo(
            -hubWidth * 0.40f, -length * 0.30f,
            -hubWidth * 0.16f, -length * 0.70f,
            -dp(1.15f), -length
        )
        needlePath.lineTo(dp(1.15f), -length)
        needlePath.cubicTo(
            hubWidth * 0.16f, -length * 0.70f,
            hubWidth * 0.40f, -length * 0.30f,
            hubWidth * 0.52f, 0f
        )
        needlePath.close()

        val tailWidth = hubWidth * 0.5f
        tailPath.reset()
        tailPath.moveTo(-tailWidth, 0f)
        tailPath.lineTo(tailWidth, 0f)
        tailPath.lineTo(tailWidth * 0.58f, tailLength)
        tailPath.lineTo(-tailWidth * 0.58f, tailLength)
        tailPath.close()

        canvas.save()
        canvas.rotate(angleDeg, centerX, centerY)
        canvas.translate(centerX, centerY)

        // 그림자
        canvas.save()
        canvas.translate(dp(2.0f), dp(3.0f))
        canvas.drawPath(needlePath, shadowPaint)
        canvas.drawPath(tailPath, shadowPaint)
        canvas.restore()

        // 꼬리 균형추
        handPaint.shader = handShader
        canvas.drawPath(tailPath, handPaint)
        // 바늘 몸체
        canvas.drawPath(needlePath, handPaint)

        // 중앙 하이라이트
        handPaint.shader = null
        handPaint.style = Paint.Style.STROKE
        handPaint.strokeWidth = dp(0.6f)
        handPaint.color = 0x66FFFFFF
        canvas.drawLine(0f, -length * 0.18f, 0f, -length * 0.94f, handPaint)
        handPaint.style = Paint.Style.FILL

        // 야광 포인트
        canvas.drawCircle(0f, -length * 0.905f, hubWidth * 0.62f, lumeGlowPaint)
        canvas.drawCircle(0f, -length * 0.905f, hubWidth * 0.34f, lumePaint)

        canvas.restore()
    }

    private fun drawHub(canvas: Canvas) {
        facePaint.shader = hubShader
        canvas.drawCircle(centerX, centerY, faceRadius * 0.072f, facePaint)
        facePaint.shader = null
        facePaint.style = Paint.Style.STROKE
        facePaint.strokeWidth = dp(0.8f)
        facePaint.color = 0x55000000
        canvas.drawCircle(centerX, centerY, faceRadius * 0.072f, facePaint)
        facePaint.style = Paint.Style.FILL
        facePaint.color = 0xFF0A0F14.toInt()
        canvas.drawCircle(centerX, centerY, faceRadius * 0.022f, facePaint)
    }
}
