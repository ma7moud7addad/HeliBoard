// SPDX-License-Identifier: GPL-3.0-only

package com.macboard.keyboard.latin

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isGone
import com.macboard.keyboard.keyboard.KeyboardTypeface
import com.macboard.keyboard.compat.ClipboardManagerCompat
import com.macboard.keyboard.event.HapticEvent
import com.macboard.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import com.macboard.keyboard.latin.common.ColorType
import com.macboard.keyboard.latin.common.isValidNumber
import com.macboard.keyboard.latin.database.ClipboardDao
import com.macboard.keyboard.latin.databinding.ClipboardSuggestionBinding
import com.macboard.keyboard.latin.utils.InputTypeUtils
import com.macboard.keyboard.latin.utils.ToolbarKey

class ClipboardHistoryManager(
        val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    var tempPrimaryClip = false // لمنع التكرار اللانهائي عند اللصق

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        if (tempPrimaryClip) return
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    private fun fetchPrimaryClip() {
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        
        // منع حفظ المحتوى الحساس
        if (ClipboardManagerCompat.getClipSensitivity(clipData.description) == true) {
            return
        }
        
        val desc = clipData.description
        val clipItem = clipData.getItemAt(0) ?: return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)

        // لو النص عادي
        if (desc?.hasMimeType("text/*") == true) {
            val content = clipItem.coerceToText(latinIME)
            if (!TextUtils.isEmpty(content)) {
                clipboardDao?.addClip(timeStamp, false, content.toString())
            }
        } 
        // لو صورة أو ملف
        else if (clipItem.uri != null) {
            val mimeTypes = (0 until (desc?.mimeTypeCount ?: 0)).mapNotNull { desc?.getMimeType(it) }
            clipboardDao?.addClipUri(timeStamp, false, latinIME, clipItem.uri, mimeTypes)
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            clipboardDao?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    fun retrieveClipboardContent(): CharSequence {
        val clipData = clipboardManager.primaryClip ?: return ""
        if (clipData.itemCount == 0) return ""
        return clipData.getItemAt(0)?.coerceToText(latinIME) ?: ""
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        clipboardSuggestionView = null

        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0 || clipData.description?.hasMimeType("text/*") == false) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)
        if (TextUtils.isEmpty(content)) return null
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null

        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        KeyboardTypeface.applyToTextView(textView)
        textView.text = (if (isClipSensitive(inputType)) "•".repeat(content.length) else content)
            .take(200)

        textView.setOnClickListener {
            dontShowCurrentSuggestion = true
            latinIME.onTextInput(content.toString())
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private var dontShowCurrentSuggestion: Boolean = false
        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes
    }
}
