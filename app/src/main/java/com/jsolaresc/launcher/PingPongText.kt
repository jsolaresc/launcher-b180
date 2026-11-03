package com.jsolaresc.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.TextView

/**
 * Texto de un renglón que, si no entra, se desplaza de ida y vuelta
 * (con pausas en cada punta) para que se pueda leer entero.
 */
class PingPongText @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : TextView(ctx, attrs) {

    private var animator: ValueAnimator? = null

    init {
        isSingleLine = true
        setHorizontallyScrolling(true)
        ellipsize = null
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        post { restart() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { restart() }
    }

    private fun restart() {
        animator?.cancel()
        scrollTo(0, 0)
        val visible = width - paddingLeft - paddingRight
        if (visible <= 0) return
        val overflow = (paint.measureText(text.toString()) - visible).toInt()
        if (overflow <= 0) return
        // ~30 px por segundo, con 1,5 s de pausa en cada punta.
        val move = (overflow * 1000L / (30 * resources.displayMetrics.density)).toLong().coerceAtLeast(800)
        val pause = 1500L
        val total = pause + move + pause + move
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction * total
                val x = when {
                    t < pause -> 0f
                    t < pause + move -> (t - pause) / move
                    t < pause * 2 + move -> 1f
                    else -> 1f - (t - pause * 2 - move) / move
                }
                scrollTo((x * overflow).toInt(), 0)
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
