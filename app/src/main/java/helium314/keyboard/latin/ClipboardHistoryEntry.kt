// SPDX-License-Identifier: GPL-3.0-only
package com.macboard.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.ClipDescription
import android.os.Build
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.macboard.keyboard.latin.settings.Settings
import com.macboard.keyboard.latin.common.ColorType
import com.macboard.keyboard.latin.utils.Log
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

    // 🔧 تحميل الصورة بشكل آمن بدون threads
    @SuppressLint("SetTextI18n")
    fun setImageAndDescription(imageView: ImageView, textView: TextView) {
        if (mimeTypes == null || filename == null) return
        try {
            val path = File(imageView.context.filesDir, "clipfiles/$filename").absolutePath
            val opt = BitmapFactory.Options()
            opt.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opt)
            
            // تقليل حجم الصور الكبيرة
            val scale = opt.outWidth / (imageView.resources.displayMetrics.widthPixels * 2)
            opt.inSampleSize = scale
            opt.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(path, opt)
            
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                textView.text = null
                return
            }
        } catch (e: Exception) {
            Log.w("ClipboardHistoryEntry", "could not load image for clip $id", e)
        }
        
        // إذا فشل تحميل الصورة، اعرض النص بدلاً منها
        val description = if (text.isNullOrBlank()) ""
            else "\n" + text
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val info = imageView.context.contentResolver.getTypeInfo(mimeTypes[0])
                info.icon.setTint(Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY))
                imageView.setImageIcon(info.icon)
                textView.text = info.label.toString() + description
                return
            } catch (e: Exception) {
                Log.w("ClipboardHistoryEntry", "could not get type info", e)
            }
        }
        
        imageView.setImageResource(com.macboard.keyboard.latin.R.drawable.ic_dictionary)
        Settings.getValues().mColors.setColor(imageView, ColorType.EMOJI_CATEGORY)
        textView.text = mimeTypes.firstOrNull()?.let { "$it$description" } ?: description
    }
}
