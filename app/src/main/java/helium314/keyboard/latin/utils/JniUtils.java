/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.macboard.keyboard.latin.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.macboard.keyboard.latin.utils.DeviceProtectedUtils;

@SuppressLint("PrivateApi") // it's a fallback in try/catch
public final class JniUtils {
    private static final String TAG = JniUtils.class.getSimpleName();
    public static final String JNI_LIB_NAME = "jni_latinime";
    public static final String JNI_LIB_NAME_GOOGLE = "jni_latinimegoogle";

    // Preference key for enabling bundled gesture typing
    public static final String PREF_ENABLE_BUNDLED_GESTURE = "pref_enable_bundled_gesture";

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

    /**
     * Checks whether gesture typing is enabled in preferences AND library is available.
     */
    public static boolean isGestureTypingEnabled(Context context) {
        SharedPreferences prefs = DeviceProtectedUtils.getSharedPreferences(context);
        boolean enabled = prefs.getBoolean(PREF_ENABLE_BUNDLED_GESTURE, true);
        return enabled && isGestureLibraryAvailable();
    }
}
