// SPDX-License-Identifier: GPL-3.0-only

package com.macboard.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.macboard.keyboard.latin.ClipboardHistoryEntry
import com.macboard.keyboard.latin.ClipboardHistoryManager
import com.macboard.keyboard.latin.R
import com.macboard.keyboard.latin.common.ColorType
import com.macboard.keyboard.latin.settings.Settings
import kotlinx.coroutines.*
import java.io.File

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setContent(getItem(position))
    }

    private fun getItem(position: Int) = clipboardHistoryManager?.getHistoryEntry(position)

    override fun getItemCount() = clipboardHistoryManager?.getHistorySize() ?: 0

    inner class ViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val thumbnailView: ImageView
        private val contentView: TextView
        
        private var loadingJob: Job? = null
        private var currentItemId: Long? = null

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            thumbnailView = view.findViewById<ImageView>(R.id.clipboard_entry_thumbnail).apply {
                visibility = View.GONE
            }
            contentView = view.findViewById<TextView>(R.id.clipboard_entry_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            clipboardLayoutParams.setItemProperties(view)
            val colors = Settings.getValues().mColors
            colors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)
        }

        fun setContent(historyEntry: ClipboardHistoryEntry?) {
            // إلغاء أي عملية تحميل سابقة
            loadingJob?.cancel()
            loadingJob = null
            
            itemView.tag = historyEntry?.id
            pinnedIconView.visibility = if (historyEntry?.isPinned == true) View.VISIBLE else View.GONE
            
            currentItemId = historyEntry?.id

            val mime = historyEntry?.mimeTypes?.firstOrNull()
            if (historyEntry?.filename != null && mime?.startsWith("image/") == true) {
                // تنظيف الصورة السابقة فوراً
                thumbnailView.setImageBitmap(null)
                thumbnailView.visibility = View.VISIBLE
                contentView.visibility = View.GONE

                val file = File(itemView.context.filesDir, "clipfiles/${historyEntry.filename}")
                val itemId = historyEntry.id
                
                if (file.exists() && file.length() > 0) {
                    // تحميل الصورة باستخدام Coroutines
                    loadingJob = scope.launch {
                        try {
                            val bitmap = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(file.absolutePath)
                            }
                            
                            // التأكد من أن ViewHolder ما زال يعرض نفس العنصر
                            if (currentItemId == itemId && bitmap != null) {
                                thumbnailView.setImageBitmap(bitmap)
                            } else if (currentItemId != itemId) {
                                // تم إعادة استخدام ViewHolder لعنصر آخر، تنظيف الموارد
                                bitmap?.recycle()
                            } else {
                                // فشل التحميل، عرض النص
                                thumbnailView.visibility = View.GONE
                                contentView.visibility = View.VISIBLE
                                contentView.text = historyEntry.text.take(1000)
                            }
                        } catch (e: Exception) {
                            // في حالة الخطأ أو الإلغاء
                            if (isActive && currentItemId == itemId) {
                                thumbnailView.visibility = View.GONE
                                contentView.visibility = View.VISIBLE
                                contentView.text = historyEntry.text.take(1000)
                            }
                        }
                    }
                } else {
                    thumbnailView.visibility = View.GONE
                    contentView.visibility = View.VISIBLE
                    contentView.text = historyEntry.text.take(1000)
                }
            } else {
                // نص عادي بدون صورة
                thumbnailView.visibility = View.GONE
                thumbnailView.setImageBitmap(null)
                contentView.visibility = View.VISIBLE
                contentView.text = historyEntry?.text?.take(1000)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
        }

        override fun onClick(view: View) {
            keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            clipboardHistoryManager?.toggleClipPinned(view.tag as Long)
            return true
        }
    }
    
    override fun onViewRecycled(holder: ViewHolder) {
        // تنظيف الموارد عند إعادة استخدام ViewHolder
        holder.loadingJob?.cancel()
        holder.loadingJob = null
        super.onViewRecycled(holder)
    }
}
