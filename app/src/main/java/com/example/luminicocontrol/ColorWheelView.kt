package com.example.luminicocontrol

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private var selectedX = 0f
    private var selectedY = 0f

    private var currentHue = 0f
    private var currentSaturation = 0f
    var currentColorInt = Color.RED
        private set

    // Callback de notificación al cambiar el color seleccionado
    var onColorSelected: ((r: Int, g: Int, b: Int, hex: String) -> Unit)? = null

    init {
        indicatorPaint.style = Paint.Style.FILL

        indicatorStrokePaint.style = Paint.Style.STROKE
        indicatorStrokePaint.strokeWidth = 3f * resources.displayMetrics.density
        indicatorStrokePaint.color = Color.WHITE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = (minOf(w, h) / 2f) * 0.9f

        if (radius <= 0f) return

        // 1. Degradado circular HSV Sweep (7 colores del espectro)
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN,
            Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        val sweepGradient = SweepGradient(centerX, centerY, colors, null)

        // 2. Degradado radial de saturación (blanco opaco al centro, transparente al borde)
        val radialGradient = RadialGradient(
            centerX, centerY, radius,
            Color.WHITE, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )

        // Combinar shaders en wheelPaint
        val composedShader = ComposeShader(sweepGradient, radialGradient, PorterDuff.Mode.SRC_OVER)
        wheelPaint.shader = composedShader

        // Posición inicial del indicador en el centro/borde por defecto
        actualizarPosicionIndicador()
    }

    private fun actualizarPosicionIndicador() {
        val angleRad = Math.toRadians(currentHue.toDouble())
        val dist = currentSaturation * radius
        selectedX = (centerX + dist * cos(angleRad)).toFloat()
        selectedY = (centerY + dist * sin(angleRad)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        // Dibujar rueda cromática
        canvas.drawCircle(centerX, centerY, radius, wheelPaint)

        // Dibujar indicador táctil (aro indicador)
        if (selectedX != 0f || selectedY != 0f) {
            val indRadius = 12f * resources.displayMetrics.density
            indicatorPaint.color = currentColorInt
            canvas.drawCircle(selectedX, selectedY, indRadius, indicatorPaint)
            canvas.drawCircle(selectedX, selectedY, indRadius, indicatorStrokePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                // Limitar al radio del círculo
                val clampedDist = dist.coerceAtMost(radius)

                var angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angleDeg < 0) angleDeg += 360f

                currentHue = angleDeg
                currentSaturation = (clampedDist / radius).coerceIn(0f, 1f)

                currentColorInt = Color.HSVToColor(floatArrayOf(currentHue, currentSaturation, 1f))

                actualizarPosicionIndicador()
                invalidate()

                // Formatear RGB y Hexadecimal
                val r = Color.red(currentColorInt)
                val g = Color.green(currentColorInt)
                val b = Color.blue(currentColorInt)
                val hex = String.format("%02X%02X%02X", r, g, b)

                onColorSelected?.invoke(r, g, b, hex)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setColorHSV(hue: Float, saturation: Float) {
        currentHue = hue.coerceIn(0f, 360f)
        currentSaturation = saturation.coerceIn(0f, 1f)
        currentColorInt = Color.HSVToColor(floatArrayOf(currentHue, currentSaturation, 1f))
        actualizarPosicionIndicador()
        invalidate()
    }
}