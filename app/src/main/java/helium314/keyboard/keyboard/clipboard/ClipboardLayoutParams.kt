// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

class ClipboardLayoutParams(ctx: Context) {

    private val keyVerticalGap: Int
    private val keyHorizontalGap: Int
    private val listHeight: Int
    val bottomRowKeyboardHeight: Int
    private val bottomPadding: Int // تم تحويله لمتغير عام

    init {
        val res = ctx.resources
        val sv = Settings.getValues()
        val defaultKeyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(res, sv)
        val defaultKeyboardWidth = ResourceUtils.getKeyboardWidth(ctx, sv)

        keyVerticalGap = (res.getFraction(R.fraction.config_key_vertical_gap_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.mKeyGapScale).toInt()
        keyHorizontalGap = (res.getFraction(R.fraction.config_key_horizontal_gap_holo,
            defaultKeyboardWidth, defaultKeyboardWidth) * sv.mKeyGapScale).toInt()
        bottomPadding = (res.getFraction(R.fraction.config_keyboard_bottom_padding_holo,
                defaultKeyboardHeight, defaultKeyboardHeight) * sv.mBottomPaddingScale).toInt()
        val topPadding = res.getFraction(R.fraction.config_keyboard_top_padding_holo,
                defaultKeyboardHeight, defaultKeyboardHeight).toInt()

        val rowCount = KeyboardParams.DEFAULT_KEYBOARD_ROWS + if (sv.mShowsNumberRow) 1 else 0
        bottomRowKeyboardHeight = (((defaultKeyboardHeight - bottomPadding - topPadding) / rowCount - keyVerticalGap / 2) * 0.7).toInt()
        val offset = 1.25f * res.displayMetrics.density * sv.mKeyboardHeightScale
        
        // القائمة تأخذ الارتفاع بالكامل لتصل للحافة السفلية
        listHeight = defaultKeyboardHeight + offset.toInt()
    }

    fun setListProperties(recycler: RecyclerView) {
        (recycler.layoutParams as FrameLayout.LayoutParams).apply {
            height = listHeight
            recycler.layoutParams = this
        }
        recycler.clipToPadding = false
        // تعويض المسافة السفلية (الأزرار + البادينج السفلي)
        recycler.setPadding(0, 0, 0, bottomRowKeyboardHeight + bottomPadding)
    }

    fun setItemProperties(view: View) {
        (view.layoutParams as RecyclerView.LayoutParams).apply {
            topMargin = keyHorizontalGap / 2
            bottomMargin = keyVerticalGap / 2
            marginStart = keyHorizontalGap / 2
            marginEnd = keyHorizontalGap / 2
            view.layoutParams = this
        }
    }
}
