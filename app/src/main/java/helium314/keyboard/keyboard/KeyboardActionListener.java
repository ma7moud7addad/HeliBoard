/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.macboard.keyboard.keyboard;

import android.view.KeyEvent;

import com.macboard.keyboard.event.HapticEvent;
import com.macboard.keyboard.latin.common.Constants;
import com.macboard.keyboard.latin.common.InputPointers;
import androidx.core.view.inputmethod.InputContentInfoCompat;

public interface KeyboardActionListener {
    void onPressKey(int primaryCode, int repeatCount, boolean isSinglePointer, HapticEvent hapticEvent);
    void onLongPressKey(int primaryCode);
    void onReleaseKey(int primaryCode, boolean withSliding);
    boolean onKeyDown(int keyCode, KeyEvent keyEvent);
    boolean onKeyUp(int keyCode, KeyEvent keyEvent);
    void onCodeInput(int primaryCode, int x, int y, boolean isKeyRepeat);
    void onTextInput(String text);
    void onStartBatchInput();
    void onUpdateBatchInput(InputPointers batchPointers);
    void onEndBatchInput(InputPointers batchPointers);
    void onCancelBatchInput();
    void onCancelInput();
    void onFinishSlidingInput();
    boolean onCustomRequest(int requestCode);
    boolean onHorizontalSpaceSwipe(int steps);
    boolean onVerticalSpaceSwipe(int steps);
    void onEndSpaceSwipe();
    boolean toggleNumpad(boolean withSliding, boolean forceReturnToAlpha);
    void onMoveDeletePointer(int steps);
    void onUpWithDeletePointerActive();
    void resetMetaState();
    void onEnterCursorMode();
    void onExitCursorMode();

    // الدالة الجديدة المسؤولة عن إرسال الصور
    boolean commitImage(android.net.Uri uri);

    // الدالة للتعامل مع محتوى الإدخال (الصور/ميديا) المرسلة عبر commitContent
    void onContent(InputContentInfoCompat content);

    KeyboardActionListener EMPTY_LISTENER = new Adapter();

    enum SwipeAction { NONE, MOVE_CURSOR, SWITCH_LANGUAGE, TOGGLE_NUMPAD, HIDE_KEYBOARD, TOUCHPAD_MODE }

    class Adapter implements KeyboardActionListener {
        @Override
        public void onPressKey(int primaryCode, int repeatCount, boolean isSinglePointer, HapticEvent hapticEvent) {}
        @Override
        public void onLongPressKey(int primaryCode) {}
        @Override
        public void onReleaseKey(int primaryCode, boolean withSliding) {}
        @Override
        public boolean onKeyDown(int keyCode, KeyEvent keyEvent) { return false; }
        @Override
        public boolean onKeyUp(int keyCode, KeyEvent keyEvent) { return false; }
        @Override
        public void onCodeInput(int primaryCode, int x, int y, boolean isKeyRepeat) {}
        @Override
        public void onTextInput(String text) {}
        @Override
        public void onStartBatchInput() {}
        @Override
        public void onUpdateBatchInput(InputPointers batchPointers) {}
        @Override
        public void onEndBatchInput(InputPointers batchPointers) {}
        @Override
        public void onCancelBatchInput() {}
        @Override
        public void onCancelInput() {}
        @Override
        public void onFinishSlidingInput() {}
        @Override
        public boolean onCustomRequest(int requestCode) { return false; }
        @Override
        public boolean onHorizontalSpaceSwipe(int steps) { return false; }
        @Override
        public boolean onVerticalSpaceSwipe(int steps) { return false; }
        @Override
        public boolean toggleNumpad(boolean withSliding, boolean forceReturnToAlpha) { return false; }
        @Override
        public void onEndSpaceSwipe() {}
        @Override
        public void onMoveDeletePointer(int steps) {}
        @Override
        public void onUpWithDeletePointerActive() {}
        @Override
        public void resetMetaState() {}
        @Override
        public void onEnterCursorMode() {}
        @Override
        public void onExitCursorMode() {}
        
        // التنفيذ الافتراضي للدالة الجديدة
        @Override
        public boolean commitImage(android.net.Uri uri) { return false; }

        // التنفيذ الافتراضي لدالة onContent
        @Override
        public void onContent(InputContentInfoCompat content) {}
    }
}
