/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package com.macboard.keyboard.latin.utils

import android.content.Context
import android.text.TextUtils
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import com.macboard.keyboard.latin.dictionary.Dictionary
import com.macboard.keyboard.latin.common.FileUtils
import com.macboard.keyboard.latin.common.LocaleUtils.constructLocale
import com.macboard.keyboard.latin.common.loopOverCodePoints
import com.macboard.keyboard.latin.define.DecoderSpecificConstants
import com.macboard.keyboard.latin.makedict.DictionaryHeader
import com.macboard.keyboard.latin.makedict.UnsupportedFormatException
import com.macboard.keyboard.latin.settings.SpacingAndPunctuations
import java.io.File
import java.io.IOException
import java.util.Locale

object DictionaryInfoUtils {
    private val TAG = DictionaryInfoUtils::class.java.simpleName
    const val DEFAULT_MAIN_DICT = "main"
    const val USER_DICTIONARY_SUFFIX = "user.dict"
    const val MAIN_DICT_PREFIX = DEFAULT_MAIN_DICT + "_"
    const val ASSETS_DICTIONARY_FOLDER = "dicts"
    const val MAIN_DICT_FILE_NAME = "$DEFAULT_MAIN_DICT.dict"
    private const val MAX_HEX_DIGITS_FOR_CODEPOINT = 6

    private fun isFileNameCharacter(codePoint: Int): Boolean {
        if (codePoint in 0x30..0x39) return true
        if (codePoint in 0x41..0x5A) return true
        if (codePoint in 0x61..0x7A) return true
        return codePoint == '_'.code || codePoint == '-'.code
    }

    private fun replaceFileNameDangerousCharacters(name: String): String {
        val sb = StringBuilder()
        loopOverCodePoints(name) { codePoint, _ ->
            if (isFileNameCharacter(codePoint)) {
                sb.appendCodePoint(codePoint)
            } else {
                sb.append(String.format(Locale.US, "%%%1$0" + MAX_HEX_DIGITS_FOR_CODEPOINT + "x", codePoint))
            }
            false
        }
        return sb.toString()
    }

    fun getWordListCacheDirectory(context: Context): String = context.filesDir?.toString() + File.separator + "dicts"

    fun getWordListIdFromFileName(fname: String): String {
        val sb = StringBuilder()
        val fnameLength = fname.length
        var i = 0
        while (i < fnameLength) {
            val codePoint = fname.codePointAt(i)
            if ('%'.code != codePoint) {
                sb.appendCodePoint(codePoint)
            } else {
                val encodedCodePoint = fname.substring(i + 1, i + 1 + MAX_HEX_DIGITS_FOR_CODEPOINT).toInt(16)
                i += MAX_HEX_DIGITS_FOR_CODEPOINT
                sb.appendCodePoint(encodedCodePoint)
            }
            i = fname.offsetByCodePoints(i, 1)
        }
        return sb.toString()
    }

    fun getCacheDirectories(context: Context) = File(getWordListCacheDirectory(context)).listFiles()
        ?.filter { it.isDirectory && !it.list().isNullOrEmpty() }.orEmpty()

    fun getCacheDirectoryForLocale(locale: Locale, context: Context): String? {
        val relativeDirectoryName = replaceFileNameDangerousCharacters(locale.toLanguageTag())
        val absoluteDirectoryName = getWordListCacheDirectory(context) + File.separator + relativeDirectoryName
        val directory = File(absoluteDirectoryName)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Could not create the directory for locale $locale")
            return null
        }
        return absoluteDirectoryName
    }

    @JvmStatic
    fun getLocalesWithEmojiDicts(context: Context): List<Locale> =
        SubtypeSettings.getEnabledSubtypes(true)
            .map { it.locale() }.filter { getCachedDictForLocaleAndType(it, Dictionary.TYPE_EMOJI, context) != null }

    @JvmStatic
    fun getCachedDictForLocaleAndType(locale: Locale, type: String, context: Context): File? =
        getCachedDictsForLocale(locale, context).firstOrNull { it.name.substringBefore("_") == type }

    fun getCachedDictsForLocale(locale: Locale, context: Context) =
        getCacheDirectoryForLocale(locale, context)?.let { File(it).listFiles() }.orEmpty()

    fun getDictionaryFileHeaderOrNull(file: File): DictionaryHeader? {
        return try {
            BinaryDictionaryUtils.getHeader(file)
        } catch (_: UnsupportedFormatException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    fun extractLocaleFromAssetsDictionaryFile(dictionaryFileName: String): Locale {
        if (dictionaryFileName.contains('_') && !dictionaryFileName.contains('.'))
            throw IllegalStateException("invalid asset dictionary name $dictionaryFileName")
        return dictionaryFileName.substringAfter("_").substringBefore(".").constructLocale()
    }

    fun extractAssetsDictionary(dictionaryFileName: String, locale: Locale, context: Context): File? {
        val cacheDir = getCacheDirectoryForLocale(locale, context) ?: return null
        val targetFile = File(cacheDir, "${dictionaryFileName.substringBefore("_")}.dict")
        try {
            FileUtils.copyStreamToNewFile(
                context.assets.open(ASSETS_DICTIONARY_FOLDER + File.separator + dictionaryFileName),
                targetFile
            )
        } catch (e: IOException) {
            Log.e(TAG, "Could not extract assets dictionary $dictionaryFileName", e)
            return null
        }
        return targetFile
    }

    fun getAssetsDictionaryList(context: Context): Array<String>? {
        return arrayOf(
            "main_ar.dict",
            "main_en.dict",
            "emoji_ar.dict",
            "emoji_en.dict"
        )
    }

    @JvmStatic
    fun looksValidForDictionaryInsertion(text: CharSequence, spacingAndPunctuations: SpacingAndPunctuations): Boolean {
        if (TextUtils.isEmpty(text)) {
            return false
        }
        if (text.length > DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH) {
            return false
        }
        var digitCount = 0
        loopOverCodePoints(text) { codePoint, charCount ->
            if (Character.isDigit(codePoint)) {
                digitCount += charCount
                return@loopOverCodePoints false
            }
            if (!spacingAndPunctuations.isWordCodePoint(codePoint)) {
                return false
            }
            false
        }
        return digitCount < text.length
    }
}
