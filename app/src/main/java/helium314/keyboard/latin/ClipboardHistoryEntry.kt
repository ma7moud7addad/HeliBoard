// SPDX-License-Identifier: GPL-3.0-only
package com.macboard.keyboard.latin

import android.content.Context
import android.net.Uri
import android.content.ClipDescription
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.macboard.keyboard.latin.settings.Settings
import java.io.File

class ClipboardHistoryEntry(
    val id: Long,
    var timeStamp: Long,
    var isPinned: Boolean,
    val text: String,
    val filename: String? = null,
    val mimeTypes: List<String>? = null
) : Comparable<ClipboardHistoryEntry> {
    override fun compareTo(other: ClipboardHistoryEntry): Int {
        val result = other.isPinned.compareTo(isPinned)
        if (result == 0) return other.timeStamp.compareTo(timeStamp)
        if (Settings.getValues()?.mClipboardHistoryPinnedFirst == false) return -result
        return result
    }

    fun getContentUri(context: Context): Uri? {
        if (filename == null) return null
        val file = File(context.filesDir, "clipfiles/$filename")
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) {
            null
        }
    }

    fun getContentInfo(context: Context): InputContentInfoCompat? {
        val uri = getContentUri(context) ?: return null
        
        // ✅ تصليح: استخدام أول MIME type صحيح أو اختيار من الامتداد
        val mime = when {
            mimeTypes?.any { it.startsWith("image/") } == true -> 
                mimeTypes.first { it.startsWith("image/") }
            mimeTypes?.isNotEmpty() == true -> mimeTypes.first()
            filename?.endsWith(".jpg") == true -> "image/jpeg"
            filename?.endsWith(".png") == true -> "image/png"
            filename?.endsWith(".gif") == true -> "image/gif"
            filename?.endsWith(".webp") == true -> "image/webp"
            filename?.endsWith(".bmp") == true -> "image/bmp"
            else -> "application/octet-stream"
        }
        
        val desc = ClipDescription("clipboard_content", arrayOf(mime))
        return InputContentInfoCompat(uri, desc, null)
    }
}
