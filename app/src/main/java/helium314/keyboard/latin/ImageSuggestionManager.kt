package helium314.keyboard.latin

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType

class ImageSuggestionManager(private val latinIME: LatinIME) {

    private lateinit var clipboardManager: ClipboardManager
    private var latestImageUri: Uri? = null
    private var dontShowCurrentSuggestion = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onPrimaryClipChanged()
    }

    private val screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            checkForRecentScreenshot()
        }
    }

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        latinIME.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            screenshotObserver
        )
    }

    fun onDestroy() {
        if (::clipboardManager.isInitialized) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        }
        latinIME.contentResolver.unregisterContentObserver(screenshotObserver)
    }

    private fun onPrimaryClipChanged() {
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val description = clipData.description ?: return

        val hasImage = (0 until description.mimeTypeCount).any { i ->
            description.getMimeType(i)?.startsWith("image/") == true
        }
        if (!hasImage) return

        val item = clipData.getItemAt(0) ?: return
        val uri = item.uri ?: return
        latestImageUri = uri
        dontShowCurrentSuggestion = false
        latinIME.setNeutralSuggestionStrip()
    }

    private fun checkForRecentScreenshot() {
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return
        if (!hasMediaPermission()) return

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA
        )

        val timeThreshold = (System.currentTimeMillis() / 1000) - 60
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} > ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} > ?"
        }
        val selectionArgs = arrayOf("%Screenshots%", timeThreshold.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            latinIME.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    latestImageUri = uri
                    dontShowCurrentSuggestion = false
                    latinIME.setNeutralSuggestionStrip()
                }
            }
        } catch (e: Exception) {
            // Ignore permission/query errors
        }
    }

    private fun hasMediaPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ContextCompat.checkSelfPermission(latinIME, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                ContextCompat.checkSelfPermission(latinIME, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }
    }

    fun getImageSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null || editorInfo == null) return null

        val uri = latestImageUri ?: return null

        val mimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        if (!mimeTypes.any { it.startsWith("image/") }) return null

        val view = LayoutInflater.from(latinIME).inflate(R.layout.image_suggestion, parent, false)

        val thumbnailView = view.findViewById<ImageView>(R.id.image_suggestion_thumbnail)
        val textView = view.findViewById<TextView>(R.id.image_suggestion_text)
        val container = view.findViewById<View>(R.id.image_suggestion_container)

        // Make thumbnail circular
        thumbnailView.clipToOutline = true
        thumbnailView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }

        val thumbSize = 144
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                latinIME.contentResolver.loadThumbnail(uri, Size(thumbSize, thumbSize), null)
            } catch (_: Exception) { null }
        } else null

        if (bitmap != null) {
            thumbnailView.setImageBitmap(bitmap)
        } else {
            thumbnailView.setImageURI(uri)
        }

        textView.text = latinIME.getString(R.string.image_suggestion_insert)
        textView.setOnClickListener {
            dontShowCurrentSuggestion = true
            latinIME.commitImage(uri)
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS
            )
            view.visibility = View.GONE
        }

        // Apply theme colors
        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(container, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        return view
    }

    private fun removeImageSuggestion() {
        dontShowCurrentSuggestion = true
        latinIME.setNeutralSuggestionStrip()
        latinIME.mHandler.postResumeSuggestions(false)
    }
}
