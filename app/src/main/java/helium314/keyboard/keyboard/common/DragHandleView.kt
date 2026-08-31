/*
 * Copyright (C) 2024 HeliBoard Contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.macboard.keyboard.keyboard.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.macboard.keyboard.R

/**
 * DragHandleView - A visual indicator for draggable panel expansion.
 *
 * Displays a small horizontal indicator line at the top of expandable panels
 * (Clipboard and Emoji panels) to indicate that the panel can be dragged up
 * to expand or down to collapse.
 *
 * Visual Design:
 * - Small rounded horizontal bar (4-6dp height)
 * - Centered horizontally on the panel
 * - Subtle gradient or semi-transparent appearance
 * - Responsive to touch feedback (optional visual feedback on drag)
 */
class DragHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.drag_handle_color)
        style = Paint.Style.FILL
    }

    private var isDragging = false

    /**
     * Called when a drag operation starts on this handle.
     * Can be used for visual feedback (e.g., color change, scale animation).
     */
    fun onDragStarted() {
        isDragging = true
        invalidate()
    }

    /**
     * Called when a drag operation ends on this handle.
     */
    fun onDragEnded() {
        isDragging = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Update paint color based on drag state for visual feedback
        val color = if (isDragging) {
            ContextCompat.getColor(context, R.color.drag_handle_color_active)
        } else {
            ContextCompat.getColor(context, R.color.drag_handle_color)
        }
        paint.color = color

        // Draw the horizontal drag handle indicator
        // Center it horizontally and position it vertically with padding
        val handleWidth = width * 0.15f // 15% of view width
        val handleHeight = height * 0.5f // 50% of view height (typically 2-3dp)
        val startX = (width - handleWidth) / 2
        val startY = (height - handleHeight) / 2

        // Draw with rounded corners for better visual appearance
        canvas.drawRoundRect(
            startX,
            startY,
            startX + handleWidth,
            startY + handleHeight,
            handleHeight / 2, // Corner radius
            handleHeight / 2,
            paint
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Default height for drag handle: 6dp (provides adequate touch target)
        val desiredHeight = dpToPx(6)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    /**
     * Utility function to convert dp to pixels.
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
