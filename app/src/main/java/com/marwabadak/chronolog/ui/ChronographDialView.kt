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
 * 실물 기계식 크로노그래프를 재현한 정밀 아날로그 다이얼.
 *
 * 렌더링 구조
 * - [dialBitmap] : 케이스, 베젤, 타키미터, 다이얼, 챕터 링, 레일웨이, 아플리케 인덱스, 서브다이얼 홈까지
 *   크기가 바뀔 때 한 번만 그려 캐시한다.
 * - [glassBitmap] : 사파이어 크리스탈 반사와 림 비네팅 오버레이. 바늘 위에 얹히므로 매 프레임 마지막에 합성한다.
 * - 매 프레임: 캐시 2장 합성 + 서브 바늘 3개 + 초침 + 중심 허브만 다시 그린다.
 *
 * 눈금 구성
 * - 외곽 타키미터 스케일(500에서 65까지), 브러시드 베젤, 코인 에지 플루팅, 3시 크라운, 2시와 4시 푸셔
 * - 레일웨이 트랙: 1초 강조 눈금 60개 + 1/5초 눈금 300개 + 1/10초 보조 눈금
 * - 아플리케 인덱스: 5초 간격 입체 메탈 바 + 야광 인레이 + 12시 더블 마커
 * - 리세스드 서브다이얼: 9시 30분 적산계, 3시 1/100초, 6시 12시간 적산계
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
    private var glassBitmap: Bitmap? = null

    private var centerX = 0f
    private var centerY = 0f
    private var caseRadius = 0f
    private var dialRadius = 0f

    private var subMinX = 0f
    private var subMinY = 0f
    private var subMinR = 0f
    private var subSecX = 0f
    private var subSecY = 0f
    private var subSecR = 0f
    private var subHourX = 0f
    private var subHourY = 0f
    private var subHourR = 0f

    private var leftFacetShader: Shader? = null
    private var rightFacetShader: Shader? = null
    private var tipShader: Shader? = null
    private var hubShader: Shader? = null
    private var hubCoreShader: Shader? = null
    private var subMinShader: Shader? = null
    private var subSecShader: Shader? = null
    private var subHourShader: Shader? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.26f
        isSubpixelText = true
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }
    private val lumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBFFFE4.toInt() }
    private val lumeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x335FFFD0 }

    private val leftPath = Path()
    private val rightPath = Path()
    private val tipPath = Path()
    private val subHandPath = Path()
    private val ringPath = Path()
    private val circlePath = Path()
    private val rectF = RectF()
    private val rectF2 = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val width = when (widthMode) {
            MeasureSpec.UNSPECIFIED -> dp(320f).toInt()
            else -> widthSize
        }
        setMeasuredDimension(width, width)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildLayers(w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dialBitmap?.recycle()
        dialBitmap = null
        glassBitmap?.recycle()
        glassBitmap = null
    }

    // ------------------------------------------------------------------ 레이어 빌드

    private fun buildLayers(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        dialBitmap?.recycle()
        glassBitmap?.recycle()

        centerX = width / 2f
        centerY = height / 2f
        val half = minOf(width, height) / 2f
        caseRadius = half * 0.905f
        dialRadius = caseRadius * 0.855f

        subMinR = caseRadius * 0.205f
        subSecR = caseRadius * 0.205f
        subHourR = caseRadius * 0.165f
        subMinX = centerX - caseRadius * 0.385f
        subMinY = centerY - caseRadius * 0.045f
        subSecX = centerX + caseRadius * 0.385f
        subSecY = centerY - caseRadius * 0.045f
        subHourX = centerX
        subHourY = centerY + caseRadius * 0.395f

        buildShaders()

        val dial = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dial)
        drawCase(canvas)
        drawPushers(canvas)
        drawBezel(canvas)
        drawTachymeter(canvas)
        drawFace(canvas)
        drawChapterRing(canvas)
        drawRailway(canvas)
        drawNumerals(canvas)
        drawIndices(canvas)
        drawBrand(canvas)
        drawSubdial(canvas, subMinX, subMinY, subMinR, 30, 5, "30 MIN", intArrayOf(10, 20))
        drawSubdial(canvas, subSecX, subSecY, subSecR, 50, 5, "1/100 s", intArrayOf())
        drawSubdial(canvas, subHourX, subHourY, subHourR, 12, 1, "12 H", intArrayOf(3, 6, 9))
        dialBitmap = dial

        glassBitmap = buildGlass(width, height)
        invalidate()
    }

    private fun buildShaders() {
        val handHalfWidth = caseRadius * 0.030f

        // 초침 좌우 면: 하나의 쐐기 바늘에 서로 다른 명암을 주어 다면체 느낌을 만든다.
        leftFacetShader = LinearGradient(
            -handHalfWidth, 0f, 0f, 0f,
            intArrayOf(
                0xFF262E37.toInt(),
                0xFF67747F.toInt(),
                0xFFC9D6E2.toInt(),
                0xFFF6FAFE.toInt()
            ),
            floatArrayOf(0f, 0.34f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
        rightFacetShader = LinearGradient(
            0f, 0f, handHalfWidth, 0f,
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xFFE7F0F8.toInt(),
                0xFFACBAC6.toInt(),
                0xFF39424C.toInt()
            ),
            floatArrayOf(0f, 0.20f, 0.60f, 1f),
            Shader.TileMode.CLAMP
        )
        tipShader = LinearGradient(
            0f, -caseRadius * 0.790f, 0f, -caseRadius * 0.690f,
            intArrayOf(
                0xFFFF5B47.toInt(),
                0xFFE8341F.toInt(),
                0xFFB21C10.toInt()
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        hubShader = RadialGradient(
            centerX - caseRadius * 0.018f,
            centerY - caseRadius * 0.018f,
            caseRadius * 0.062f,
            intArrayOf(
                0xFFFCFEFF.toInt(),
                0xFFC3D0DC.toInt(),
                0xFF68737F.toInt(),
                0xFF2A323A.toInt()
            ),
            floatArrayOf(0f, 0.48f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        hubCoreShader = RadialGradient(
            centerX - caseRadius * 0.010f,
            centerY - caseRadius * 0.010f,
            caseRadius * 0.030f,
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xFFD6E1EB.toInt(),
                0xFF6E7A86.toInt()
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        subMinShader = handShaderFor(subMinR * 0.16f)
        subSecShader = handShaderFor(subSecR * 0.11f)
        subHourShader = handShaderFor(subHourR * 0.17f)
    }

    private fun handShaderFor(halfWidth: Float): Shader = LinearGradient(
        -halfWidth, 0f, halfWidth, 0f,
        intArrayOf(
            0xFF525E6A.toInt(),
            0xFFB4C2CF.toInt(),
            0xFFF8FBFF.toInt(),
            0xFFCBD8E4.toInt(),
            0xFF454F5A.toInt()
        ),
        floatArrayOf(0f, 0.26f, 0.50f, 0.74f, 1f),
        Shader.TileMode.CLAMP
    )

    // ------------------------------------------------------------------ 케이스와 베젤

    private fun drawCase(canvas: Canvas) {
        val r = caseRadius

        // 케이스가 화면에서 떠 보이도록 외곽 낙하 그림자
        fillPaint.shader = RadialGradient(
            centerX, centerY + dp(3f), r * 1.06f,
            intArrayOf(0x00000000, 0x00000000, 0x7A000000.toInt()),
            floatArrayOf(0f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, r * 1.06f, fillPaint)

        // 폴리시드 스틸 외곽 링
        fillPaint.shader = caseSweepShader(r)
        drawRing(canvas, r, r * 0.962f, fillPaint)

        // 케이스 옆면 홈 (동전 테두리 느낌의 미세 플루팅)
        val fluteCount = 144
        val fluteOuter = r * 0.999f
        val fluteInner = r * 0.962f
        for (i in 0 until fluteCount) {
            val angle = i * (360.0 / fluteCount)
            val rad = Math.toRadians(angle)
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            tickPaint.strokeWidth = dp(1.3f)
            tickPaint.color = if (i % 2 == 0) 0x38FFFFFF else 0x3A000000
            canvas.drawLine(
                centerX + fluteInner * s, centerY - fluteInner * c,
                centerX + fluteOuter * s, centerY - fluteOuter * c,
                tickPaint
            )
        }

        // 외곽 림 하이라이트 (상단 밝고 하단 어두운 원형 엣지)
        val rimRect = circleRect(r * 0.9955f)
        ringPaint.strokeWidth = dp(1.1f)
        ringPaint.color = 0x66FFFFFF
        canvas.drawArc(rimRect, 190f, 150f, false, ringPaint)
        ringPaint.color = 0x66000000
        canvas.drawArc(rimRect, 12f, 150f, false, ringPaint)
    }

    private fun caseSweepShader(r: Float): Shader = SweepGradient(
        centerX, centerY,
        intArrayOf(
            0xFF6C7A87.toInt(),
            0xFFC6D2DD.toInt(),
            0xFFF1F6FA.toInt(),
            0xFFAFBDC9.toInt(),
            0xFF525E69.toInt(),
            0xFF8B98A5.toInt(),
            0xFFE2ECF4.toInt(),
            0xFF77838F.toInt(),
            0xFF3B444D.toInt(),
            0xFFA6B3BF.toInt(),
            0xFFEDF4FA.toInt(),
            0xFF98A5B1.toInt(),
            0xFF4A555F.toInt(),
            0xFF6C7A87.toInt()
        ),
        floatArrayOf(
            0f, 0.08f, 0.15f, 0.22f, 0.30f, 0.38f, 0.46f, 0.54f,
            0.62f, 0.70f, 0.78f, 0.86f, 0.93f, 1f
        )
    )

    private fun drawPushers(canvas: Canvas) {
        // 2시와 4시 방향 푸셔
        drawLug(canvas, 60f, caseRadius * 0.031f, caseRadius * 0.118f)
        drawLug(canvas, 120f, caseRadius * 0.031f, caseRadius * 0.118f)
        // 3시 방향 크라운
        drawCrown(canvas)
    }

    private fun drawLug(canvas: Canvas, angleDeg: Float, halfWidth: Float, length: Float) {
        val r = caseRadius
        canvas.save()
        canvas.rotate(angleDeg, centerX, centerY)

        val top = centerY - r * 0.958f - length
        val bottom = centerY - r * 0.948f

        rectF.set(centerX - halfWidth, top, centerX + halfWidth, bottom)

        // 그림자
        canvas.save()
        canvas.translate(dp(0.8f), dp(1.4f))
        fillPaint.shader = null
        fillPaint.color = 0x59000000
        canvas.drawRoundRect(rectF, halfWidth, halfWidth, fillPaint)
        canvas.restore()

        // 원통형 금속면
        fillPaint.shader = LinearGradient(
            rectF.left, 0f, rectF.right, 0f,
            intArrayOf(
                0xFF1E252C.toInt(),
                0xFF8C9AA7.toInt(),
                0xFFEFF5FA.toInt(),
                0xFFB6C3CF.toInt(),
                0xFF2B333B.toInt()
            ),
            floatArrayOf(0f, 0.26f, 0.50f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, halfWidth * 0.85f, halfWidth * 0.85f, fillPaint)

        // 푸셔 끝면
        rectF2.set(
            rectF.left + halfWidth * 0.16f,
            rectF.top + dp(0.6f),
            rectF.right - halfWidth * 0.16f,
            rectF.top + dp(3.4f)
        )
        fillPaint.shader = LinearGradient(
            rectF2.left, 0f, rectF2.right, 0f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFD3DEE8.toInt(), 0xFF6D7883.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF2, dp(1.4f), dp(1.4f), fillPaint)
        fillPaint.shader = null

        canvas.restore()
    }

    private fun drawCrown(canvas: Canvas) {
        val r = caseRadius
        canvas.save()
        canvas.rotate(90f, centerX, centerY)

        val halfWidth = r * 0.043f
        val top = centerY - r * 0.995f
        val bottom = centerY - r * 0.940f
        rectF.set(centerX - halfWidth, top, centerX + halfWidth, bottom)

        canvas.save()
        canvas.translate(dp(0.9f), dp(1.6f))
        fillPaint.shader = null
        fillPaint.color = 0x66000000
        canvas.drawRoundRect(rectF, halfWidth * 0.5f, halfWidth * 0.5f, fillPaint)
        canvas.restore()

        fillPaint.shader = LinearGradient(
            rectF.left, 0f, rectF.right, 0f,
            intArrayOf(
                0xFF1B2229.toInt(),
                0xFF8E9CA9.toInt(),
                0xFFF3F8FC.toInt(),
                0xFFB9C6D2.toInt(),
                0xFF252D35.toInt()
            ),
            floatArrayOf(0f, 0.24f, 0.50f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, halfWidth * 0.55f, halfWidth * 0.55f, fillPaint)

        // 크라운 홈
        val grooves = 9
        for (i in 0 until grooves) {
            val x = rectF.left + rectF.width() * (i + 0.5f) / grooves
            tickPaint.strokeWidth = dp(0.8f)
            tickPaint.color = 0x4D000000
            canvas.drawLine(x, rectF.top + dp(1.6f), x, rectF.bottom - dp(1.6f), tickPaint)
            tickPaint.color = 0x33FFFFFF
            canvas.drawLine(x + dp(0.5f), rectF.top + dp(1.6f), x + dp(0.5f), rectF.bottom - dp(1.6f), tickPaint)
        }

        // 크라운 캡 하이라이트
        fillPaint.shader = null
        fillPaint.color = 0x55FFFFFF
        rectF2.set(rectF.left + dp(1.4f), rectF.top + dp(1.2f), rectF.right - dp(1.4f), rectF.top + dp(2.6f))
        canvas.drawRoundRect(rectF2, dp(0.8f), dp(0.8f), fillPaint)

        canvas.restore()
    }

    private fun drawBezel(canvas: Canvas) {
        val r = caseRadius

        // 베벨 경사면 (상단 밝고 하단 어두운 입체 모서리)
        fillPaint.shader = LinearGradient(
            centerX - r * 0.75f, centerY - r * 0.95f, centerX + r * 0.75f, centerY + r * 0.95f,
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xFFE1EAF2.toInt(),
                0xFF9BA9B6.toInt(),
                0xFF4B555F.toInt(),
                0xFF1C232A.toInt()
            ),
            floatArrayOf(0f, 0.24f, 0.52f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
        drawRing(canvas, r * 0.962f, r * 0.940f, fillPaint)

        // 브러시드 타키미터 플레이트
        fillPaint.shader = SweepGradient(
            centerX, centerY,
            intArrayOf(
                0xFF4A555F.toInt(),
                0xFF8C99A5.toInt(),
                0xFF616D79.toInt(),
                0xFF3B444D.toInt(),
                0xFF7C8894.toInt(),
                0xFF586470.toInt(),
                0xFF374049.toInt(),
                0xFF8794A0.toInt(),
                0xFF4A555F.toInt()
            ),
            floatArrayOf(0f, 0.12f, 0.24f, 0.37f, 0.50f, 0.62f, 0.75f, 0.88f, 1f)
        )
        drawRing(canvas, r * 0.940f, r * 0.866f, fillPaint)

        // 원형 브러시 결
        val brushCount = 216
        val brushOuter = r * 0.938f
        val brushInner = r * 0.868f
        for (i in 0 until brushCount) {
            val angle = i * (360.0 / brushCount) + 0.4
            val rad = Math.toRadians(angle)
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            tickPaint.strokeWidth = dp(0.6f)
            tickPaint.color = if (i % 3 == 0) 0x12FFFFFF else 0x0E000000
            canvas.drawLine(
                centerX + brushInner * s, centerY - brushInner * c,
                centerX + brushOuter * s, centerY - brushOuter * c,
                tickPaint
            )
        }

        // 베젤 상단과 하단 엣지 라인
        ringPaint.strokeWidth = dp(0.9f)
        ringPaint.color = 0x40FFFFFF
        canvas.drawCircle(centerX, centerY, r * 0.9395f, ringPaint)
        ringPaint.color = 0x66000000
        canvas.drawCircle(centerX, centerY, r * 0.8665f, ringPaint)

        // 내곽 경사 벽 (베젤에서 다이얼로)
        fillPaint.shader = LinearGradient(
            centerX, centerY - r * 0.87f, centerX, centerY + r * 0.87f,
            intArrayOf(
                0xFF2A343E.toInt(),
                0xFF0B1016.toInt(),
                0xFF070B0F.toInt(),
                0xFF2C3742.toInt()
            ),
            floatArrayOf(0f, 0.30f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        drawRing(canvas, r * 0.866f, r * 0.855f, fillPaint)

        // 다이얼 경계면 미세 하이라이트
        ringPaint.strokeWidth = dp(0.7f)
        ringPaint.color = 0x4DFFFFFF
        canvas.drawCircle(centerX, centerY, r * 0.8555f, ringPaint)
    }

    private fun drawTachymeter(canvas: Canvas) {
        val r = caseRadius
        val textRadius = r * 0.902f
        val values = intArrayOf(500, 400, 300, 240, 200, 180, 160, 140, 120, 100, 90, 80, 75, 70, 65)

        labelPaint.textSize = r * 0.035f
        labelPaint.letterSpacing = 0.02f

        for (value in values) {
            val seconds = 3600.0 / value.toDouble()
            val angle = seconds * 6.0
            val rad = Math.toRadians(angle)
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            val x = centerX + textRadius * s
            val y = centerY - textRadius * c

            // 각인 눈금
            tickPaint.strokeWidth = dp(0.8f)
            tickPaint.color = 0x66000000
            canvas.drawLine(
                centerX + r * 0.870f * s, centerY - r * 0.870f * c,
                centerX + r * 0.884f * s, centerY - r * 0.884f * c,
                tickPaint
            )
            tickPaint.color = 0x59FFFFFF
            canvas.drawLine(
                centerX + r * 0.871f * s, centerY - r * 0.871f * c,
                centerX + r * 0.885f * s, centerY - r * 0.885f * c,
                tickPaint
            )

            canvas.save()
            canvas.rotate(angle.toFloat(), x, y)
            // 음각 효과: 아래쪽 어두운 그림자 + 위쪽 밝은 문자
            labelPaint.color = 0x99000000.toInt()
            canvas.drawText(value.toString(), x, y + labelPaint.textSize * 0.34f + dp(0.5f), labelPaint)
            labelPaint.color = 0xFFD8E4EE.toInt()
            canvas.drawText(value.toString(), x, y + labelPaint.textSize * 0.34f, labelPaint)
            canvas.restore()
        }

        // 12시 위치 각인 문구
        val labelY = centerY - r * 0.902f
        labelPaint.textSize = r * 0.038f
        labelPaint.letterSpacing = 0.30f
        labelPaint.color = 0x99000000.toInt()
        canvas.drawText("TACHYMETRE", centerX, labelY + dp(0.6f), labelPaint)
        labelPaint.color = 0xFFE4EEF7.toInt()
        canvas.drawText("TACHYMETRE", centerX, labelY, labelPaint)
        labelPaint.letterSpacing = 0f
    }

    // ------------------------------------------------------------------ 다이얼

    private fun drawFace(canvas: Canvas) {
        val d = dialRadius

        // 선버스트 다크 슬레이트 베이스
        fillPaint.shader = RadialGradient(
            centerX - d * 0.30f, centerY - d * 0.44f, d * 1.58f,
            intArrayOf(
                0xFF2E3D4B.toInt(),
                0xFF1B2630.toInt(),
                0xFF0E151C.toInt(),
                0xFF070B0F.toInt()
            ),
            floatArrayOf(0f, 0.36f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, d, fillPaint)

        // 선버스트 광선
        val rays = 108
        val colors = IntArray(rays * 2 + 1)
        val positions = FloatArray(rays * 2 + 1)
        for (i in 0 until rays) {
            colors[i * 2] = 0x1AFFFFFF
            colors[i * 2 + 1] = 0x00000000
            positions[i * 2] = (i * 2f) / (rays * 2f)
            positions[i * 2 + 1] = (i * 2f + 1f) / (rays * 2f)
        }
        colors[rays * 2] = colors[0]
        positions[rays * 2] = 1f
        fillPaint.shader = SweepGradient(centerX, centerY, colors, positions)
        canvas.drawCircle(centerX, centerY, d * 0.998f, fillPaint)

        // 미세 동심원 질감
        ringPaint.strokeWidth = dp(0.5f)
        ringPaint.color = 0x0AFFFFFF
        var radius = d * 0.08f
        while (radius < d * 0.96f) {
            canvas.drawCircle(centerX, centerY, radius, ringPaint)
            radius += dp(2.4f)
        }
        ringPaint.color = 0x08000000
        radius = d * 0.08f
        while (radius < d * 0.96f) {
            canvas.drawCircle(centerX, centerY, radius + dp(1.2f), ringPaint)
            radius += dp(2.4f)
        }

        // 가장자리 비네팅
        fillPaint.shader = RadialGradient(
            centerX, centerY, d,
            intArrayOf(0x00000000, 0x00000000, 0x33000000, 0x70000000),
            floatArrayOf(0f, 0.58f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, d, fillPaint)
    }

    private fun drawChapterRing(canvas: Canvas) {
        val r = caseRadius
        val outer = r * 0.855f
        val inner = r * 0.795f

        // 경사진 챕터 링
        fillPaint.shader = LinearGradient(
            centerX, centerY - outer, centerX, centerY + outer,
            intArrayOf(
                0xFF3E4A56.toInt(),
                0xFF232D36.toInt(),
                0xFF10171E.toInt(),
                0xFF2C3742.toInt()
            ),
            floatArrayOf(0f, 0.34f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        drawRing(canvas, outer, inner, fillPaint)

        // 링 단차 그림자와 하이라이트
        ringPaint.strokeWidth = dp(0.9f)
        ringPaint.color = 0x8C000000.toInt()
        canvas.drawCircle(centerX, centerY, outer - dp(0.3f), ringPaint)
        ringPaint.color = 0x59FFFFFF
        canvas.drawCircle(centerX, centerY, outer - dp(1.0f), ringPaint)
        ringPaint.color = 0x66000000
        canvas.drawCircle(centerX, centerY, inner + dp(0.4f), ringPaint)
        ringPaint.color = 0x40DCE8F2
        canvas.drawCircle(centerX, centerY, inner, ringPaint)
    }

    private fun drawRailway(canvas: Canvas) {
        val r = caseRadius
        val outer = r * 0.795f
        val secondInner = r * 0.756f
        val fifthInner = r * 0.772f
        val microOuter = r * 0.7935f
        val microInner = r * 0.7865f

        // 1/10초 보조 눈금: 아주 미세한 계측기 질감
        for (i in 0 until 600) {
            if (i % 5 == 0) continue
            val angle = i * 0.6
            val rad = Math.toRadians(angle)
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            tickPaint.strokeWidth = dp(0.45f)
            tickPaint.color = if (i % 50 == 0) 0x66E9F2FA else 0x339DB0C0
            canvas.drawLine(
                centerX + microOuter * s, centerY - microOuter * c,
                centerX + microInner * s, centerY - microInner * c,
                tickPaint
            )
        }

        // 1/5초 눈금 300개와 1초 강조 눈금
        for (i in 0 until 300) {
            val angle = i * 1.2
            val rad = Math.toRadians(angle)
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            val isSecond = i % 5 == 0
            val isFive = i % 25 == 0

            val inner = when {
                isFive -> r * 0.742f
                isSecond -> secondInner
                else -> fifthInner
            }
            tickPaint.strokeWidth = when {
                isFive -> r * 0.0105f
                isSecond -> dp(1.5f)
                else -> dp(0.7f)
            }
            tickPaint.color = when {
                isFive -> 0xFFF7FBFF.toInt()
                isSecond -> 0xFFD2DFEA.toInt()
                i % 5 == 1 || i % 5 == 4 -> 0x99B7C6D4.toInt()
                else -> 0x7A93A4B3.toInt()
            }
            canvas.drawLine(
                centerX + outer * s, centerY - outer * c,
                centerX + inner * s, centerY - inner * c,
                tickPaint
            )
        }

        // 트랙 경계선
        ringPaint.strokeWidth = dp(0.6f)
        ringPaint.color = 0x40FFFFFF
        canvas.drawCircle(centerX, centerY, outer, ringPaint)
        ringPaint.color = 0x26000000
        canvas.drawCircle(centerX, centerY, r * 0.735f, ringPaint)
    }

    private fun drawNumerals(canvas: Canvas) {
        val r = caseRadius
        labelPaint.textSize = r * 0.056f
        labelPaint.color = 0xFFC9D7E4.toInt()
        labelPaint.letterSpacing = 0.04f
        val radius = r * 0.612f
        for (i in 1 until 12) {
            val angle = i * 30.0
            val rad = Math.toRadians(angle)
            val x = centerX + radius * sin(rad).toFloat()
            val y = centerY - radius * cos(rad).toFloat()
            val value = i * 5
            val label = if (value < 10) "0" + value else value.toString()
            canvas.save()
            canvas.rotate(angle.toFloat(), x, y)
            canvas.drawText(label, x, y + labelPaint.textSize * 0.35f, labelPaint)
            canvas.restore()
        }
        labelPaint.letterSpacing = 0f
    }

    private fun drawIndices(canvas: Canvas) {
        val r = caseRadius
        val outer = r * 0.735f
        val inner = r * 0.648f

        for (i in 0 until 12) {
            val angle = i * 30.0
            val isCardinal = i % 3 == 0
            val width = if (isCardinal) r * 0.040f else r * 0.027f
            if (i == 0) {
                // 12시 더블 마커
                drawIndexBar(canvas, angle - 4.6, inner, outer, width * 0.86f)
                drawIndexBar(canvas, angle + 4.6, inner, outer, width * 0.86f)
            } else {
                drawIndexBar(canvas, angle, inner, outer, width)
            }
        }
    }

    private fun drawIndexBar(canvas: Canvas, angleDeg: Double, inner: Float, outer: Float, width: Float) {
        canvas.save()
        canvas.rotate(angleDeg.toFloat(), centerX, centerY)

        rectF.set(centerX - width / 2f, centerY - outer, centerX + width / 2f, centerY - inner)

        // 입체 인덱스 낙하 그림자
        canvas.save()
        canvas.translate(dp(0.7f), dp(1.5f))
        fillPaint.shader = null
        fillPaint.color = 0x73000000
        canvas.drawRoundRect(rectF, width * 0.34f, width * 0.34f, fillPaint)
        canvas.restore()

        // 메탈 바디 (좌우 베벨 명암)
        fillPaint.shader = LinearGradient(
            rectF.left, 0f, rectF.right, 0f,
            intArrayOf(
                0xFF3C4650.toInt(),
                0xFF98A6B3.toInt(),
                0xFFF8FBFF.toInt(),
                0xFFDDE8F2.toInt(),
                0xFFB0BECA.toInt(),
                0xFF39424C.toInt()
            ),
            floatArrayOf(0f, 0.16f, 0.38f, 0.60f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, width * 0.32f, width * 0.32f, fillPaint)

        // 안쪽 챔퍼
        rectF2.set(
            rectF.left + width * 0.17f,
            rectF.top + width * 0.20f,
            rectF.right - width * 0.17f,
            rectF.bottom - width * 0.16f
        )
        fillPaint.shader = LinearGradient(
            rectF2.left, 0f, rectF2.right, 0f,
            intArrayOf(0xFF6C7A87.toInt(), 0xFFF2F8FD.toInt(), 0xFF93A1AE.toInt()),
            floatArrayOf(0f, 0.44f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF2, width * 0.20f, width * 0.20f, fillPaint)

        // 야광 인레이
        val lumeTop = rectF.top + rectF.height() * 0.20f
        val lumeBottom = rectF.bottom - rectF.height() * 0.15f
        rectF2.set(centerX - width * 0.16f, lumeTop, centerX + width * 0.16f, lumeBottom)
        fillPaint.shader = LinearGradient(
            rectF2.left, 0f, rectF2.right, 0f,
            intArrayOf(
                0xFF3FC79A.toInt(),
                0xFFBFFFE4.toInt(),
                0xFF6BE8BE.toInt()
            ),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF2, width * 0.14f, width * 0.14f, fillPaint)

        // 인레이 상단 유리 반사
        fillPaint.shader = null
        fillPaint.color = 0x4DFFFFFF
        canvas.drawRect(
            rectF2.left,
            rectF2.top,
            rectF2.right,
            rectF2.top + rectF2.height() * 0.20f,
            fillPaint
        )

        canvas.restore()
    }

    private fun drawBrand(canvas: Canvas) {
        val r = caseRadius

        brandPaint.textSize = r * 0.063f
        brandPaint.color = 0xFFF0F7FC.toInt()
        canvas.drawText("CHRONOLOG", centerX, centerY - r * 0.300f, brandPaint)

        labelPaint.textSize = r * 0.034f
        labelPaint.letterSpacing = 0.34f
        labelPaint.color = 0xFF7C90A2.toInt()
        canvas.drawText("PRECISION CHRONOGRAPH", centerX, centerY - r * 0.243f, labelPaint)
        labelPaint.letterSpacing = 0f

        labelPaint.textSize = r * 0.030f
        labelPaint.letterSpacing = 0.20f
        labelPaint.color = 0xFF5F7385.toInt()
        canvas.drawText("TACHYMETER 1/100 s", centerX, centerY + r * 0.232f, labelPaint)
        labelPaint.letterSpacing = 0f
    }

    // ------------------------------------------------------------------ 서브다이얼

    private fun drawSubdial(
        canvas: Canvas,
        scx: Float,
        scy: Float,
        r: Float,
        tickCount: Int,
        majorEvery: Int,
        label: String,
        numerals: IntArray
    ) {
        // 리세스 단차 (위로 솟은 테두리)
        fillPaint.shader = LinearGradient(
            scx, scy - r * 1.03f, scx, scy + r * 1.03f,
            intArrayOf(
                0xFF0A1016.toInt(),
                0xFF3A4855.toInt(),
                0xFF141C24.toInt()
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(scx, scy, r * 1.025f, fillPaint)

        // 오목한 바닥 (위쪽이 어둡고 아래쪽으로 빛이 도는 음각)
        fillPaint.shader = RadialGradient(
            scx, scy + r * 0.46f, r * 1.34f,
            intArrayOf(
                0xFF1A2530.toInt(),
                0xFF0D141B.toInt(),
                0xFF05080B.toInt()
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(scx, scy, r, fillPaint)

        // 동심원 미세 홈 (스네일 링)
        ringPaint.strokeWidth = dp(0.5f)
        var sr = r * 0.06f
        var index = 0
        while (sr < r * 0.985f) {
            ringPaint.color = if (index % 2 == 0) 0x0FFFFFFF else 0x08000000
            canvas.drawCircle(scx, scy, sr, ringPaint)
            sr += dp(1.7f)
            index++
        }

        // 상단 내부 그림자
        rectF.set(scx - r * 0.965f, scy - r * 0.965f, scx + r * 0.965f, scy + r * 0.965f)
        ringPaint.strokeWidth = r * 0.055f
        ringPaint.color = 0x59000000
        canvas.drawArc(rectF, 198f, 144f, false, ringPaint)
        ringPaint.strokeWidth = r * 0.022f
        ringPaint.color = 0x4D000000
        canvas.drawArc(rectF, 206f, 128f, false, ringPaint)

        // 하단 림 하이라이트
        ringPaint.strokeWidth = dp(1.0f)
        ringPaint.color = 0x66E9F3FA
        canvas.drawArc(rectF, 22f, 136f, false, ringPaint)
        ringPaint.strokeWidth = dp(0.6f)
        ringPaint.color = 0x33FFFFFF
        canvas.drawArc(rectF, 14f, 152f, false, ringPaint)

        // 눈금
        for (i in 0 until tickCount) {
            val angle = i * (360.0 / tickCount)
            val rad = Math.toRadians(angle)
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            val isMajor = i % majorEvery == 0
            val outer = r * 0.905f
            val inner = if (isMajor) r * 0.762f else r * 0.822f
            tickPaint.strokeWidth = if (isMajor) dp(1.35f) else dp(0.65f)
            tickPaint.color = if (isMajor) 0xFFDCE8F2.toInt() else 0x8CA6B7C6.toInt()
            canvas.drawLine(
                scx + outer * s, scy - outer * c,
                scx + inner * s, scy - inner * c,
                tickPaint
            )
        }

        // 내부 숫자
        labelPaint.textSize = r * 0.24f
        labelPaint.color = 0xFFC6D5E2.toInt()
        for (n in numerals) {
            val angle = n * (360.0 / tickCount)
            val rad = Math.toRadians(angle)
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            val nr = r * 0.52f
            canvas.drawText(
                n.toString(),
                scx + nr * s,
                scy - nr * c + labelPaint.textSize * 0.35f,
                labelPaint
            )
        }

        // 다이얼 명칭
        labelPaint.textSize = r * 0.175f
        labelPaint.color = 0xFF6F8496.toInt()
        labelPaint.letterSpacing = 0.08f
        canvas.drawText(label, scx, scy - r * 0.44f, labelPaint)
        labelPaint.letterSpacing = 0f
    }

    // ------------------------------------------------------------------ 유리 오버레이

    private fun buildGlass(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = caseRadius

        circlePath.reset()
        circlePath.addCircle(centerX, centerY, dialRadius * 1.006f, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(circlePath)

        // 대각선 사파이어 반사광
        canvas.save()
        canvas.rotate(-33f, centerX, centerY)
        rectF.set(centerX - r * 1.25f, centerY - r * 0.95f, centerX + r * 1.25f, centerY - r * 0.36f)
        fillPaint.shader = LinearGradient(
            0f, rectF.top, 0f, rectF.bottom,
            intArrayOf(
                0x00FFFFFF,
                0x12FFFFFF,
                0x22FFFFFF,
                0x0AFFFFFF,
                0x00000000
            ),
            floatArrayOf(0f, 0.34f, 0.54f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rectF, fillPaint)

        // 두 번째 얇은 반사
        rectF2.set(centerX - r * 1.25f, centerY - r * 0.20f, centerX + r * 1.25f, centerY - r * 0.03f)
        fillPaint.shader = LinearGradient(
            0f, rectF2.top, 0f, rectF2.bottom,
            intArrayOf(0x00FFFFFF, 0x0CFFFFFF, 0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rectF2, fillPaint)
        canvas.restore()

        // 안티리플렉션 틴트
        fillPaint.shader = null
        fillPaint.color = 0x0A2F5F96
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        // 가장자리 림 비네팅
        fillPaint.shader = RadialGradient(
            centerX, centerY, dialRadius,
            intArrayOf(0x00000000, 0x00000000, 0x2E000000, 0x66000000),
            floatArrayOf(0f, 0.80f, 0.94f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        // 상단 림 하이라이트 아치
        val arcRect = circleRect(dialRadius * 0.985f)
        ringPaint.strokeWidth = dp(2.4f)
        ringPaint.color = 0x1FFFFFFF
        canvas.drawArc(arcRect, 202f, 136f, false, ringPaint)
        ringPaint.strokeWidth = dp(1.0f)
        ringPaint.color = 0x14FFFFFF
        canvas.drawArc(arcRect, 208f, 124f, false, ringPaint)

        canvas.restore()
        return bitmap
    }

    // ------------------------------------------------------------------ 매 프레임 렌더

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = dialBitmap ?: return
        canvas.drawBitmap(base, 0f, 0f, null)
        if (caseRadius <= 0f) return

        val elapsed = if (elapsedMs < 0L) 0L else elapsedMs

        val hundredAngle = ((elapsed % 1_000L) / 1_000.0 * 360.0).toFloat()
        val secondAngle = ((elapsed % 60_000L) / 60_000.0 * 360.0).toFloat()
        val minuteAngle = ((elapsed % 1_800_000L) / 1_800_000.0 * 360.0).toFloat()
        val hourAngle = ((elapsed % 43_200_000L) / 43_200_000.0 * 360.0).toFloat()

        drawSubHand(canvas, subMinX, subMinY, subMinR, minuteAngle, subMinShader, 0.16f, 0.80f)
        drawSubHand(canvas, subSecX, subSecY, subSecR, hundredAngle, subSecShader, 0.11f, 0.84f)
        drawSubHand(canvas, subHourX, subHourY, subHourR, hourAngle, subHourShader, 0.17f, 0.78f)

        drawChronoHand(canvas, secondAngle)
        drawHub(canvas)

        glassBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun drawSubHand(
        canvas: Canvas,
        scx: Float,
        scy: Float,
        radius: Float,
        angleDeg: Float,
        shader: Shader?,
        widthFrac: Float,
        lengthFrac: Float
    ) {
        val w = radius * widthFrac
        val tip = radius * lengthFrac
        val tail = radius * 0.20f

        subHandPath.reset()
        subHandPath.moveTo(0f, tail)
        subHandPath.lineTo(-w * 0.34f, tail * 0.52f)
        subHandPath.lineTo(-w * 0.54f, -tip * 0.08f)
        subHandPath.lineTo(-w * 0.20f, -tip * 0.84f)
        subHandPath.lineTo(0f, -tip)
        subHandPath.lineTo(w * 0.20f, -tip * 0.84f)
        subHandPath.lineTo(w * 0.54f, -tip * 0.08f)
        subHandPath.lineTo(w * 0.34f, tail * 0.52f)
        subHandPath.close()

        canvas.save()
        canvas.translate(scx, scy)
        canvas.rotate(angleDeg)

        canvas.save()
        canvas.translate(dp(0.9f), dp(1.3f))
        shadowPaint.color = 0x40000000
        canvas.drawPath(subHandPath, shadowPaint)
        canvas.restore()

        handPaint.shader = shader
        handPaint.alpha = 255
        canvas.drawPath(subHandPath, handPaint)
        handPaint.shader = null

        // 중심 캡
        fillPaint.shader = null
        fillPaint.color = 0xFFD9E5EF.toInt()
        canvas.drawCircle(0f, 0f, w * 0.95f, fillPaint)
        fillPaint.color = 0xFF151C23.toInt()
        canvas.drawCircle(0f, 0f, w * 0.48f, fillPaint)

        canvas.restore()
    }

    private fun drawChronoHand(canvas: Canvas, angleDeg: Float) {
        val r = caseRadius
        val tip = r * 0.790f
        val tail = r * 0.225f
        val w = r * 0.030f

        buildHandPaths(tip, tail, w)

        canvas.save()
        canvas.rotate(angleDeg, centerX, centerY)
        canvas.translate(centerX, centerY)

        // 다층 오프셋 그림자 (하드웨어 캔버스에서도 부드럽게 보이도록 3겹)
        shadowPaint.color = 0x24000000
        canvas.save()
        canvas.translate(dp(2.4f), dp(3.4f))
        canvas.drawPath(leftPath, shadowPaint)
        canvas.drawPath(rightPath, shadowPaint)
        canvas.restore()

        shadowPaint.color = 0x33000000
        canvas.save()
        canvas.translate(dp(1.5f), dp(2.1f))
        canvas.drawPath(leftPath, shadowPaint)
        canvas.drawPath(rightPath, shadowPaint)
        canvas.restore()

        shadowPaint.color = 0x4D000000
        canvas.save()
        canvas.translate(dp(0.6f), dp(0.9f))
        canvas.drawPath(leftPath, shadowPaint)
        canvas.drawPath(rightPath, shadowPaint)
        canvas.restore()

        // 좌우 패싯
        handPaint.shader = leftFacetShader
        canvas.drawPath(leftPath, handPaint)
        handPaint.shader = rightFacetShader
        canvas.drawPath(rightPath, handPaint)

        // 정밀 포인트 컬러 팁
        handPaint.shader = tipShader
        canvas.drawPath(tipPath, handPaint)
        handPaint.shader = null

        // 중심 리지 하이라이트
        ringPaint.strokeWidth = dp(0.7f)
        ringPaint.color = 0x80FFFFFF.toInt()
        canvas.drawLine(0f, -tip * 0.62f, 0f, tail * 0.70f, ringPaint)

        // 야광 포인트
        canvas.drawCircle(0f, -tip * 0.705f, w * 0.80f, lumeGlowPaint)
        canvas.drawCircle(0f, -tip * 0.705f, w * 0.36f, lumePaint)

        // 카운터웨이트
        fillPaint.shader = hubCoreShader
        canvas.drawCircle(0f, tail * 0.52f, w * 0.62f, fillPaint)
        fillPaint.shader = null
        fillPaint.color = 0xFF2C3540.toInt()
        canvas.drawCircle(0f, tail * 0.52f, w * 0.22f, fillPaint)

        canvas.restore()
    }

    private fun buildHandPaths(tip: Float, tail: Float, w: Float) {
        leftPath.reset()
        leftPath.moveTo(0f, -tip)
        leftPath.lineTo(-w * 0.30f, -tip * 0.905f)
        leftPath.lineTo(-w * 0.58f, -tip * 0.700f)
        leftPath.lineTo(-w * 0.88f, -tip * 0.090f)
        leftPath.lineTo(-w * 0.76f, tail * 0.360f)
        leftPath.lineTo(-w * 0.34f, tail * 0.880f)
        leftPath.lineTo(0f, tail)
        leftPath.close()

        rightPath.reset()
        rightPath.moveTo(0f, -tip)
        rightPath.lineTo(w * 0.30f, -tip * 0.905f)
        rightPath.lineTo(w * 0.58f, -tip * 0.700f)
        rightPath.lineTo(w * 0.88f, -tip * 0.090f)
        rightPath.lineTo(w * 0.76f, tail * 0.360f)
        rightPath.lineTo(w * 0.34f, tail * 0.880f)
        rightPath.lineTo(0f, tail)
        rightPath.close()

        tipPath.reset()
        tipPath.moveTo(0f, -tip)
        tipPath.lineTo(-w * 0.30f, -tip * 0.905f)
        tipPath.lineTo(-w * 0.20f, -tip * 0.775f)
        tipPath.lineTo(w * 0.20f, -tip * 0.775f)
        tipPath.lineTo(w * 0.30f, -tip * 0.905f)
        tipPath.close()
    }

    private fun drawHub(canvas: Canvas) {
        val r = caseRadius

        // 1단 캡
        fillPaint.shader = hubShader
        canvas.drawCircle(centerX, centerY, r * 0.052f, fillPaint)
        fillPaint.shader = null

        ringPaint.strokeWidth = dp(0.9f)
        ringPaint.color = 0x66000000
        canvas.drawCircle(centerX, centerY, r * 0.052f, ringPaint)
        ringPaint.strokeWidth = dp(0.6f)
        ringPaint.color = 0x66FFFFFF
        canvas.drawCircle(centerX, centerY, r * 0.040f, ringPaint)

        // 2단 캡
        fillPaint.shader = hubCoreShader
        canvas.drawCircle(centerX, centerY, r * 0.029f, fillPaint)
        fillPaint.shader = null

        // 중심 축과 하이라이트
        fillPaint.color = 0xFFB02A31.toInt()
        canvas.drawCircle(centerX, centerY, r * 0.0105f, fillPaint)
        fillPaint.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(centerX - r * 0.0035f, centerY - r * 0.0035f, r * 0.0038f, fillPaint)
    }

    // ------------------------------------------------------------------ 공용 헬퍼

    private fun drawRing(canvas: Canvas, outer: Float, inner: Float, paint: Paint) {
        ringPath.reset()
        ringPath.addCircle(centerX, centerY, outer, Path.Direction.CW)
        ringPath.addCircle(centerX, centerY, inner, Path.Direction.CCW)
        canvas.drawPath(ringPath, paint)
    }

    private fun circleRect(radius: Float): RectF {
        rectF2.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        return rectF2
    }
}
