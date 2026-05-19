/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

internal class EmojiLayoutParams(res: Resources) {
    val emojiKeyboardHeight: Int
    val bottomRowKeyboardHeight: Int
    private val bottomPadding: Int

    init {
        val sv = Settings.getValues()
        val defaultKeyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(res, sv)

        val keyVerticalGap = (res.getFraction(R.fraction.config_key_vertical_gap_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.mKeyGapScale).toInt()
        bottomPadding = (res.getFraction(R.fraction.config_keyboard_bottom_padding_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.mBottomPaddingScale).toInt()
        val topPadding = res.getFraction(R.fraction.config_keyboard_top_padding_holo,
            defaultKeyboardHeight, defaultKeyboardHeight).toInt()

        val rowCount = KeyboardParams.DEFAULT_KEYBOARD_ROWS + if (sv.mShowsNumberRow) 1 else 0
        bottomRowKeyboardHeight = (((defaultKeyboardHeight - bottomPadding - topPadding) / rowCount - keyVerticalGap / 2) * 0.7).toInt()
        
        val offset = 1.25f * res.displayMetrics.density * sv.mKeyboardHeightScale
        
        emojiKeyboardHeight = defaultKeyboardHeight + offset.toInt()
    }

    fun setEmojiListProperties(vp: ViewPager2) {
        val lp = vp.layoutParams as LinearLayout.LayoutParams
        lp.height = emojiKeyboardHeight
        vp.layoutParams = lp
        
        val recyclerView = vp.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
        recyclerView?.clipToPadding = false
        recyclerView?.setPadding(0, 0, 0, bottomRowKeyboardHeight + bottomPadding)

        // إزالة الفراغ السفلي من الحاوية الرئيسية ونقله للأزرار
        try {
            val parentLayout = vp.parent as? View
            val frameLayout = parentLayout?.parent as? FrameLayout
            val root = frameLayout?.parent as? View
            
            root?.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
            
            val bottomRow = frameLayout?.findViewById<View>(R.id.bottom_row_keyboard)
            if (bottomRow != null) {
                val bottomRowLp = bottomRow.layoutParams as FrameLayout.LayoutParams
                bottomRowLp.bottomMargin = bottomPadding
                bottomRow.layoutParams = bottomRowLp
            }
        } catch (e: Exception) {}
    }

    fun setCategoryPageIdViewProperties(v: View) {
    }
}
