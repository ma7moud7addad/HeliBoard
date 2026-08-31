/*
 * Copyright (C) 2024 HeliBoard Contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.macboard.keyboard.keyboard.common

import android.view.MotionEvent
import com.macboard.keyboard.latin.utils.Log

/**
 * PanelDragController - Centralized touch event and drag logic controller.
 *
 * Handles ACTION_DOWN, ACTION_MOVE, and ACTION_UP events on the drag handle,
 * calculates new heights based on drag delta, applies min/max constraints,
 * and updates panel heights smoothly.
 *
 * This controller is shared by both Clipboard and Emoji panels via the
 * ExpandablePanel interface, ensuring consistent behavior across both.
 */
class PanelDragController(
    private val expandablePanel: ExpandablePanel
) {

    companion object {
        private val TAG = PanelDragController::class.simpleName
    }

    // Drag state tracking
    private var startDragY = 0f
    private var initialHeight = 0
    private var isDragging = false

    /**
     * Process a MotionEvent from the drag handle.
     * Dispatches to appropriate action handler based on event type.
     *
     * @param event The MotionEvent to process
     * @return true if the event was consumed, false otherwise
     */
    fun onDragHandleEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onActionDown(event)
            MotionEvent.ACTION_MOVE -> onActionMove(event)
            MotionEvent.ACTION_UP -> onActionUp(event)
            MotionEvent.ACTION_CANCEL -> onActionCancel(event)
            else -> false
        }
    }

    /**
     * Handle ACTION_DOWN - Initialize drag state.
     * Store the initial touch Y coordinate and current panel height.
     */
    private fun onActionDown(event: MotionEvent): Boolean {
        startDragY = event.y
        initialHeight = expandablePanel.getCurrentHeight()
        isDragging = true

        // Notify panel of drag start (for visual feedback, etc.)
        expandablePanel.onDragStarted()

        Log.d(TAG, "Drag started. Initial height: $initialHeight, startY: $startDragY")
        return true
    }

    /**
     * Handle ACTION_MOVE - Update panel height based on drag delta.
     *
     * Calculation:
     * - Negative deltaY (dragging up) = expand (increase height)
     * - Positive deltaY (dragging down) = collapse (decrease height)
     * - Apply min/max constraints to keep panel within valid bounds
     */
    private fun onActionMove(event: MotionEvent): Boolean {
        if (!isDragging) {
            return false
        }

        // Calculate vertical drag distance
        // Negative when dragging up (expand), positive when dragging down (collapse)
        val deltaY = startDragY - event.y

        // Calculate new height: initial height + drag delta
        val newHeight = (initialHeight + deltaY).toInt()

        // Apply min/max constraints
        val constrainedHeight = newHeight.coerceIn(
            expandablePanel.getMinHeight(),
            expandablePanel.getMaxHeight()
        )

        // Update panel height
        expandablePanel.setExpandedHeight(constrainedHeight)

        Log.d(TAG, "Dragging: deltaY=$deltaY, newHeight=$newHeight, constrained=$constrainedHeight")
        return true
    }

    /**
     * Handle ACTION_UP - End drag operation.
     * Notify panel of drag completion for cleanup/state management.
     */
    private fun onActionUp(event: MotionEvent): Boolean {
        if (!isDragging) {
            return false
        }

        isDragging = false
        val finalHeight = expandablePanel.getCurrentHeight()

        // Notify panel of drag end (for visual feedback cleanup, etc.)
        expandablePanel.onDragEnded()

        Log.d(TAG, "Drag ended. Final height: $finalHeight")
        return true
    }

    /**
     * Handle ACTION_CANCEL - End drag operation without user completion.
     * This can occur if the view hierarchy changes or touch is intercepted.
     */
    private fun onActionCancel(event: MotionEvent): Boolean {
        if (!isDragging) {
            return false
        }

        isDragging = false
        expandablePanel.onDragEnded()

        Log.d(TAG, "Drag cancelled")
        return true
    }

    /**
     * Get the current drag state.
     * Useful for preventing other touch interactions during drag.
     *
     * @return true if currently dragging, false otherwise
     */
    fun isDragging(): Boolean = isDragging
}
