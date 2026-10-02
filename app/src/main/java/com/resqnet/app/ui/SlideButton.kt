package com.resqnet.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.resqnet.app.R

class SlideButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val trackMargin = (4 * density).toInt()
    private var currentThumbSize = (40 * density).toInt()

    private val progressView: View
    private val labelView: TextView
    private val thumbContainer: FrameLayout
    private val thumbIcon: ImageView

    private var initialTouchX = 0f
    private var startThumbX = 0f
    private var isDragging = false
    private var isCompleted = false

    var onSlideCompleteListener: (() -> Unit)? = null

    init {
        // Outer pill container styling
        background = ContextCompat.getDrawable(context, R.drawable.bg_slide_track)
        clipToOutline = true

        // Progress fill view expanding behind thumb
        progressView = View(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_slide_progress)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT)
        }
        addView(progressView)

        // Center call-to-action text
        labelView = TextView(context).apply {
            text = "Slide to enter channel  ❯❯❯"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0.9f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                // Ensure text padding avoids the thumb start zone
                setPadding((currentThumbSize + trackMargin * 2), 0, (currentThumbSize / 2), 0)
            }
        }
        addView(labelView)

        // Circular draggable thumb handle
        thumbContainer = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_slide_thumb)
            elevation = 4 * density
            layoutParams = LayoutParams(currentThumbSize, currentThumbSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = trackMargin
            }
        }

        thumbIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_forward)
            layoutParams = LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt(),
                Gravity.CENTER
            )
        }
        thumbContainer.addView(thumbIcon)
        addView(thumbContainer)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) {
            val calcThumb = (h - 2 * trackMargin).coerceAtLeast((24 * density).toInt())
            if (calcThumb != currentThumbSize) {
                currentThumbSize = calcThumb
                thumbContainer.layoutParams.width = calcThumb
                thumbContainer.layoutParams.height = calcThumb
                labelView.setPadding((calcThumb + trackMargin * 2), 0, (calcThumb / 2), 0)
                thumbContainer.requestLayout()
            }
        }
    }

    fun setText(text: CharSequence) {
        labelView.text = text
    }

    fun setAlertTheme() {
        background = ContextCompat.getDrawable(context, R.drawable.bg_slide_track_logout)
        progressView.background = ContextCompat.getDrawable(context, R.drawable.bg_slide_progress_logout)
        thumbContainer.background = ContextCompat.getDrawable(context, R.drawable.bg_slide_thumb_logout)
        thumbIcon.setColorFilter(Color.parseColor("#F04444"))
        labelView.setTextColor(Color.parseColor("#F5F3ED"))
    }

    private fun getMaxSlideDistance(): Int {
        return (width - currentThumbSize - 2 * trackMargin).coerceAtLeast(0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isCompleted || !isEnabled) return false

        val maxSlide = getMaxSlideDistance()
        if (maxSlide <= 0) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if touch is near thumb or starts a drag
                if (event.x <= (thumbContainer.translationX + currentThumbSize + trackMargin * 4)) {
                    isDragging = true
                    initialTouchX = event.x
                    startThumbX = thumbContainer.translationX
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false

                val deltaX = event.x - initialTouchX
                val newX = (startThumbX + deltaX).coerceIn(0f, maxSlide.toFloat())
                updateSliderPosition(newX, maxSlide)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)

                val progress = if (maxSlide > 0) thumbContainer.translationX / maxSlide.toFloat() else 0f
                if (progress >= 0.78f) {
                    completeSlide(maxSlide)
                } else {
                    resetThumbPosition(animated = true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSliderPosition(targetX: Float, maxSlide: Int) {
        thumbContainer.translationX = targetX
        val progress = if (maxSlide > 0) (targetX / maxSlide.toFloat()).coerceIn(0f, 1f) else 0f

        // Progress fill width
        progressView.layoutParams.width = (targetX + currentThumbSize / 2 + trackMargin).toInt()
        progressView.requestLayout()

        // Fade label as thumb moves across
        labelView.alpha = ((1f - progress * 1.5f) * 0.9f).coerceIn(0f, 0.9f)
    }

    private fun completeSlide(maxSlide: Int) {
        isCompleted = true
        ValueAnimator.ofFloat(thumbContainer.translationX, maxSlide.toFloat()).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                updateSliderPosition(v, maxSlide)
            }
            start()
        }

        performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )

        postDelayed({
            onSlideCompleteListener?.invoke()
        }, 160)
    }

    fun resetSlider(shake: Boolean = false) {
        isCompleted = false
        if (shake) {
            val originalX = 0f
            val shakeAnimator = ValueAnimator.ofFloat(0f, 25f, -20f, 15f, -10f, 5f, 0f).apply {
                duration = 400
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val offset = anim.animatedValue as Float
                    thumbContainer.translationX = originalX + offset
                }
            }
            shakeAnimator.start()
            resetThumbPosition(animated = false)
        } else {
            resetThumbPosition(animated = true)
        }
    }

    private fun resetThumbPosition(animated: Boolean) {
        val currentX = thumbContainer.translationX
        val maxSlide = getMaxSlideDistance()

        if (animated && currentX > 0) {
            ValueAnimator.ofFloat(currentX, 0f).apply {
                duration = 200
                interpolator = OvershootInterpolator(0.8f)
                addUpdateListener { animator ->
                    val v = animator.animatedValue as Float
                    updateSliderPosition(v, maxSlide)
                }
                start()
            }
        } else {
            updateSliderPosition(0f, maxSlide)
        }
    }
}
