package com.resqnet.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.resqnet.app.R

class SpotlightOverlayView(context: Context) : FrameLayout(context) {

    data class SpotlightTarget(
        val targetView: View,
        val title: String,
        val description: String,
        val stepBadge: String? = null,
        val customButtonText: String? = null,
        val onNextClicked: (() -> Unit)? = null
    )

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#CC0B151E") // 80% deep navy scrim
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.palette_amber)
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface_card)
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.color_divider)
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private var targets: List<SpotlightTarget> = emptyList()
    private var currentIndex = 0
    private var onFinishedListener: (() -> Unit)? = null

    private val targetRect = RectF()
    private val arrowPath = Path()
    private var isArrowPointingDown = false
    private var arrowTipX = 0f
    private var arrowTipY = 0f

    private val tooltipView: View
    private val cardView: MaterialCardView
    private val tvStepBadge: TextView
    private val tvTitle: TextView
    private val tvDescription: TextView
    private val btnClose: ImageButton
    private val btnSkip: MaterialButton
    private val btnNext: MaterialButton

    private var currentAnimProgress = 1f

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        tooltipView = LayoutInflater.from(context).inflate(R.layout.view_spotlight_tooltip, this, false)
        cardView = tooltipView.findViewById(R.id.spotlightCard)
        tvStepBadge = tooltipView.findViewById(R.id.spotlightStepBadge)
        tvTitle = tooltipView.findViewById(R.id.spotlightTitle)
        tvDescription = tooltipView.findViewById(R.id.spotlightDescription)
        btnClose = tooltipView.findViewById(R.id.spotlightBtnClose)
        btnSkip = tooltipView.findViewById(R.id.spotlightBtnSkip)
        btnNext = tooltipView.findViewById(R.id.spotlightBtnNext)

        addView(tooltipView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        btnNext.setOnClickListener {
            val currentTarget = targets.getOrNull(currentIndex)
            if (currentTarget?.onNextClicked != null) {
                dismiss()
                currentTarget.onNextClicked.invoke()
            } else if (currentIndex < targets.lastIndex) {
                moveToStep(currentIndex + 1)
            } else {
                dismiss()
            }
        }

        btnSkip.setOnClickListener { dismiss() }
        btnClose.setOnClickListener { dismiss() }

        // Block touches from reaching views below
        isClickable = true
        isFocusable = true
    }

    fun start(targets: List<SpotlightTarget>, onFinished: (() -> Unit)? = null) {
        if (targets.isEmpty()) return
        this.targets = targets
        this.onFinishedListener = onFinished
        this.currentIndex = 0
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
        post {
            showCurrentStep(animate = false)
        }
    }

    private fun moveToStep(index: Int) {
        currentIndex = index
        showCurrentStep(animate = true)
    }

    private fun showCurrentStep(animate: Boolean) {
        if (currentIndex !in targets.indices) return
        val step = targets[currentIndex]
        val target = step.targetView

        // Calculate target rect relative to this overlay
        val overlayLoc = IntArray(2)
        getLocationOnScreen(overlayLoc)

        val targetLoc = IntArray(2)
        target.getLocationOnScreen(targetLoc)

        val density = resources.displayMetrics.density
        val padding = 8f * density
        val cornerRadius = 14f * density

        val left = (targetLoc[0] - overlayLoc[0]).toFloat() - padding
        val top = (targetLoc[1] - overlayLoc[1]).toFloat() - padding
        val right = (targetLoc[0] - overlayLoc[0] + target.width).toFloat() + padding
        val bottom = (targetLoc[1] - overlayLoc[1] + target.height).toFloat() + padding

        targetRect.set(left, top, right, bottom)

        // Populate tooltip content
        val isLast = (currentIndex == targets.lastIndex)
        tvStepBadge.text = step.stepBadge ?: "${currentIndex + 1} of ${targets.size}"
        tvTitle.text = step.title
        tvDescription.text = step.description
        btnNext.text = step.customButtonText ?: if (isLast) "Got it  ✓" else "Next  →"
        btnSkip.visibility = if (isLast) GONE else VISIBLE

        // Measure and size tooltip card compactly
        val maxCardWidth = (330 * density).toInt()
        val actualCardWidth = (width - (48 * density).toInt()).coerceAtMost(maxCardWidth)
        val cardLeft = (width - actualCardWidth) / 2f

        val lp = tooltipView.layoutParams as LayoutParams
        if (lp.width != actualCardWidth) {
            lp.width = actualCardWidth
            tooltipView.layoutParams = lp
        }

        val specW = MeasureSpec.makeMeasureSpec(actualCardWidth, MeasureSpec.EXACTLY)
        val specH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        tooltipView.measure(specW, specH)
        val cardH = tooltipView.measuredHeight

        // Position tooltip above or below target
        val spaceBelow = height - bottom
        val spaceAbove = top
        val arrowH = 10f * density
        val arrowW = 18f * density

        val targetCenterX = targetRect.centerX().coerceIn(cardLeft + 24 * density, cardLeft + actualCardWidth - 24 * density)

        val cardTop: Float
        if (spaceBelow >= cardH + 24 * density || spaceBelow >= spaceAbove) {
            // Place BELOW target
            isArrowPointingDown = false
            cardTop = (bottom + arrowH + 4 * density)
            arrowTipX = targetCenterX
            arrowTipY = bottom + 2 * density

            arrowPath.reset()
            arrowPath.moveTo(arrowTipX, arrowTipY)
            arrowPath.lineTo(arrowTipX - arrowW / 2, cardTop + 2)
            arrowPath.lineTo(arrowTipX + arrowW / 2, cardTop + 2)
            arrowPath.close()
        } else {
            // Place ABOVE target
            isArrowPointingDown = true
            cardTop = (top - cardH - arrowH - 4 * density).coerceAtLeast(16 * density)
            arrowTipX = targetCenterX
            arrowTipY = top - 2 * density

            arrowPath.reset()
            arrowPath.moveTo(arrowTipX, arrowTipY)
            arrowPath.lineTo(arrowTipX - arrowW / 2, cardTop + cardH - 2)
            arrowPath.lineTo(arrowTipX + arrowW / 2, cardTop + cardH - 2)
            arrowPath.close()
        }

        tooltipView.x = cardLeft

        if (animate) {
            tooltipView.animate()
                .y(cardTop)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            tooltipView.y = cardTop
            tooltipView.alpha = 1f
        }

        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (targets.isEmpty() || targetRect.isEmpty) {
            super.dispatchDraw(canvas)
            return
        }

        val density = resources.displayMetrics.density
        val cornerRadius = 14f * density

        // Draw dark background scrim
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Clear cutout hole for target element
        canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, clearPaint)

        // Draw illuminated amber outline around target
        canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, strokePaint)

        // Draw pointer arrow connecting tooltip card to target
        if (!arrowPath.isEmpty) {
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.drawPath(arrowPath, arrowStrokePaint)
        }

        super.dispatchDraw(canvas)
    }

    fun dismiss() {
        animate()
            .alpha(0f)
            .setDuration(180)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (parent as? ViewGroup)?.removeView(this@SpotlightOverlayView)
                    onFinishedListener?.invoke()
                }
            })
            .start()
    }

    companion object {
        fun attach(activity: Activity): SpotlightOverlayView {
            val decorView = activity.window.decorView as ViewGroup
            val existing = decorView.findViewWithTag<SpotlightOverlayView>("SPOTLIGHT_TUTORIAL_TAG")
            if (existing != null) {
                decorView.removeView(existing)
            }
            val overlay = SpotlightOverlayView(activity)
            overlay.tag = "SPOTLIGHT_TUTORIAL_TAG"
            decorView.addView(
                overlay,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            return overlay
        }
    }
}
