/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.macboard.keyboard.latin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.CharacterStyle;

import com.macboard.keyboard.keyboard.KeyboardSwitcher;
import com.macboard.keyboard.latin.common.ConstantsKt;
import com.macboard.keyboard.latin.define.DebugFlags;
import com.macboard.keyboard.latin.settings.Settings;
import com.macboard.keyboard.latin.utils.Log;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.macboard.keyboard.latin.common.Constants;
import com.macboard.keyboard.latin.common.StringUtils;
import com.macboard.keyboard.latin.common.StringUtilsKt;
import com.macboard.keyboard.latin.common.UnicodeSurrogate;
import com.macboard.keyboard.latin.inputlogic.PrivateCommandPerformer;
import com.macboard.keyboard.latin.settings.SpacingAndPunctuations;
import com.macboard.keyboard.latin.utils.CapsModeUtils;
import com.macboard.keyboard.latin.utils.DebugLogUtils;
import com.macboard.keyboard.latin.utils.NgramContextUtils;
import com.macboard.keyboard.latin.utils.StatsUtils;
import com.macboard.keyboard.latin.utils.TextRange;

import java.util.concurrent.TimeUnit;

public final class RichInputConnection implements PrivateCommandPerformer {
    private static final String TAG = "RichInputConnection";
    private static final boolean DBG = false;
    private static final boolean DEBUG_PREVIOUS_TEXT = false;
    private static final boolean DEBUG_BATCH_NESTING = false;
    private static final int NUM_CHARS_TO_GET_BEFORE_CURSOR = 40;
    private static final int NUM_CHARS_TO_GET_AFTER_CURSOR = 40;
    private static final int INVALID_CURSOR_POSITION = -1;

    private static final long SLOW_INPUT_CONNECTION_ON_FULL_RELOAD_MS = 1000;
    private static final long SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS = 200;

    private static final int OPERATION_GET_TEXT_BEFORE_CURSOR = 0;
    private static final int OPERATION_GET_TEXT_AFTER_CURSOR = 1;
    private static final int OPERATION_GET_WORD_RANGE_AT_CURSOR = 2;
    private static final int OPERATION_RELOAD_TEXT_CACHE = 3;
    private static final String[] OPERATION_NAMES = new String[] {
            "GET_TEXT_BEFORE_CURSOR",
            "GET_TEXT_AFTER_CURSOR",
            "GET_WORD_RANGE_AT_CURSOR",
            "RELOAD_TEXT_CACHE"};

    private static final long SLOW_INPUTCONNECTION_PERSIST_MS = TimeUnit.MINUTES.toMillis(10);

    private int mExpectedSelStart = INVALID_CURSOR_POSITION; 
    private int mExpectedSelEnd = INVALID_CURSOR_POSITION; 
    private final StringBuilder mCommittedTextBeforeComposingText = new StringBuilder();
    private final StringBuilder mComposingText = new StringBuilder();
    private final SpannableStringBuilder mTempObjectForCommitText = new SpannableStringBuilder();

    private final InputMethodService mParent;
    private InputConnection mIC;
    private int mNestLevel;
    private long mLastSlowInputConnectionTime = -SLOW_INPUTCONNECTION_PERSIST_MS;

    public RichInputConnection(final InputMethodService parent) {
        mParent = parent;
        mIC = null;
        mNestLevel = 0;
    }

    public boolean isConnected() {
        return mIC != null;
    }

    public boolean hasSlowInputConnection() {
        return (SystemClock.uptimeMillis() - mLastSlowInputConnectionTime)
                        <= SLOW_INPUTCONNECTION_PERSIST_MS;
    }

    public void onStartInput() {
        mLastSlowInputConnectionTime = -SLOW_INPUTCONNECTION_PERSIST_MS;
    }

    private void checkConsistencyForDebug() {
        final ExtractedTextRequest r = new ExtractedTextRequest();
        r.hintMaxChars = 0;
        r.hintMaxLines = 0;
        r.token = 1;
        r.flags = 0;
        final ExtractedText et = mIC.getExtractedText(r, 0);
        final CharSequence beforeCursor = getTextBeforeCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE,
                0);
        final StringBuilder internal = new StringBuilder(mCommittedTextBeforeComposingText)
                .append(mComposingText);
        if (null == et || null == beforeCursor) return;
        final int actualLength = Math.min(beforeCursor.length(), internal.length());
        if (internal.length() > actualLength) {
            internal.delete(0, internal.length() - actualLength);
        }
        final String reference = (beforeCursor.length() <= actualLength) ? beforeCursor.toString()
                : beforeCursor.subSequence(beforeCursor.length() - actualLength,
                        beforeCursor.length()).toString();
        if (et.selectionStart != mExpectedSelStart
                || !(reference.equals(internal.toString()))) {
            final String context = "Expected selection start = " + mExpectedSelStart
                    + "\nActual selection start = " + et.selectionStart
                    + "\nExpected text = " + internal.length() + " " + internal
                    + "\nActual text = " + reference.length() + " " + reference;
            ((LatinIME)mParent).debugDumpStateAndCrashWithException(context);
        } else {
            Log.e(TAG, DebugLogUtils.getStackTrace(2));
            Log.e(TAG, "Exp <> Actual : " + mExpectedSelStart + " <> " + et.selectionStart);
        }
    }

    public void beginBatchEdit() {
        if (++mNestLevel == 1) {
            mIC = mParent.getCurrentInputConnection();
            if (isConnected()) {
                mIC.beginBatchEdit();
            }
        } else {
            if (DBG) {
                throw new RuntimeException("Nest level too deep");
            }
            Log.e(TAG, "Nest level too deep : " + mNestLevel);
        }
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
    }

    public void endBatchEdit() {
        if (mNestLevel <= 0) Log.e(TAG, "Batch edit not in progress!"); 
        if (--mNestLevel == 0 && isConnected()) {
            mIC.endBatchEdit();
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
    }

    public boolean resetCachesUponCursorMoveAndReturnSuccess(final int newSelStart,
            final int newSelEnd, final boolean shouldFinishComposition) {
        mComposingText.setLength(0);
        final boolean didReloadTextSuccessfully = reloadTextCache();
        if (!didReloadTextSuccessfully) {
            Log.d(TAG, "Will try to retrieve text later.");
            return false;
        }
        if (mExpectedSelStart != newSelStart || mExpectedSelEnd != newSelEnd) {
            mExpectedSelStart = newSelStart;
            mExpectedSelEnd = newSelEnd;
            reloadTextCache();
            if (mExpectedSelStart != newSelStart || mExpectedSelEnd != newSelEnd) {
                Log.i(TAG, "resetCachesUponCursorMove: tried to set "+newSelStart+"/"+newSelEnd+", but input field has "+mExpectedSelStart+"/"+mExpectedSelEnd);
            }
        }
        if (isConnected() && shouldFinishComposition) {
            mIC.finishComposingText();
        }
        return true;
    }

    private boolean reloadTextCache() {
        mCommittedTextBeforeComposingText.setLength(0);
        mComposingText.setLength(0);
        mIC = mParent.getCurrentInputConnection();
        final CharSequence textBeforeCursor = getTextBeforeCursorAndDetectLaggyConnection(
                OPERATION_RELOAD_TEXT_CACHE,
                SLOW_INPUT_CONNECTION_ON_FULL_RELOAD_MS,
                Constants.EDITOR_CONTENTS_CACHE_SIZE,
                0 /* flags */);
        if (null == textBeforeCursor) {
            mExpectedSelStart = INVALID_CURSOR_POSITION;
            mExpectedSelEnd = INVALID_CURSOR_POSITION;
            Log.e(TAG, "Unable to connect to the editor to retrieve text.");
            return false;
        }
        mCommittedTextBeforeComposingText.append(textBeforeCursor);
        return true;
    }

    private void reloadCursorPosition() {
        if (!isConnected()) return;
        final ExtractedText et = mIC.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null) return;
        mExpectedSelStart = et.selectionStart + et.startOffset;
        mExpectedSelEnd = et.selectionEnd + et.startOffset;
    }

    private void checkBatchEdit() {
        if (mNestLevel != 1) {
            Log.e(TAG, "Batch edit level incorrect : " + mNestLevel);
            Log.e(TAG, DebugLogUtils.getStackTrace(4));
        }
    }

    public void finishComposingText() {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        mCommittedTextBeforeComposingText.append(mComposingText);
        mComposingText.setLength(0);
        if (isConnected()) {
            mIC.finishComposingText();
        }
    }

    public void commitCodePoint(final int codePoint) {
        commitText(StringUtils.newSingleCodePointString(codePoint), 1);
    }

    public void commitText(final CharSequence text, final int newCursorPosition) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "committing "+text.length()+" characters");
        mCommittedTextBeforeComposingText.append(text);
        mExpectedSelStart += text.length() - mComposingText.length();
        mExpectedSelEnd = mExpectedSelStart;
        mComposingText.setLength(0);
        if (isConnected()) {
            mTempObjectForCommitText.clear();
            mTempObjectForCommitText.append(text);
            final CharacterStyle[] spans = mTempObjectForCommitText.getSpans(
                    0, text.length(), CharacterStyle.class);
            for (final CharacterStyle span : spans) {
                final int spanStart = mTempObjectForCommitText.getSpanStart(span);
                final int spanEnd = mTempObjectForCommitText.getSpanEnd(span);
                final int spanFlags = mTempObjectForCommitText.getSpanFlags(span);
                if (0 < spanEnd && spanEnd < mTempObjectForCommitText.length()) {
                    final char spanEndChar = mTempObjectForCommitText.charAt(spanEnd - 1);
                    final char nextChar = mTempObjectForCommitText.charAt(spanEnd);
                    if (UnicodeSurrogate.isLowSurrogate(spanEndChar)
                            && UnicodeSurrogate.isHighSurrogate(nextChar)) {
                        mTempObjectForCommitText.setSpan(span, spanStart, spanEnd + 1, spanFlags);
                    }
                }
            }
            mIC.commitText(mTempObjectForCommitText, newCursorPosition);
        }
    }

    @Nullable
    public CharSequence getSelectedText(final int flags) {
        return isConnected() ?  mIC.getSelectedText(flags) : null;
    }

    public boolean canDeleteCharacters() {
        return mExpectedSelStart > 0;
    }

    public boolean hasTextAfterCursor() {
        final CharSequence after = getTextAfterCursor(1, 0);
        return !TextUtils.isEmpty(after);
    }

    public int getCursorCapsMode(final int inputType,
            final SpacingAndPunctuations spacingAndPunctuations, final boolean hasSpaceBefore) {
        mIC = mParent.getCurrentInputConnection();
        if (!isConnected()) {
            return Constants.TextUtils.CAP_MODE_OFF;
        }
        if (!TextUtils.isEmpty(mComposingText)) {
            if (hasSpaceBefore) {
                return (TextUtils.CAP_MODE_CHARACTERS | TextUtils.CAP_MODE_WORDS) & inputType;
            }
            return TextUtils.CAP_MODE_CHARACTERS & inputType;
        }
        if (TextUtils.isEmpty(mCommittedTextBeforeComposingText) && 0 != mExpectedSelStart) {
            if (!reloadTextCache()) {
                Log.w(TAG, "Unable to connect to the editor. "
                        + "Setting caps mode without knowing text.");
            }
        }
        return CapsModeUtils.getCapsMode(mCommittedTextBeforeComposingText.toString(), inputType,
                spacingAndPunctuations, hasSpaceBefore);
    }

    public int getCodePointBeforeCursor() {
        final CharSequence text = mComposingText.length() == 0 ? mCommittedTextBeforeComposingText : mComposingText;
        final int length = text.length();
        if (length < 1) return Constants.NOT_A_CODE;
        return Character.codePointBefore(text, length);
    }

    public int getCharBeforeBeforeCursor() {
        if (mComposingText.length() >= 2) return mComposingText.charAt(mComposingText.length() - 2);
        final int length = mCommittedTextBeforeComposingText.length();
        if (mComposingText.length() == 1) {
            if (length < 1) return Constants.NOT_A_CODE;
            return mCommittedTextBeforeComposingText.charAt(length - 1);
        }
        if (length < 2) return Constants.NOT_A_CODE;
        return mCommittedTextBeforeComposingText.charAt(length - 2);
    }

    @Nullable public CharSequence getTextBeforeCursor(final int n, final int flags) {
        final int cachedLength = mCommittedTextBeforeComposingText.length() + mComposingText.length();
        if (INVALID_CURSOR_POSITION != mExpectedSelStart
                && (cachedLength >= n || cachedLength >= mExpectedSelStart)) {
            final StringBuilder s = new StringBuilder(mCommittedTextBeforeComposingText.toString());
            s.append(mComposingText.toString());
            if (s.length() > n) {
                s.delete(0, s.length() - n);
            }
            return s;
        }
        return getTextBeforeCursorAndDetectLaggyConnection(
                OPERATION_GET_TEXT_BEFORE_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                n, flags);
    }

    @Nullable private CharSequence getTextBeforeCursorAndDetectLaggyConnection(
            final int operation, final long timeout, final int n, final int flags) {
        mIC = mParent.getCurrentInputConnection();
        if (!isConnected()) {
            return null;
        }
        final long startTime = SystemClock.uptimeMillis();
        final CharSequence result = mIC.getTextBeforeCursor(n, flags);
        detectLaggyConnection(operation, timeout, startTime);

        if ((mCommittedTextBeforeComposingText.length() > 0 || mComposingText.length() > 0)
                && result != null && !checkTextBeforeCursorConsistency(result)) {
            Log.w(TAG, "cached text out of sync, reloading");
            reloadCursorPosition();
            reloadTextCache();
        }
        return result;
    }

    private boolean checkTextBeforeCursorConsistency(final CharSequence textField) {
        final int lastIndex = textField.length() - 1;
        if (lastIndex == -1) return true;
        final char lastChar = textField.charAt(lastIndex);
        final int composingLength = mComposingText.length();
        for (int i = 0; i <= lastIndex; i++) {
            final char currentTextFieldChar = textField.charAt(lastIndex - i);
            final char currentCachedChar;
            if (i < composingLength) {
                currentCachedChar = mComposingText.charAt(composingLength - 1 - i);
            } else {
                final int index = mCommittedTextBeforeComposingText.length() - 1 - (i - composingLength);
                if (index < mCommittedTextBeforeComposingText.length() && index >= 0)
                    currentCachedChar = mCommittedTextBeforeComposingText.charAt(index);
                else return lastIndex > 100; 
            }

            if (currentTextFieldChar != currentCachedChar)
                return false;

            if (lastChar != currentTextFieldChar)
                return true;
        }
        return true; 
    }

    @Nullable public CharSequence getTextAfterCursor(final int n, final int flags) {
        return getTextAfterCursorAndDetectLaggyConnection(
                OPERATION_GET_TEXT_AFTER_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                n, flags);
    }

    @Nullable private CharSequence getTextAfterCursorAndDetectLaggyConnection(
            final int operation, final long timeout, final int n, final int flags) {
        mIC = mParent.getCurrentInputConnection();
        if (!isConnected()) {
            return null;
        }
        final long startTime = SystemClock.uptimeMillis();
        final CharSequence result = mIC.getTextAfterCursor(n, flags);
        detectLaggyConnection(operation, timeout, startTime);
        return result;
    }

    private void detectLaggyConnection(final int operation, final long timeout, final long startTime) {
        final long duration = SystemClock.uptimeMillis() - startTime;
        if (duration >= timeout) {
            final String operationName = OPERATION_NAMES[operation];
            Log.w(TAG, "Slow InputConnection: " + operationName + " took " + duration + " ms.");
            StatsUtils.onInputConnectionLaggy(operation, duration);
            mLastSlowInputConnectionTime = SystemClock.uptimeMillis();
        } else if (duration < timeout / 5 && hasSlowInputConnection()) {
            mLastSlowInputConnectionTime -= SLOW_INPUTCONNECTION_PERSIST_MS / 2;
            Log.d(TAG, "InputConnection: much faster now, reducing persist time");
        }
    }

    public void deleteTextBeforeCursor(final int beforeLength) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "deleting "+beforeLength+" characters before cursor");
        final int remainingChars = mComposingText.length() - beforeLength;
        if (remainingChars >= 0) {
            mComposingText.setLength(remainingChars);
        } else {
            mComposingText.setLength(0);
            final int len = Math.max(mCommittedTextBeforeComposingText.length()
                    + remainingChars, 0);
            mCommittedTextBeforeComposingText.setLength(len);
        }
        if (mExpectedSelStart > beforeLength) {
            mExpectedSelStart -= beforeLength;
            mExpectedSelEnd -= beforeLength;
        } else {
            mExpectedSelEnd -= mExpectedSelStart;
            mExpectedSelStart = 0;
        }
        if (isConnected()) {
            mIC.deleteSurroundingText(beforeLength, 0);
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
    }

    public void performEditorAction(final int actionId) {
        mIC = mParent.getCurrentInputConnection();
        if (isConnected()) {
            mIC.performEditorAction(actionId);
        }
    }

    public void sendKeyEvent(final KeyEvent keyEvent) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DebugFlags.DEBUG_ENABLED) 
            Log.d(TAG, "key event with action "+keyEvent.getAction()+", is control: "+Character.isISOControl(keyEvent.getUnicodeChar()));
        if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
            if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
            switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
                mCommittedTextBeforeComposingText.append("\n");
                mExpectedSelStart += 1;
                mExpectedSelEnd = mExpectedSelStart;
                break;
            case KeyEvent.KEYCODE_DEL:
                if (0 == mComposingText.length()) {
                    if (mCommittedTextBeforeComposingText.length() > 0) {
                        mCommittedTextBeforeComposingText.delete(
                                mCommittedTextBeforeComposingText.length() - 1,
                                mCommittedTextBeforeComposingText.length());
                    }
                } else {
                    mComposingText.delete(mComposingText.length() - 1, mComposingText.length());
                }
                if (mExpectedSelStart > 0 && mExpectedSelStart == mExpectedSelEnd) {
                    mExpectedSelStart -= 1;
                }
                mExpectedSelEnd = mExpectedSelStart;
                break;
            case KeyEvent.KEYCODE_UNKNOWN:
                if (null != keyEvent.getCharacters()) {
                    mCommittedTextBeforeComposingText.append(keyEvent.getCharacters());
                    mExpectedSelStart += keyEvent.getCharacters().length();
                    mExpectedSelEnd = mExpectedSelStart;
                }
                break;
            default:
                final int codePoint = keyEvent.getUnicodeChar();
                if (Character.isISOControl(codePoint))
                    break; 
                final String text = StringUtils.newSingleCodePointString(codePoint);
                mCommittedTextBeforeComposingText.append(text);
                mExpectedSelStart += text.length();
                mExpectedSelEnd = mExpectedSelStart;
                break;
            }
        }
        if (isConnected()) {
            mIC.sendKeyEvent(keyEvent);
        }
    }

    public void setComposingRegion(final int start, final int end) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        final int moveBy = mExpectedSelStart - start; 
        final CharSequence textBeforeCursor =
                getTextBeforeCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE + (end - start), 0);
        mCommittedTextBeforeComposingText.setLength(0);
        mComposingText.setLength(0);
        if (!TextUtils.isEmpty(textBeforeCursor)) {
            final int indexOfStartOfComposingText = Math.max(textBeforeCursor.length() - moveBy, 0);
            mComposingText.append(textBeforeCursor.subSequence(indexOfStartOfComposingText,
                    textBeforeCursor.length()));
            mCommittedTextBeforeComposingText.append(
                    textBeforeCursor.subSequence(0, indexOfStartOfComposingText));
        }
        if (isConnected()) {
            mIC.setComposingRegion(start, end);
        }
    }

    public boolean setComposingText(final CharSequence text, final int newCursorPosition) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        mExpectedSelStart += text.length() - mComposingText.length();
        mExpectedSelEnd = mExpectedSelStart;
        mComposingText.setLength(0);
        mComposingText.append(text);
        if (isConnected()) {
            if (DebugFlags.DEBUG_ENABLED)
                Log.d(TAG, "setting composing text of length "+text.length()); 
            mIC.setComposingText(text, newCursorPosition);
            if (!Settings.getValues().mInputAttributes.mShouldShowSuggestions && text.length() > 0) {
                final CharSequence lastChar = mIC.getTextBeforeCursor(1, 0);
                if (lastChar == null || lastChar.length() == 0 || text.charAt(text.length() - 1) != lastChar.charAt(0)) {
                    Log.w(TAG, "did set " + text + ", but got " + mIC.getTextBeforeCursor(text.length(), 0) + " as last character");
                    return false;
                }
            }
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        return true;
    }

    public boolean setSelection(final int start, final int end) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "setting selection from "+start+" to "+end);

        if (start < 0 || end < 0) {
            return false;
        }
        if (start > end) {
            mExpectedSelStart = end;
            mExpectedSelEnd = start;
        } else {
            mExpectedSelStart = start;
            mExpectedSelEnd = end;
        }
        if (isConnected()) {
            final boolean isIcValid = mIC.setSelection(start, end);
            if (!isIcValid) {
                return false;
            }
        }
        return reloadTextCache();
    }

    public void selectAll() {
        if (!isConnected()) return;
        if (mExpectedSelStart != mExpectedSelEnd && mExpectedSelStart == 0 && !hasTextAfterCursor()) { 
            mIC.setSelection(mExpectedSelEnd, mExpectedSelEnd);
        } else mIC.performContextMenuAction(android.R.id.selectAll);
    }

    public void selectWord(final SpacingAndPunctuations spacingAndPunctuations, final String script) {
        if (!isConnected()) return;
        if (mExpectedSelStart != mExpectedSelEnd) { 
            mIC.setSelection(mExpectedSelEnd, mExpectedSelEnd);
            return;
        }
        final TextRange range = getWordRangeAtCursor(spacingAndPunctuations, script);
        if (range == null) return;
        mIC.setSelection(mExpectedSelStart - range.getNumberOfCharsInWordBeforeCursor(), mExpectedSelStart + range.getNumberOfCharsInWordAfterCursor());
    }

    public void copyText(final boolean getSelection) {
        CharSequence text = null;
        if (getSelection) {
            text = getSelectedText(InputConnection.GET_TEXT_WITH_STYLES);
        }
        if (text == null || text.length() == 0) {
            final ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.flags = InputConnection.GET_TEXT_WITH_STYLES;
            etr.hintMaxChars = Integer.MAX_VALUE;
            final ExtractedText et = mIC.getExtractedText(etr, 0);
            if (et == null) return;
            text = et.text;
        }
        if (text == null || text.length() == 0) return;
        final ClipboardManager cm = (ClipboardManager) mParent.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("copied text", text));
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            KeyboardSwitcher.getInstance().showToast(mParent.getString(R.string.toast_msg_clipboard_copy), true);
        }
    }

    public void commitCorrection(final CorrectionInfo correctionInfo) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        if (isConnected()) {
            mIC.commitCorrection(correctionInfo);
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
    }

    public void commitCompletion(final CompletionInfo completionInfo) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
        CharSequence text = completionInfo.getText();
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "committing completion of length "+text.length()); 
        if (null == text) text = "";
        mCommittedTextBeforeComposingText.append(text);
        mExpectedSelStart += text.length() - mComposingText.length();
        mExpectedSelEnd = mExpectedSelStart;
        mComposingText.setLength(0);
        if (isConnected()) {
            mIC.commitCompletion(completionInfo);
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug();
    }

    @NonNull
    public NgramContext getNgramContextFromNthPreviousWord(
            final SpacingAndPunctuations spacingAndPunctuations, final int n) {
        mIC = mParent.getCurrentInputConnection();
        if (!isConnected()) {
            return NgramContext.EMPTY_PREV_WORDS_INFO;
        }
        final CharSequence prev = getTextBeforeCursor(NUM_CHARS_TO_GET_BEFORE_CURSOR, 0);
        if (DEBUG_PREVIOUS_TEXT && null != prev) {
            final int checkLength = NUM_CHARS_TO_GET_BEFORE_CURSOR - 1;
            final String reference = prev.length() <= checkLength ? prev.toString()
                    : prev.subSequence(prev.length() - checkLength, prev.length()).toString();
            final StringBuilder internal = new StringBuilder()
                    .append(mCommittedTextBeforeComposingText).append(mComposingText);
            if (internal.length() > checkLength) {
                internal.delete(0, internal.length() - checkLength);
                if (!(reference.equals(internal.toString()))) {
                    final String context = "Expected text = " + internal + "\nActual text = " + reference;
                    ((LatinIME)mParent).debugDumpStateAndCrashWithException(context);
                }
            }
        }
        return NgramContextUtils.getNgramContextFromNthPreviousWord(prev, spacingAndPunctuations, n);
    }

    @Nullable public TextRange getWordRangeAtCursor(final SpacingAndPunctuations spacingAndPunctuations,
            final String script) {
        mIC = mParent.getCurrentInputConnection();
        if (!isConnected()) {
            return null;
        }
        CharSequence before = getTextBeforeCursorAndDetectLaggyConnection(
                OPERATION_GET_WORD_RANGE_AT_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                NUM_CHARS_TO_GET_BEFORE_CURSOR,
                InputConnection.GET_TEXT_WITH_STYLES);
        final CharSequence after = getTextAfterCursorAndDetectLaggyConnection(
                OPERATION_GET_WORD_RANGE_AT_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                NUM_CHARS_TO_GET_AFTER_CURSOR,
                InputConnection.GET_TEXT_WITH_STYLES);
        if (before == null || after == null) {
            return null;
        }
        return StringUtilsKt.getTouchedWordRange(before, after, script, spacingAndPunctuations);
    }

    public boolean isCursorTouchingWord(final SpacingAndPunctuations spacingAndPunctuations,
            boolean checkTextAfter) {
        if (checkTextAfter && isCursorFollowedByWordCharacter(spacingAndPunctuations)) {
            return true;
        }
        if (mComposingText.length() > 0) {
            return true;
        }
        return StringUtilsKt.endsWithWordCodepoint(mCommittedTextBeforeComposingText.toString(), spacingAndPunctuations);
    }

    public boolean isCursorFollowedByWordCharacter(
            final SpacingAndPunctuations spacingAndPunctuations) {
        final CharSequence after = getTextAfterCursor(1, 0);
        if (TextUtils.isEmpty(after)) {
            return false;
        }
        final int codePointAfterCursor = Character.codePointAt(after, 0);
        return !spacingAndPunctuations.isWordSeparator(codePointAfterCursor)
                && !spacingAndPunctuations.isWordConnector(codePointAfterCursor);
    }

    public void removeTrailingSpace() {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        final int codePointBeforeCursor = getCodePointBeforeCursor();
        if (Constants.CODE_SPACE == codePointBeforeCursor) {
            deleteTextBeforeCursor(1);
        }
    }

    public boolean sameAsTextBeforeCursor(final CharSequence text) {
        final CharSequence beforeText = getTextBeforeCursor(text.length(), 0);
        return TextUtils.equals(text, beforeText);
    }

    public boolean revertDoubleSpacePeriod(final SpacingAndPunctuations spacingAndPunctuations) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        final CharSequence textBeforeCursor = getTextBeforeCursor(2, 0);
        if (!TextUtils.equals(spacingAndPunctuations.mSentenceSeparatorAndSpace,
                textBeforeCursor)) {
            Log.d(TAG, "Tried to revert double-space combo but we didn't find \""
                    + spacingAndPunctuations.mSentenceSeparatorAndSpace
                    + "\" just before the cursor.");
            return false;
        }
        deleteTextBeforeCursor(2);
        final String singleSpace = " ";
        commitText(singleSpace, 1);
        return true;
    }

    public boolean revertSwapPunctuation() {
        if (DEBUG_BATCH_NESTING) checkBatchEdit();
        final CharSequence textBeforeCursor = getTextBeforeCursor(2, 0);
        if (TextUtils.isEmpty(textBeforeCursor)
                || (Constants.CODE_SPACE != textBeforeCursor.charAt(1))) {
            Log.d(TAG, "Tried to revert a swap of punctuation but we didn't "
                    + "find a space just before the cursor.");
            return false;
        }
        deleteTextBeforeCursor(2);
        final String text = " " + textBeforeCursor.subSequence(0, 1);
        commitText(text, 1);
        return true;
    }

    public boolean isBelatedExpectedUpdate(final int oldSelStart, final int newSelStart,
            final int oldSelEnd, final int newSelEnd, final int composingSpanStart, final int composingSpanEnd) {
        if (mExpectedSelStart == newSelStart && mExpectedSelEnd == newSelEnd) {
            if (composingSpanEnd - composingSpanStart < mComposingText.length()) {
                return false;
            }
            return true;
        }
        if (mExpectedSelStart == oldSelStart && mExpectedSelEnd == oldSelEnd
                && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) return false;
        return (newSelStart == newSelEnd)
                && (newSelStart - oldSelStart) * (mExpectedSelStart - newSelStart) >= 0
                && (newSelEnd - oldSelEnd) * (mExpectedSelEnd - newSelEnd) >= 0;
    }

    public boolean textBeforeCursorLooksLikeURL() {
        return StringUtils.lastPartLooksLikeURL(mCommittedTextBeforeComposingText);
    }

    public boolean nonWordCodePointAndNoSpaceBeforeCursor(final SpacingAndPunctuations spacingAndPunctuations) {
        return StringUtilsKt.nonWordCodePointAndNoSpaceBeforeCursor(mCommittedTextBeforeComposingText, spacingAndPunctuations);
    }

    public boolean spaceBeforeCursor() {
        return mCommittedTextBeforeComposingText.indexOf(" ") != -1;
    }

    public int getCharCountToDeleteBeforeCursor() {
        int lastCodePoint = getCodePointBeforeCursor();
        if (StringUtils.mightBeEmoji(lastCodePoint) || Character.isSupplementaryCodePoint(lastCodePoint) || ConstantsKt.getCombiningRange().contains(lastCodePoint)) {
            CharSequence text = getTextBeforeCursor(NUM_CHARS_TO_GET_BEFORE_CURSOR, 0);
            if (TextUtils.isEmpty(text)) return 1;
            return StringUtilsKt.getLastGrapheme(text.toString()).length();
        }
        return 1;
    }

    public boolean hasLetterBeforeLastSpaceBeforeCursor() {
        return StringUtilsKt.hasLetterBeforeLastSpaceBeforeCursor(mCommittedTextBeforeComposingText);
    }

    public boolean wordBeforeCursorMayBeEmail() {
        return mCommittedTextBeforeComposingText.lastIndexOf(" ") < mCommittedTextBeforeComposingText.lastIndexOf("@");
    }

    public CharSequence textBeforeCursorUntilLastWhitespaceOrDoubleSlash() {
        int startIndex = 0;
        boolean previousWasSlash = false;
        for (int i = mCommittedTextBeforeComposingText.length() - 1; i >= 0; i--) {
            final char c = mCommittedTextBeforeComposingText.charAt(i);
            if (Character.isWhitespace(c)) {
                startIndex = i + 1;
                break;
            }
            if (c == '/') {
                if (previousWasSlash) {
                    startIndex = i + 2;
                    break;
                }
                previousWasSlash = true;
            } else {
                previousWasSlash = false;
            }
        }
        return mCommittedTextBeforeComposingText.subSequence(startIndex, mCommittedTextBeforeComposingText.length());
    }

    public boolean isInsideDoubleQuoteOrAfterDigit() {
        return StringUtils.isInsideDoubleQuoteOrAfterDigit(mCommittedTextBeforeComposingText);
    }

    public void tryFixIncorrectCursorPosition() {
        mIC = mParent.getCurrentInputConnection();
        final CharSequence textBeforeCursor = getTextBeforeCursor(
                Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        final CharSequence selectedText = isConnected() ? mIC.getSelectedText(0 /* flags */) : null;
        if (null == textBeforeCursor ||
                (!TextUtils.isEmpty(selectedText) && mExpectedSelEnd == mExpectedSelStart)) {
            mExpectedSelStart = mExpectedSelEnd = Constants.NOT_A_CURSOR_POSITION;
        } else {
            final int textLength = textBeforeCursor.length();
            if (textLength < Constants.EDITOR_CONTENTS_CACHE_SIZE
                    && (textLength > mExpectedSelStart
                            ||  mExpectedSelStart < Constants.EDITOR_CONTENTS_CACHE_SIZE)) {
                final boolean wasEqual = mExpectedSelStart == mExpectedSelEnd;
                mExpectedSelStart = textLength;
                if (wasEqual || mExpectedSelStart > mExpectedSelEnd) {
                    mExpectedSelEnd = mExpectedSelStart;
                }
            } else {
                reloadCursorPosition();
            }
        }
    }

    @Override
    public boolean performPrivateCommand(final String action, final Bundle data) {
        mIC = mParent.getCurrentInputConnection();
        if (!isConnected()) {
            return false;
        }
        return mIC.performPrivateCommand(action, data);
    }

    public int getExpectedSelectionStart() {
        return mExpectedSelStart;
    }

    public int getExpectedSelectionEnd() {
        return mExpectedSelEnd;
    }

    public boolean hasSelection() {
        return mExpectedSelEnd != mExpectedSelStart;
    }

    public boolean isCursorPositionKnown() {
        return INVALID_CURSOR_POSITION != mExpectedSelStart;
    }

    public boolean requestCursorUpdates(final boolean enableMonitor, final boolean requestImmediateCallback) {
        mIC = mParent.getCurrentInputConnection();
        if (!isConnected()) {
            return false;
        }
        final int cursorUpdateMode = (enableMonitor ? InputConnection.CURSOR_UPDATE_MONITOR : 0)
            | (requestImmediateCallback ? InputConnection.CURSOR_UPDATE_IMMEDIATE : 0);
        return mIC.requestCursorUpdates(cursorUpdateMode);
    }

    public void commitContent(androidx.core.view.inputmethod.InputContentInfoCompat contentInfo, @NonNull android.view.inputmethod.EditorInfo editorInfo) {
        mIC = mParent.getCurrentInputConnection();
        if (isConnected())
            androidx.core.view.inputmethod.InputConnectionCompat.commitContent(mIC, editorInfo, contentInfo, androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
    }
            }
