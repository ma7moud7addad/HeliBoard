/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.macboard.keyboard.latin.utils;

import android.annotation.SuppressLint;
import android.util.Log;

@SuppressLint("PrivateApi")
public final class JniUtils {
    private static final String TAG = JniUtils.class.getSimpleName();
    public static final String JNI_LIB_NAME = "jni_latinime";
    public static final String JNI_LIB_NAME_GOOGLE = "jni_latinimegoogle";

    public static boolean sHaveGestureLib = false;

    static {
        // 1. Try loading the bundled Google gesture library first
        try {
            System.loadLibrary(JNI_LIB_NAME_GOOGLE);
            sHaveGestureLib = true;
            Log.i(TAG, "Successfully loaded bundled gesture typing library");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Bundled gesture library not available: " + e.getMessage());
        }

        // 2. Fallback: Try loading built-in library if Google's is missing
        if (!sHaveGestureLib) {
            try {
                System.loadLibrary(JNI_LIB_NAME);
            } catch (UnsatisfiedLinkError ul) {
                Log.w(TAG, "Could not load native library " + JNI_LIB_NAME, ul);
            }
        }
    }

    private JniUtils() {
        // This utility class is not publicly instantiable.
    }

    public static void loadNativeLibrary() {
        // Ensures the static initializer is called
    }

    /**
     * Checks whether the bundled gesture library is available on this device.
     * Used by settings to determine if gesture typing should be shown.
     */
    public static boolean isGestureLibraryAvailable() {
        return sHaveGestureLib;
    }
}
