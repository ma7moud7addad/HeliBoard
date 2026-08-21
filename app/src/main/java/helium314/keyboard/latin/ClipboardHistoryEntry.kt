// SPDX-License-Identifier: GPL-3.0-only
package com.macboard.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 🚀 تحميل الصورة بدون Lag باستخدام Coroutines
    @SuppressLint("SetTextI18n")
    fun setImageAndDescription(imageView: ImageView, textView: TextView) {
        if (mimeTypes == null || filename == null) return
        
        // تخزين معرّف الـ clip الحالي لتجنب تحديثات قديمة
        val currentClipId = id
        
        // تحميل الصورة في background thread
        GlobalScope.launch(Dispatchers.Default) {
            try {
                val bitmap = loadBitmapFromFile(imageView.context)
                
                // تحديث الـ UI في main thread فقط إذا كان هذا هو الـ clip الحالي
                withContext(Dispatchers.Main) {
                    // تحقق من أن الـ ViewHolder لم يتم إعادة استخدامه لـ clip مختلف
                    if (imageView.tag == currentClipId && bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        textView.text = null
                    }
                }
            } catch (e: Exception) {
                Log.w("ClipboardHistoryEntry", "could not load image for clip $id", e)
                
                // عرض fallback في case الفشل
                withContext(Dispatchers.Main) {
                    if (imageView.tag == currentClipId) {
                        showFallbackContent(imageView, textView)
                    }
                }
            }
        }
    }
    
    // 🔧 تحميل الصورة من الـ disk بدون blocking UI thread
    private fun loadBitmapFromFile(context: Context): Bitmap? {
        val path = File(context.filesDir, "clipfiles/$filename").absolutePath
        val opt = BitmapFactory.Options()
        opt.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, opt)
        
        // تقليل حجم الصور الكبيرة
        val scale = opt.outWidth / (256 * 2) // fixed size بدلاً من DisplayMetrics
        opt.inSampleSize = scale
        opt.inJustDecodeBounds = false
        
        return BitmapFactory.decodeFile(path, opt)
    }
    
    // 🎨 عرض محتوى بديل عند فشل تحميل الصورة
    private fun showFallbackContent(imageView: ImageView, textView: TextView) {
        val description = if (text.isNullOrBlank()) ""
            else "\n" + text
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val info = imageView.context.contentResolver.getTypeInfo(mimeTypes?.firstOrNull() ?: "")
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
        textView.text = mimeTypes?.firstOrNull()?.let { "$it$description" } ?: description
    }
}
