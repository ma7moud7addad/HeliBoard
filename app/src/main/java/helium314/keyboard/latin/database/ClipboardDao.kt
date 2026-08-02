// SPDX-License-Identifier: GPL-3.0-only
package com.macboard.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import com.macboard.keyboard.latin.ClipboardHistoryEntry
import com.macboard.keyboard.latin.settings.Settings
import com.macboard.keyboard.latin.utils.Log
import com.macboard.keyboard.latin.utils.prefs
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class ClipboardDao private constructor(private val db: Database) {
    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
    }

    var listener: Listener? = null

    private var lastClearOldClips = 0L

    // cache is loaded at start and never dropped
    private val cache = mutableListOf<ClipboardHistoryEntry>().apply {
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT, COLUMN_FILE, COLUMN_MIME_TYPES),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED, $COLUMN_TIMESTAMP DESC"
        ).use {
            while (it.moveToNext()) {
                val file = if (it.isNull(4)) null else it.getString(4)
                val mime = if (it.isNull(5)) null else it.getString(5)
                add(ClipboardHistoryEntry(
                    it.getLong(0), 
                    it.getLong(1), 
                    it.getInt(2) != 0, 
                    it.getString(3) ?: "", 
                    file, 
                    mime?.split("§")?.filter { m -> m.isNotEmpty() }
                ))
            }
        }
        sort()
    }

    fun addClip(timestamp: Long, pinned: Boolean, text: String) {
        clearOldClips()
        val existingIndex = cache.indexOfFirst { it.text == text && it.filename == null }
        if (existingIndex >= 0 && cache[existingIndex].timeStamp == timestamp)
            return // nothing to do
        if (existingIndex >= 0) {
            updateTimestampAt(existingIndex, timestamp)
            return
        }
        insertNewEntry(timestamp, pinned, text)
    }

    fun addClipUri(timestamp: Long, pinned: Boolean, context: Context, uri: Uri, mimeTypes: List<String>) {
        clearOldClips()
        val prefs = context.prefs()
        if (!prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, true)) return
        val maxMb = prefs.getInt(Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, 10)

        var size: Long? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = c.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "error checking clip size", e)
        }

        if (size != null && size!! > maxMb * 1024L * 1024L) return

        val input = try {
            context.contentResolver.openInputStream(uri) ?: return
        } catch (e: Exception) {
            Log.w(TAG, "error opening input stream", e)
            return
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val outDir = File(context.filesDir, "clipfiles")
        if (!outDir.exists()) outDir.mkdirs()

        val buffer = ByteArray(8192)
        val temp = ByteArrayOutputStream()
        var read: Int
        try {
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
                temp.write(buffer, 0, read)
                if (maxMb > 0 && temp.size() > maxMb * 1024L * 1024L) {
                    input.close()
                    return
                }
            }
            input.close()
        } catch (e: Exception) {
            Log.w(TAG, "error reading/writing stream", e)
            return
        }

        val filename = digest.digest().joinToString("") { "%02x".format(it) }
        val dest = File(outDir, filename)
        if (!dest.exists()) {
            try {
                dest.writeBytes(temp.toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "error saving file", e)
                return
            }
        }

        val existingIndex = cache.indexOfFirst { it.filename == filename }
        if (existingIndex >= 0) {
            if (cache[existingIndex].timeStamp != timestamp) {
                updateTimestampAt(existingIndex, timestamp)
            }
            return
        }

        val mimeJoined = mimeTypes.joinToString("§")
        insertNewEntry(timestamp, pinned, "", filename, mimeJoined)
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String?, filename: String? = null, mimeTypesJoined: String? = null) {
        val cv = ContentValues(5)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        cv.put(COLUMN_PINNED, pinned)
        cv.put(COLUMN_TEXT, text ?: "")
        cv.put(COLUMN_FILE, filename)
        cv.put(COLUMN_MIME_TYPES, mimeTypesJoined)
        val rowId = db.writableDatabase.insert(TABLE, null, cv)

        val entry = ClipboardHistoryEntry(rowId, timestamp, pinned, text ?: "", filename, mimeTypesJoined?.split("§")?.filter { it.isNotEmpty() })
        cache.add(entry)
        cache.sort()
        listener?.onClipInserted(cache.indexOf(entry))
    }

    private fun updateTimestampAt(index: Int, timestamp: Long) {
        val entry = cache[index]
        entry.timeStamp = timestamp
        cache.sort()
        listener?.onClipMoved(index, cache.indexOf(entry))
        val cv = ContentValues(1)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    fun isPinned(index: Int) = cache[index].isPinned

    fun getAt(index: Int) = cache[index]

    fun get(id: Long) = cache.first { it.id == id }

    fun count() = cache.size

    fun sort() = cache.sort()

    fun togglePinned(id: Long) {
        val entry = cache.first { it.id == id }
        entry.isPinned = !entry.isPinned
        entry.timeStamp = System.currentTimeMillis()
        if (listener != null) {
            val oldPos = cache.indexOf(entry)
            cache.sort()
            val newPos = cache.indexOf(entry)
            listener?.onClipMoved(oldPos, newPos)
        } else {
            cache.sort()
        }
        val cv = ContentValues(2)
        cv.put(COLUMN_PINNED, entry.isPinned)
        cv.put(COLUMN_TIMESTAMP, entry.timeStamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    fun deleteClipAt(index: Int) {
        val entry = cache[index]
        cache.remove(entry)
        db.writableDatabase.delete(TABLE, "$COLUMN_ID = ${entry.id}", null)
    }

    fun clearOldClips(now: Boolean = false) {
        if (listener != null)
            return 
        if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5 * 1000)
            return

        lastClearOldClips = SystemClock.elapsedRealtime()
        val retentionTime = Settings.getValues()?.mClipboardHistoryRetentionTime ?: 121L
        if (retentionTime > 120) return
        val minTime = System.currentTimeMillis() - retentionTime * 60 * 1000L
        if (!cache.removeAll { it.timeStamp < minTime && !it.isPinned })
            return 

        db.writableDatabase.delete(TABLE, "$COLUMN_TIMESTAMP < $minTime AND $COLUMN_PINNED = 0", null)
    }

    fun clearNonPinned() {
        if (listener != null) {
            val indicesToRemove = mutableListOf<Int>()
            cache.forEachIndexed { idx, clip ->
                if (!clip.isPinned)
                    indicesToRemove.add(idx)
            }
            if (indicesToRemove.isEmpty())
                return 
            cache.removeAll { !it.isPinned }
            listener?.onClipsRemoved(indicesToRemove[0], indicesToRemove.size)
        } else if (!cache.removeAll { !it.isPinned }) {
            return 
        }
        db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
    }

    fun clear() {
        if (count() == 0) return
        cache.clear()
        listener?.onClipsRemoved(0, count())
        db.writableDatabase.delete(TABLE, null, null)
    }

    fun cleanupFiles(prefs: SharedPreferences) {
        // Dummy implementation to satisfy PreferencesScreen.kt
    }

    companion object {
        private const val TAG = "ClipboardDao"

        private const val TABLE = "CLIPBOARD"
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT" 
        private const val COLUMN_FILE = "FILE"
        private const val COLUMN_MIME_TYPES = "MIME_TYPES"

        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_FILE TEXT,
                $COLUMN_MIME_TYPES TEXT
            )
        """

        private var instance: ClipboardDao? = null

        fun getInstance(context: Context): ClipboardDao? {
            if (instance == null)
                try {
                    instance = ClipboardDao(Database.getInstance(context))
                } catch (e: Throwable) {
                    Log.e(TAG, "can't create ClipboardDao", e)
                }
            return instance
        }
    }
}
