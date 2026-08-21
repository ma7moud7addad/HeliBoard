package com.macboard.keyboard.latin

import android.content.ClipDescription
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
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

    fun getContentUri(context: Context): android.net.Uri? {
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

    fun setImageAndDescription(imageView: ImageView, textView: TextView) {
        if (mimeTypes == null || filename == null) return
        try {
            val file = File(imageView.context.filesDir, "clipfiles/$filename")
            val path = file.absolutePath
            val opt = BitmapFactory.Options()
            opt.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opt)
            val scale = opt.outWidth / (imageView.resources.displayMetrics.widthPixels * 2)
            opt.inSampleSize = scale.coerceAtLeast(1)
            opt.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(path, opt)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                textView.text = null
                return
            }
        } catch (e: Exception) {
        }
        // لو الصورة ماتحملتش، اعرض text
        imageView.setImageDrawable(null)
        textView.text = text.take(1000)
    }
}
