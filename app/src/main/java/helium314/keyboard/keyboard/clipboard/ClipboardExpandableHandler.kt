/*
 * Copyright (C) 2024 HeliBoard Contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.macboard.keyboard.keyboard.clipboard

import android.view.View
import android.view.ViewGroup
import com.macboard.keyboard.keyboard.common.DragHandleView
import com.macboard.keyboard.keyboard.common.ExpandablePanel
import com.macboard.keyboard.keyboard.common.PanelDragController
import com.macboard.keyboard.latin.utils.Log

/**
 * ClipboardExpandableHandler - Clipboard panel implementation of ExpandablePanel.
 *
 * Manages the expansion/collapse behavior of the Clipboard panel via the drag handle.
 * Handles:
 * - Dynamic height updates via LayoutParams
 * - Min/max constraint enforcement
 * - Drag lifecycle callbacks (visual feedback, scroll control, etc.)
 *
 * Architecture:
 * - Holds reference to the clipboard panel View
 * - Holds reference to the DragHandleView
 * - Creates and delegates to PanelDragController for drag logic
 * - Updates parent ViewGroup LayoutParams to resize the panel
 */
class ClipboardExpandableHandler(
    private val clipboardPanel: View,
    private val dragHandleView: DragHandleView
) : ExpandablePanel {

    companion object {
        private val TAG = ClipboardExpandableHandler::class.simpleName
    }

    // Height constraints (in pixels)
    private var minHeight = 0
    private var maxHeight = 0
    private var currentHeight = 0

    // Drag controller for handling touch events
    private val dragController = PanelDragController(this)

    // Reference to content view (e.g., RecyclerView in clipboard panel)
    // Used to disable/enable scroll during drag
    private var contentView: View? = null

    /**
     * Initialize the clipboard handler with min/max height constraints.
     *
     * @param minHeightPx Minimum height in pixels (default/collapsed state)
     * @param maxHeightPx Maximum height in pixels (fully expanded state)
     */
    override fun initialize(minHeightPx: Int, maxHeightPx: Int) {
        minHeight = minHeightPx
        maxHeight = maxHeightPx
        currentHeight = minHeightPx

        // Set initial layout params to minimum height
        applyHeightToPanel(minHeight)

        Log.d(TAG, "Initialized: minHeight=$minHeight, maxHeight=$maxHeight")
    }

    /**
     * Set the content view (e.g., RecyclerView) for scroll control during drag.
     * Call this after the clipboard panel is fully initialized.
     *
     * @param content The content view to control
     */
    fun setContentView(content: View) {
        contentView = content
    }

    /**
     * Set up the drag handle touch listener.
     * Call this during panel initialization to enable drag functionality.
     */
    fun setupDragHandle() {
        dragHandleView.setOnTouchListener { _, event ->
            dragController.onDragHandleEvent(event)
        }
    }

    /**
     * Update the panel height via LayoutParams.
     * Triggers a layout pass to apply the new height smoothly.
     *
     * @param heightPx The new height in pixels
     */
    override fun setExpandedHeight(heightPx: Int) {
        if (currentHeight == heightPx) {
            return // No change needed
        }

        currentHeight = heightPx
        applyHeightToPanel(heightPx)
    }

    /**
     * Get the minimum height (default/collapsed state).
     *
     * @return Minimum height in pixels
     */
    override fun getMinHeight(): Int = minHeight

    /**
     * Get the maximum height (fully expanded state).
     *
     * @return Maximum height in pixels
     */
    override fun getMaxHeight(): Int = maxHeight

    /**
     * Get the current height of the panel.
     *
     * @return Current height in pixels
     */
    override fun getCurrentHeight(): Int = currentHeight

    /**
     * Called when a drag operation starts.
     * Used for visual feedback and disabling content scroll.
     */
    override fun onDragStarted() {
        Log.d(TAG, "Drag started on Clipboard panel")

        // Visual feedback on drag handle
        dragHandleView.onDragStarted()

        // Disable scroll on content during drag to prevent competing gestures
        contentView?.isScrollEnabled = false
    }

    /**
     * Called when a drag operation ends.
     * Used for cleanup and restoring content scroll.
     */
    override fun onDragEnded() {
        Log.d(TAG, "Drag ended on Clipboard panel. Final height: $currentHeight")

        // Remove visual feedback from drag handle
        dragHandleView.onDragEnded()

        // Re-enable scroll on content
        contentView?.isScrollEnabled = true
    }

    /**
     * Apply the new height to the clipboard panel via LayoutParams.
     * Updates the parent ViewGroup's layout parameters.
     *
     * @param heightPx The height to apply in pixels
     */
    private fun applyHeightToPanel(heightPx: Int) {
        val layoutParams = clipboardPanel.layoutParams as? ViewGroup.LayoutParams
            ?: return // Cannot update without valid LayoutParams

        layoutParams.height = heightPx
        clipboardPanel.layoutParams = layoutParams

        // Request layout pass to apply changes
        clipboardPanel.post {
            clipboardPanel.requestLayout()
        }

        Log.d(TAG, "Applied height to panel: $heightPx px")
    }
}

/**
 * Extension property to safely get/set scroll enabled on View.
 * Works with RecyclerView and ScrollView.
 */
private var View.isScrollEnabled: Boolean
    get() {
        return when (this) {
            is androidx.recyclerview.widget.RecyclerView -> this.isNestedScrollingEnabled
            else -> true // Default to enabled
        }
    }
    set(value) {
        when (this) {
            is androidx.recyclerview.widget.RecyclerView -> this.isNestedScrollingEnabled = value
        }
    }
