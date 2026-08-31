/*
 * Copyright (C) 2024 HeliBoard Contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.macboard.keyboard.keyboard.common

/**
 * ExpandablePanel - Interface for standardized expandable panel behavior.
 *
 * This interface ensures both Clipboard and Emoji panels implement consistent
 * expansion logic and can be managed uniformly by the PanelDragController.
 *
 * Implementations should handle:
 * - Dynamic height updates via LayoutParams
 * - Constraint enforcement (min/max bounds)
 * - Drag lifecycle callbacks for state management
 */
interface ExpandablePanel {

    /**
     * Set the expanded height of the panel.
     * Implementations should apply this height via LayoutParams and requestLayout().
     *
     * @param heightPx The new height in pixels. Should be constrained between
     *                 getMinHeight() and getMaxHeight() by the caller.
     */
    fun setExpandedHeight(heightPx: Int)

    /**
     * Get the minimum height (default/collapsed state) of the panel.
     * Typically equals the standard keyboard height (~60-100dp depending on device).
     *
     * @return Minimum height in pixels
     */
    fun getMinHeight(): Int

    /**
     * Get the maximum height (fully expanded state) of the panel.
     * Typically 70-80% of screen height to ensure IME visibility and usability.
     *
     * @return Maximum height in pixels
     */
    fun getMaxHeight(): Int

    /**
     * Get the current height of the panel.
     * Used to track state during drag operations.
     *
     * @return Current height in pixels
     */
    fun getCurrentHeight(): Int

    /**
     * Called when a drag operation starts on the drag handle.
     * Implementations can use this for:
     * - Visual feedback (highlight, color change)
     * - Disabling scroll on underlying RecyclerView/content
     * - Storing drag start state
     */
    fun onDragStarted()

    /**
     * Called when a drag operation ends on the drag handle.
     * Implementations can use this for:
     * - Removing visual feedback
     * - Re-enabling scroll on underlying content
     * - Persisting final height state (optional)
     * - Smoothly snapping to nearest boundary (optional)
     */
    fun onDragEnded()

    /**
     * Initialize the panel with min/max height constraints.
     * Called during panel creation/setup.
     *
     * @param minHeightPx Minimum height in pixels
     * @param maxHeightPx Maximum height in pixels
     */
    fun initialize(minHeightPx: Int, maxHeightPx: Int)
}
