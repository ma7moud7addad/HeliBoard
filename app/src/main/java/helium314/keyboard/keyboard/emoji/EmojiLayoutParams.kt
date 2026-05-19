/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

internal class EmojiLayoutParams(res: Resources) {
    private val emojiListBottomMargin: Int
    val emojiKeyboardHeight: Int
    private val emojiCategoryPageIdViewHeight: Int
    val bottomRowKeyboardHeight: Int
    private val bottomPadding: Int // تم تحويله لمتغير عام لاستخدامه في الدوال

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
        val pageIdHeight = res.getDimension(R.dimen.config_emoji_category_page_id_height)
        emojiCategoryPageIdViewHeight = pageIdHeight.toInt()
        val offset = 1.25f * res.displayMetrics.density * sv.mKeyboardHeightScale
        
        // القائمة تأخذ الارتفاع بالكامل لتصل للحافة السفلية
        emojiKeyboardHeight = defaultKeyboardHeight + offset.toInt()
        emojiListBottomMargin = 0
    }

    fun setEmojiListProperties(vp: ViewPager2) {
        val lp = vp.layoutParams as FrameLayout.LayoutParams
        lp.height = emojiKeyboardHeight
        lp.bottomMargin = emojiListBottomMargin
        vp.layoutParams = lp
        
        val recyclerView = vp.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
        recyclerView?.clipToPadding = false
        // تعويض المسافة السفلية (الأزرار + شريط التمرير + البادينج السفلي)
        val totalBottomClearance = bottomRowKeyboardHeight + emojiCategoryPageIdViewHeight + bottomPadding
        recyclerView?.setPadding(0, 0, 0, totalBottomClearance)
    }

    fun setCategoryPageIdViewProperties(v: View) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        lp.height = emojiCategoryPageIdViewHeight
        lp.gravity = android.view.Gravity.BOTTOM
        // رفع شريط التمرير ليكون فوق الأزرار والبادينج السفلي مباشرة
        lp.bottomMargin = bottomRowKeyboardHeight + bottomPadding
        v.layoutParams = lp
    }
}
