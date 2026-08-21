package com.macboard.keyboard.latin

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.macboard.keyboard.latin.common.ColorType
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

    @SuppressLint("SetTextI18n")
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
        val description = if (text.isBlank()) ""
            else "\n" + textView.context.getString(R.string.item_description, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val info = imageView.context.contentResolver.getTypeInfo(mimeTypes[0])
                info.icon.setTint(Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY))
                imageView.setImageIcon(info.icon)
                textView.text = textView.context.getString(R.string.item_type, info.label.toString()) + description
                return
            } catch (_: Exception) {}
        }
        imageView.setImageResource(R.drawable.ic_dictionary)
        Settings.getValues().mColors.setColor(imageView, ColorType.EMOJI_CATEGORY)
        textView.text = textView.context.getString(R.string.item_type, mimeTypes.first()) + description
    }
}
