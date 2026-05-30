/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Message;
import android.os.Process;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.view.inputmethod.InputMethodSubtype;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.compat.ConfigurationCompatKt;
import helium314.keyboard.compat.EditorInfoCompatUtils;
import helium314.keyboard.compat.ImeCompat;
import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardActionListenerImpl;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity;
import helium314.keyboard.keyboard.internal.KeyboardIconsSet;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.InsetsOutlineProvider;
import helium314.keyboard.dictionarypack.DictionaryPackConstants;
import helium314.keyboard.event.Event;
import helium314.keyboard.event.InputTransaction;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardId;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.common.ViewOutlineProviderUtilsKt;
import helium314.keyboard.latin.define.DebugFlags;
import helium314.keyboard.latin.inputlogic.InputLogic;
import helium314.keyboard.latin.personalization.PersonalizationHelper;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.suggestions.SuggestionStripViewAccessor;
import helium314.keyboard.latin.touchinputconsumer.GestureConsumer;
import helium314.keyboard.latin.utils.ColorUtilKt;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.GestureDataGatheringKt;
import helium314.keyboard.latin.utils.GestureDataGatheringSettings;
import helium314.keyboard.latin.utils.InlineAutofillUtils;
import helium314.keyboard.latin.utils.InputMethodPickerKt;
import helium314.keyboard.latin.utils.JniUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LeakGuardHandlerWrapper;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.StatsUtils;
import helium314.keyboard.latin.utils.StatsUtilsManager;
import helium314.keyboard.latin.utils.SubtypeLocaleUtils;
import helium314.keyboard.latin.utils.SubtypeSettings;
import helium314.keyboard.latin.utils.SubtypeState;
import helium314.keyboard.latin.utils.ToolbarMode;
import helium314.keyboard.settings.SettingsActivity2;
import kotlin.Unit;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import android.content.ClipDescription;
import android.net.Uri;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

public class LatinIME extends InputMethodService implements
        SuggestionStripView.Listener, SuggestionStripViewAccessor,
        DictionaryFacilitator.DictionaryInitializationListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS = TimeUnit.SECONDS.toMillis(2);
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private static final String SCHEME_PACKAGE = "package";

    final Settings mSettings;
    public final KeyboardActionListener mKeyboardActionListener;
    private int mOriginalNavBarColor = 0;
    private int mOriginalNavBarFlags = 0;

    public final UIHandler mHandler = new UIHandler(this);
    private DictionaryFacilitator mDictionaryFacilitator =
            DictionaryFacilitatorProvider.getDictionaryFacilitator(false);
    private final DictionaryFacilitator mOriginalDictionaryFacilitator = mDictionaryFacilitator;
    final InputLogic mInputLogic = new InputLogic(this, this, mDictionaryFacilitator);

    private View mInputView;
    private InsetsOutlineProvider mInsetsUpdater;
    private SuggestionStripView mSuggestionStripView;

    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;
    private final SubtypeState mSubtypeState = new SubtypeState((InputMethodSubtype subtype) -> { switchToSubtype(subtype); return Unit.INSTANCE; });
    private final StatsUtilsManager mStatsUtilsManager;
    private boolean mIsExecutingStartShowingInputView;

    @Nullable
    private Context mDisplayContext;

    private final BroadcastReceiver mDictionaryPackInstallReceiver =
            new DictionaryPackInstallBroadcastReceiver(this);

    private final BroadcastReceiver mDictionaryDumpBroadcastReceiver =
            new DictionaryDumpBroadcastReceiver(this);

    FoldableUtils.FoldableObserver foldableObserver;

    final static class RestartAfterDeviceUnlockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                final int myPid = Process.myPid();
                Log.i(TAG, "Killing my process: pid=" + myPid);
                Process.killProcess(myPid);
            } else {
                Log.e(TAG, "Unexpected intent " + intent);
            }
        }
    }
    final RestartAfterDeviceUnlockReceiver mRestartAfterDeviceUnlockReceiver = new RestartAfterDeviceUnlockReceiver();

    private AlertDialog mOptionsDialog;

    private final boolean mIsHardwareAcceleratedDrawingEnabled;

    private GestureConsumer mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;

    private final ClipboardHistoryManager mClipboardHistoryManager = new ClipboardHistoryManager(this);
    private final ImageSuggestionManager mImageSuggestionManager = new ImageSuggestionManager(this);

    private boolean mIsClipboardAuthenticated = false;
    private boolean mIsWaitingForBiometricResult = false;

    // MacBoard: Clipboard expansion state
    private boolean mIsClipboardExpanded = false;
    private int mClipboardExpandedHeight = 0;

    public void setClipboardExpanded(boolean expanded, int height) {
        mIsClipboardExpanded = expanded;
        mClipboardExpandedHeight = height;
        if (mInputView != null) {
            mInputView.requestLayout();
            mInputView.post(() -> {
                updateFullscreenMode();
                KtxKt.updateSoftInputWindowLayoutParameters(LatinIME.this, mInputView);
            });
        }
    }

    private void openClipboardWithAuth() {
        if (!mIsClipboardAuthenticated) {
            mIsWaitingForBiometricResult = true;
            try {
                Intent intent = new Intent("com.mahmoud.MACRO_REQ_FINGERPRINT");
                sendBroadcast(intent);
            } catch (Exception e) { }
            mHandler.postDelayed(() -> mIsWaitingForBiometricResult = false, 10000);
            return;
        }
        mIsClipboardAuthenticated = false;
        mKeyboardSwitcher.setClipboardKeyboard();
    }

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 0;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_UPDATE_SUGGESTION_STRIP = 2;
        private static final int MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS = 3;
        private static final int MSG_RESUME_SUGGESTIONS = 4;
        private static final int MSG_REOPEN_DICTIONARIES = 5;
        private static final int MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED = 6;
        private static final int MSG_RESET_CACHES = 7;
        private static final int MSG_WAIT_FOR_DICTIONARY_LOAD = 8;
        private static final int MSG_DEALLOCATE_MEMORY = 9;
        private static final int MSG_SWITCH_LANGUAGE_AUTOMATICALLY = 10;
        private static final int MSG_LAST = MSG_SWITCH_LANGUAGE_AUTOMATICALLY;

        private static final int ARG1_NOT_GESTURE_INPUT = 0;
        private static final int ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT = 1;
        private static final int ARG1_SHOW_GESTURE_FLOATING_PREVIEW_TEXT = 2;
        private static final int ARG2_UNUSED = 0;
        private static final int ARG1_TRUE = 1;

        private int mDelayInMillisecondsToUpdateSuggestions;
        private int mDelayInMillisecondsToUpdateShiftState;

        public UIHandler(@NonNull final LatinIME ownerInstance) {
            super(ownerInstance);
        }

        public void onCreate() {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) return;
            final Resources res = latinIme.getResources();
            mDelayInMillisecondsToUpdateSuggestions = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_suggestions);
            mDelayInMillisecondsToUpdateShiftState = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_shift_state);
        }

        @Override
        public void handleMessage(@NonNull final Message msg) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) return;
            switch (msg.what) {
                case MSG_UPDATE_SUGGESTION_STRIP:
                    cancelUpdateSuggestionStrip();
                    latinIme.mInputLogic.performUpdateSuggestionStripSync(
                            latinIme.mSettings.getCurrent(), msg.arg1);
                    break;
                case MSG_UPDATE_SHIFT_STATE:
                    latinIme.mKeyboardSwitcher.requestUpdatingShiftState(latinIme.getCurrentAutoCapsState(),
                            latinIme.getCurrentRecapitalizeState());
                    break;
                case MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS:
                    if (msg.arg1 == ARG1_NOT_GESTURE_INPUT) {
                        final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                        latinIme.setSuggestedWords(suggestedWords);
                    } else {
                        latinIme.showGesturePreviewAndSetSuggestions((SuggestedWords) msg.obj,
                                msg.arg1 == ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT);
                    }
                    break;
                case MSG_RESUME_SUGGESTIONS:
                    latinIme.mInputLogic.restartSuggestionsOnWordTouchedByCursor(
                            latinIme.mSettings.getCurrent(),
                            latinIme.mKeyboardSwitcher.getCurrentKeyboardScript());
                    break;
                case MSG_REOPEN_DICTIONARIES:
                    postWaitForDictionaryLoad();
                    latinIme.resetDictionaryFacilitatorIfNecessary();
                    break;
                case MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED:
                    final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                    latinIme.mInputLogic.onUpdateTailBatchInputCompleted(
                            latinIme.mSettings.getCurrent(),
                            suggestedWords, latinIme.mKeyboardSwitcher);
                    latinIme.onTailBatchInputResultShown(suggestedWords);
                    break;
                case MSG_RESET_CACHES:
                    final SettingsValues settingsValues = latinIme.mSettings.getCurrent();
                    if (latinIme.mInputLogic.retryResetCachesAndReturnSuccess(
                            msg.arg1 == ARG1_TRUE,
                            msg.arg2, this)) {
                        latinIme.mKeyboardSwitcher.reloadMainKeyboard();
                    }
                    break;
                case MSG_WAIT_FOR_DICTIONARY_LOAD:
                    Log.i(TAG, "Timeout waiting for dictionary load");
                    break;
                case MSG_DEALLOCATE_MEMORY:
                    latinIme.deallocateMemory();
                    break;
                case MSG_SWITCH_LANGUAGE_AUTOMATICALLY:
                    latinIme.switchToSubtype((InputMethodSubtype) msg.obj);
                    break;
            }
        }

        public void postUpdateSuggestionStrip(final int inputStyle) {
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SUGGESTION_STRIP, inputStyle,
                    0), mDelayInMillisecondsToUpdateSuggestions);
        }

        public void postReopenDictionaries() {
            sendMessage(obtainMessage(MSG_REOPEN_DICTIONARIES));
        }

        public void postResumeSuggestions(final boolean shouldDelay) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) return;
            if (!latinIme.mSettings.getCurrent().needsToLookupSuggestions()) return;
            removeMessages(MSG_RESUME_SUGGESTIONS);
            if (shouldDelay) {
                sendMessageDelayed(obtainMessage(MSG_RESUME_SUGGESTIONS),
                        mDelayInMillisecondsToUpdateSuggestions);
            } else {
                sendMessage(obtainMessage(MSG_RESUME_SUGGESTIONS));
            }
        }

        public void postResetCaches(final boolean tryResumeSuggestions, final int remainingTries) {
            removeMessages(MSG_RESET_CACHES);
            sendMessage(obtainMessage(MSG_RESET_CACHES, tryResumeSuggestions ? 1 : 0,
                    remainingTries, null));
        }

        public void postWaitForDictionaryLoad() {
            sendMessageDelayed(obtainMessage(MSG_WAIT_FOR_DICTIONARY_LOAD),
                    DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS);
        }

        public void cancelWaitForDictionaryLoad() {
            removeMessages(MSG_WAIT_FOR_DICTIONARY_LOAD);
        }

        public boolean hasPendingWaitForDictionaryLoad() {
            return hasMessages(MSG_WAIT_FOR_DICTIONARY_LOAD);
        }

        public void cancelUpdateSuggestionStrip() {
            removeMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public void cancelResumeSuggestions() {
            removeMessages(MSG_RESUME_SUGGESTIONS);
        }

        public boolean hasPendingUpdateSuggestions() {
            return hasMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public boolean hasPendingResumeSuggestions() {
            return hasMessages(MSG_RESUME_SUGGESTIONS);
        }

        public boolean hasPendingReopenDictionaries() {
            return hasMessages(MSG_REOPEN_DICTIONARIES);
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE),
                    mDelayInMillisecondsToUpdateShiftState);
        }

        public void postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS);
        }

        public void cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY);
        }

        public boolean hasPendingDeallocateMemory() {
            return hasMessages(MSG_DEALLOCATE_MEMORY);
        }

        public void removeAllMessages() {
            for (int i = 0; i <= MSG_LAST; ++i) {
                removeMessages(i);
            }
        }

        public void showGesturePreviewAndSetSuggestions(final SuggestedWords suggestedWords,
                                                        final boolean dismissGestureFloatingPreviewText) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS);
            final int arg1 = dismissGestureFloatingPreviewText
                    ? ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT
                    : ARG1_SHOW_GESTURE_FLOATING_PREVIEW_TEXT;
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS, arg1,
                    ARG2_UNUSED, suggestedWords).sendToTarget();
        }

        public void setSuggestions(final SuggestedWords suggestedWords) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS);
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS,
                    ARG1_NOT_GESTURE_INPUT, ARG2_UNUSED, suggestedWords).sendToTarget();
        }

        public void showTailBatchInputResult(final SuggestedWords suggestedWords) {
            obtainMessage(MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED, suggestedWords).sendToTarget();
        }

        public void postSwitchLanguage(final InputMethodSubtype subtype) {
            obtainMessage(MSG_SWITCH_LANGUAGE_AUTOMATICALLY, subtype).sendToTarget();
        }

        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        public void startOrientationChanging() {
            removeMessages(MSG_PENDING_IMS_CALLBACK);
            resetPendingImsCallback();
            mIsOrientationChanging = true;
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) return;
            if (latinIme.isInputViewShown()) {
                latinIme.mKeyboardSwitcher.saveKeyboardState();
            }
        }

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                                               boolean restarting) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal();
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputInternal(editorInfo, restarting);
                }
            }
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS);
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputViewInternal(editorInfo, restarting);
                    mAppliedEditorInfo = editorInfo;
                }
                cancelDeallocateMemory();
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput);
                    mAppliedEditorInfo = null;
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory();
                }
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false);
                    latinIme.onFinishInputInternal();
                }
            }
        }
    }

    static {
        JniUtils.loadNativeLibrary();
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mStatsUtilsManager = StatsUtilsManager.getInstance();
        mKeyboardActionListener = new KeyboardActionListenerImpl(this, mInputLogic);
        mIsHardwareAcceleratedDrawingEnabled = this.enableHardwareAcceleration();
        Log.i(TAG, "Hardware accelerated drawing: " + mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void onCreate() {
        mSettings.startListener();
        KeyboardIconsSet.Companion.getInstance().loadIcons(this);
        mRichImm = RichInputMethodManager.getInstance();
        AudioAndHapticFeedbackManager.init(this);
        AccessibilityUtils.init(this);
        mStatsUtilsManager.onCreate(this, mDictionaryFacilitator);
        mDisplayContext = KtxKt.getDisplayContext(this);
        KeyboardSwitcher.init(this);
        super.onCreate();

        loadSettings();
        mClipboardHistoryManager.onCreate();
        mImageSuggestionManager.onCreate();
        mHandler.onCreate();
        if (FoldableUtils.INSTANCE.isFoldable())
            foldableObserver = new FoldableUtils.FoldableObserver(this);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);

        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme(SCHEME_PACKAGE);
        registerReceiver(mDictionaryPackInstallReceiver, packageFilter);

        final IntentFilter newDictFilter = new IntentFilter();
        newDictFilter.addAction(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION);
        ContextCompat.registerReceiver(this, mDictionaryPackInstallReceiver, newDictFilter, ContextCompat.RECEIVER_EXPORTED);

        final IntentFilter dictDumpFilter = new IntentFilter();
        dictDumpFilter.addAction(DictionaryDumpBroadcastReceiver.DICTIONARY_DUMP_INTENT_ACTION);
        ContextCompat.registerReceiver(this, mDictionaryDumpBroadcastReceiver, dictDumpFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        final IntentFilter restartAfterUnlockFilter = new IntentFilter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            restartAfterUnlockFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver(mRestartAfterDeviceUnlockReceiver, restartAfterUnlockFilter);

        final IntentFilter macroFilter = new IntentFilter();
        macroFilter.addAction("com.mahmoud.MACRO_OPEN_CLIPBOARD");
        macroFilter.addAction("com.mahmoud.MACRO_AUTH_FAILED");
        ContextCompat.registerReceiver(this, mMacroDroidReceiver, macroFilter, ContextCompat.RECEIVER_EXPORTED);
        StatsUtils.onCreate(mSettings.getCurrent(), mRichImm);
    }

    private void loadSettings() {
        final Locale locale = mRichImm.getCurrentSubtypeLocale();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(
                editorInfo, isFullscreenMode(), getPackageName());
        mSettings.loadSettings(this, locale, inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
        if (!mHandler.hasPendingReopenDictionaries()) {
            resetDictionaryFacilitatorIfNecessary();
        }
        refreshPersonalizationDictionarySession(currentSettingsValues);
        mInputLogic.mSuggest.clearNextWordSuggestionsCache();
        mInputLogic.updateEmojiDictionary(locale);
        mStatsUtilsManager.onLoadSettings(this, currentSettingsValues);
    }

    private void refreshPersonalizationDictionarySession(
            final SettingsValues currentSettingsValues) {
        if (!currentSettingsValues.mUsePersonalizedDicts) {
            PersonalizationHelper.removeAllUserHistoryDictionaries(this);
            mDictionaryFacilitator.clearUserHistoryDictionary(this);
        }
    }

    @Override
    public void onUpdateMainDictionaryAvailability(final boolean isMainDictionaryAvailable) {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.setMainDictionaryAvailability(isMainDictionaryAvailable);
        }
        if (mHandler.hasPendingWaitForDictionaryLoad()) {
            mHandler.cancelWaitForDictionaryLoad();
            mHandler.postResumeSuggestions(false);
        }
    }

    void resetDictionaryFacilitatorIfNecessary() {
        final Locale subtypeSwitcherLocale = mRichImm.getCurrentSubtypeLocale();
        final Locale subtypeLocale;
        if (subtypeSwitcherLocale == null) {
            Log.e(TAG, "System is reporting no current subtype.");
            subtypeLocale = ConfigurationCompatKt.locale(getResources().getConfiguration());
        } else {
            subtypeLocale = subtypeSwitcherLocale;
        }
        final ArrayList<Locale> locales = new ArrayList<>();
        locales.add(subtypeLocale);
        locales.addAll(mSettings.getCurrent().mSecondaryLocales);
        if (mDictionaryFacilitator.usesSameSettings(
                locales,
                mSettings.getCurrent().mUseContactsDictionary,
                mSettings.getCurrent().mUseAppsDictionary,
                mSettings.getCurrent().mUsePersonalizedDicts
        )) {
            return;
        }
        resetDictionaryFacilitator(subtypeLocale);
    }

    private void resetDictionaryFacilitator(@NonNull final Locale locale) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        try {
            mDictionaryFacilitator.resetDictionaries(this, locale,
                settingsValues.mUseContactsDictionary, settingsValues.mUseAppsDictionary,
                settingsValues.mUsePersonalizedDicts, false, "", this);
        } catch (Throwable e) {
            Log.e(TAG, "Could not reset dictionary facilitator, please fix ASAP", e);
        }
        mInputLogic.mSuggest.setAutoCorrectionThreshold(settingsValues.mAutoCorrectionThreshold);
    }

    void resetSuggestMainDict() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        mDictionaryFacilitator.resetDictionaries(this, mDictionaryFacilitator.getMainLocale(),
                settingsValues.mUseContactsDictionary, settingsValues.mUseAppsDictionary,
                settingsValues.mUsePersonalizedDicts, true, "", this);
        mKeyboardSwitcher.setThemeNeedsReload();
        EmojiPalettesView.closeDictionaryFacilitator();
        EmojiSearchActivity.Companion.closeDictionaryFacilitator();
    }

    public String getLocaleAndConfidenceInfo() {
        return mDictionaryFacilitator.localesAndConfidences();
    }

    @Override
    public void onDestroy() {
        mClipboardHistoryManager.onDestroy();
        mImageSuggestionManager.onDestroy();
        mDictionaryFacilitator.closeDictionaries();
        mSettings.onDestroy();
        if (foldableObserver != null)
            foldableObserver.unregister(this);
        unregisterReceiver(mRingerModeChangeReceiver);
        unregisterReceiver(mDictionaryPackInstallReceiver);
        unregisterReceiver(mDictionaryDumpBroadcastReceiver);
        unregisterReceiver(mRestartAfterDeviceUnlockReceiver);
        unregisterReceiver(mMacroDroidReceiver);
        mStatsUtilsManager.onDestroy(this);
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        deallocateMemory();
    }

    private boolean isImeSuppressedByHardwareKeyboard() {
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        SettingsValues settingsValues = mSettings.getCurrent();
        Log.i(TAG, "onConfigurationChanged");
        SubtypeSettings.INSTANCE.reloadSystemLocales(this);
        if (settingsValues.mDisplayOrientation != conf.orientation) {
            mHandler.startOrientationChanging();
            mInputLogic.onOrientationChange(mSettings.getCurrent());
        }
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            loadSettings();
            if (isImeSuppressedByHardwareKeyboard()) {
                cleanupInternalStateForFinishInput();
            }
        }
        mKeyboardSwitcher.updateKeyboardTheme(KtxKt.getDisplayContext(this));
        super.onConfigurationChanged(conf);
    }

    @Override
    public void onInitializeInterface() {
        mDisplayContext = KtxKt.getDisplayContext(this);
        Log.d(TAG, "onInitializeInterface");
        mKeyboardSwitcher.updateKeyboardTheme(mDisplayContext);
    }

    @Override
    public View onCreateInputView() {
        StatsUtils.onCreateInputView();
        return mKeyboardSwitcher.onCreateInputView(KtxKt.getDisplayContext(this), mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void setInputView(final View view) {
        super.setInputView(view);
        mInputView = view;
        mInsetsUpdater = ViewOutlineProviderUtilsKt.setInsetsOutlineProvider(view);
        KtxKt.updateSoftInputWindowLayoutParameters(this, mInputView);
        updateSuggestionStripView(view);
    }

    public void updateSuggestionStripView(View view) {
        mSuggestionStripView = mSettings.getCurrent().mToolbarMode == ToolbarMode.HIDDEN || isEmojiSearch()?
                        null : view.findViewById(R.id.suggestion_strip_view);
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setRtl(mRichImm.getCurrentSubtype().isRtlSubtype());
            mSuggestionStripView.setListener(this, view);
        }
    }

    @Override
    public void setCandidatesView(final View view) {
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
        mStatsUtilsManager.onStartInputView();
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        StatsUtils.onFinishInputView();
        mHandler.onFinishInputView(finishingInput);
        mStatsUtilsManager.onFinishInputView();
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(final InputMethodSubtype subtype) {
        if (subtype.hashCode() == 0x7000000f) {
            return;
        }
        InputMethodSubtype oldSubtype = mRichImm.getCurrentSubtype().getRawSubtype();
        if (subtype.equals(oldSubtype)) {
            return;
        }

        mSubtypeState.onSubtypeChanged(oldSubtype, subtype);
        StatsUtils.onSubtypeChanged(oldSubtype, subtype);
        mRichImm.onSubtypeChanged(subtype);
        mInputLogic.onSubtypeChanged(SubtypeLocaleUtils.getCombiningRulesExtraValue(subtype),
                mSettings.getCurrent());
        loadKeyboard();
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setRtl(mRichImm.getCurrentSubtype().isRtlSubtype());
        }
        mSettings.saveSubtypeForApp(mRichImm.getCurrentSubtype(), getCurrentInputEditorInfo().packageName);
    }

    public void switchToSubtype(final InputMethodSubtype subtype) {
        onCurrentInputMethodSubtypeChanged(subtype);
    }

    private void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);

        final RichInputMethodSubtype subtypeForApp = editorInfo == null
            ? null :
            mSettings.getSubtypeForApp(editorInfo.packageName);
        final List<Locale> hintLocales = EditorInfoCompatUtils.getHintLocales(editorInfo);
        final InputMethodSubtype subtypeForLocales = mSubtypeState.getSubtypeForLocales(mRichImm, hintLocales, subtypeForApp);
        if (subtypeForLocales != null) {
            mHandler.postSwitchLanguage(subtypeForLocales);
        }
    }

    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);

        setGestureDataGatheringMode(editorInfo);

        mDictionaryFacilitator.onStartInput();
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
        mRichImm.refreshSubtypeCaches();
        final KeyboardSwitcher switcher = mKeyboardSwitcher;

        SettingsValues currentSettingsValues = mSettings.getCurrent();
        boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        boolean isDifferentTextField = !restarting || inputTypeChanged;

        if (isDifferentTextField || !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings();
            if (hasSuggestionStripView())
                mSuggestionStripView.updateVoiceKey();
        }

        switcher.updateKeyboardTheme(mDisplayContext);
        MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }
        Log.i(TAG, (restarting ? "Res" : "S") +"tarting input. Cursor position = " + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd);
        if (DebugFlags.DEBUG_ENABLED) {
            EditorInfoCompatUtils.INSTANCE.debugLog(editorInfo, TAG);
        }

        if (mainKeyboardView == null) {
            return;
        }

        mGestureConsumer = GestureConsumer.newInstance(editorInfo,
                mInputLogic.getPrivateCommandPerformer(),
                mRichImm.getCurrentSubtypeLocale(),
                switcher.getKeyboard());

        final AccessibilityUtils accessUtils = AccessibilityUtils.Companion.getInstance();
        if (accessUtils.isTouchExplorationEnabled()) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting);
        }

        StatsUtils.onStartInputView(editorInfo.inputType,
                Settings.getValues().mDisplayOrientation,
                !isDifferentTextField);

        updateFullscreenMode();

        final boolean needToCallLoadKeyboardLater;
        final Suggest suggest = mInputLogic.mSuggest;
        if (!isImeSuppressedByHardwareKeyboard()) {
            mInputLogic.startInput(mRichImm.getCombiningRulesExtraValueOfCurrentSubtype(), currentSettingsValues);

            resetDictionaryFacilitatorIfNecessary();

            if (!mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(
                    editorInfo.initialSelStart, editorInfo.initialSelEnd,
                    false)) {
                mHandler.postResetCaches(isDifferentTextField, 5);
                needToCallLoadKeyboardLater = true;
            } else {
                mInputLogic.mConnection.tryFixIncorrectCursorPosition();
                if (mInputLogic.mConnection.isCursorTouchingWord(currentSettingsValues.mSpacingAndPunctuations, true)) {
                    mHandler.postResumeSuggestions(true);
                }
                needToCallLoadKeyboardLater = false;
            }
        } else {
            needToCallLoadKeyboardLater = false;
        }

        if (isDifferentTextField) {
            mainKeyboardView.closing();
            suggest.setAutoCorrectionThreshold(currentSettingsValues.mAutoCorrectionThreshold);
            switcher.reloadMainKeyboard();
            if (needToCallLoadKeyboardLater) {
                switcher.saveKeyboardState();
            }
        } else if (restarting) {
            switcher.resetKeyboardStateToAlphabet(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
            switcher.requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
        if (!mHandler.hasPendingResumeSuggestions()) {
            mHandler.cancelUpdateSuggestionStrip();
            setNeutralSuggestionStrip();
            if (hasSuggestionStripView() && currentSettingsValues.mAutoShowToolbar && !tryShowMediaSuggestion()) {
                mSuggestionStripView.setToolbarVisibility(true);
            }
        }

        mainKeyboardView.setMainDictionaryAvailability(mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary());
        mainKeyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(currentSettingsValues.mSlidingKeyInputPreviewEnabled);
        mainKeyboardView.setGestureHandlingEnabledByUser(
                currentSettingsValues.mGestureInputEnabled,
                currentSettingsValues.mGestureTrailEnabled,
                currentSettingsValues.mGestureFloatingPreviewTextEnabled);

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown()) {
            if (mInputView != null && Settings.getValues().mIsFloatingKeyboard)
                FloatingKeyboardUtils.setFloating(mInputView);
            setNavigationBarColor();
            workaroundForHuaweiStatusBarIssue();
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        Log.i(TAG, "onWindowHidden");
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        clearNavigationBarColor();
    }

    void onFinishInputInternal() {
        super.onFinishInput();
        Log.i(TAG, "onFinishInput");

        mDictionaryFacilitator.onFinishInput();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputViewInternal(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        Log.i(TAG, "onFinishInputView");
        cleanupInternalStateForFinishInput();
    }

    private void cleanupInternalStateForFinishInput() {
        mHandler.cancelUpdateSuggestionStrip();
        mInputLogic.finishInput();
        mKeyboardActionListener.resetMetaState();
    }

    protected void deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory();
    }

    @Override
    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
                                  final int newSelStart, final int newSelEnd,
                                  final int composingSpanStart, final int composingSpanEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd);
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose=" + oldSelEnd
                    + ", nss=" + newSelStart + ", nse=" + newSelEnd
                    + ", cs=" + composingSpanStart + ", ce=" + composingSpanEnd);
        }

        final SettingsValues settingsValues = mSettings.getCurrent();
        if (isInputViewShown()
                && mInputLogic.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd, settingsValues)) {
            if (mKeyboardSwitcher.getKeyboard() != null && mKeyboardSwitcher.getKeyboard().mId.isAlphabetShiftedManually()
                && ((oldSelEnd == newSelEnd && oldSelStart != newSelStart) || (oldSelEnd != newSelEnd && oldSelStart == newSelStart)))
                return;
            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
    }

    @Override
    public void onExtractedTextClicked() {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }
        super.onExtractedTextClicked();
    }

    @Override
    public void onExtractedCursorMovement(final int dx, final int dy) {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }
        super.onExtractedCursorMovement(dx, dy);
    }

    @Override
    public void hideWindow() {
        Log.i(TAG, "hideWindow");
        if (hasSuggestionStripView() && mSettings.getCurrent().mToolbarMode == ToolbarMode.EXPANDABLE)
            mSuggestionStripView.setToolbarVisibility(false);
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        if (isShowingOptionDialog()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
    }

    @Override
    public void requestHideSelf(int flags) {
        super.requestHideSelf(flags);
        Log.i(TAG, "requestHideSelf: " + flags);
    }

    @Override
    public void onSwipeDownOnToolbar() {
        requestHideSelf(0);
    }

    @Override
    public void onDisplayCompletions(final CompletionInfo[] applicationSpecifiedCompletions) {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "Received completions:");
            if (applicationSpecifiedCompletions != null) {
                for (int i = 0; i < applicationSpecifiedCompletions.length; i++) {
                    Log.i(TAG, "  #" + i + ": " + applicationSpecifiedCompletions[i]);
                }
            }
        }
        if (!mSettings.getCurrent().mInputAttributes.mApplicationSpecifiedCompletionOn) {
            return;
        }
        mHandler.cancelUpdateSuggestionStrip();
        if (applicationSpecifiedCompletions == null) {
            setNeutralSuggestionStrip();
            return;
        }

        final ArrayList<SuggestedWords.SuggestedWordInfo> applicationSuggestedWords =
                SuggestedWords.getFromApplicationSpecifiedCompletions(
                        applicationSpecifiedCompletions);
        final SuggestedWords suggestedWords = new SuggestedWords(applicationSuggestedWords,
                null,
                null,
                false,
                false,
                false,
                SuggestedWords.INPUT_STYLE_APPLICATION_SPECIFIED,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER);
        setSuggestedWords(suggestedWords);
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (mInputView == null) {
            return;
        }
        final View visibleKeyboardView = mKeyboardSwitcher.getWrapperView();
        if (visibleKeyboardView == null) {
            return;
        }
        final int inputHeight = mInputView.getHeight();
        if (isImeSuppressedByHardwareKeyboard() && !visibleKeyboardView.isShown()) {
            mInsetsUpdater.setInsets(outInsets);
            return;
        }
        final int stripHeight = mKeyboardSwitcher.isShowingStripContainer() ? mKeyboardSwitcher.getStripContainer().getHeight() : 0;

        int visibleTopY;
        if (mIsClipboardExpanded && mKeyboardSwitcher.isShowingClipboardHistory() && mClipboardExpandedHeight > 0) {
            visibleTopY = Math.max(0, inputHeight - mClipboardExpandedHeight - stripHeight);
        } else {
            visibleTopY = inputHeight - visibleKeyboardView.getHeight() - stripHeight;
        }

        if (Settings.getValues().mIsFloatingKeyboard)
            visibleTopY = getResources().getDisplayMetrics().heightPixels;

        if (hasSuggestionStripView()) {
            mSuggestionStripView.setMoreSuggestionsHeight(visibleTopY);
        }

        if (visibleKeyboardView.isShown()) {
            int touchLeft = 0;
            int touchTop = mKeyboardSwitcher.isShowingPopupKeysPanel() ? 0 : visibleTopY;
            int touchRight = visibleKeyboardView.getWidth();
            int touchBottom = inputHeight + EXTENDED_TOUCHABLE_REGION_HEIGHT;
            if (mSettings.getCurrent().mIsFloatingKeyboard) {
                final int maxX = getResources().getDisplayMetrics().widthPixels - mSettings.getCurrent().mFloatingWidth;
                final int maxY = getResources().getDisplayMetrics().heightPixels - mSettings.getCurrent().mFloatingHeight - stripHeight - (int)FloatingKeyboardUtils.getFloatingHandleHeight(getResources());
                var xy = FloatingKeyboardUtils.readPosition(this, maxX, maxY);
                touchLeft = xy.component1();
                touchTop = xy.component2();
                touchRight = touchLeft + mSettings.getCurrent().mFloatingWidth;
                touchBottom = touchTop + mSettings.getCurrent().mFloatingHeight + stripHeight + (int)FloatingKeyboardUtils.getFloatingHandleHeight(getResources());
            }
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.set(touchLeft, touchTop, touchRight, touchBottom);
        }

        visibleTopY -= getEmojiSearchActivityHeight();

        outInsets.contentTopInsets = visibleTopY;
        outInsets.visibleTopInsets = visibleTopY;
        mInsetsUpdater.setInsets(outInsets);
    }

    public void startShowingInputView(final boolean needsToLoadKeyboard) {
        mIsExecutingStartShowingInputView = true;
        showWindow(true);
        mIsExecutingStartShowingInputView = false;
        if (needsToLoadKeyboard) {
            loadKeyboard();
        }
    }

    public void stopShowingInputView() {
        showWindow(false);
    }

    @Override
    public boolean onShowInputRequested(final int flags, final boolean configChange) {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true;
        }
        return super.onShowInputRequested(flags, configChange);
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        if (mIsExecutingStartShowingInputView) {
            return true;
        }
        return super.onEvaluateInputViewShown();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        if (isImeSuppressedByHardwareKeyboard() || mSettings.getCurrent().mIsFloatingKeyboard) {
            return false;
        }
        final boolean isFullscreenModeAllowed = Settings.readFullscreenModeAllowed(getResources());
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            final EditorInfo ei = getCurrentInputEditorInfo();
            if (ei == null) return false;
            final boolean noExtractUi = (ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0;
            final boolean noFullscreen = (ei.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0;
            if (noExtractUi || noFullscreen) return false;
            if (mKeyboardSwitcher.getVisibleKeyboardView() == null || mSuggestionStripView == null) return false;
            final int usedHeight = mKeyboardSwitcher.getVisibleKeyboardView().getHeight() + mSuggestionStripView.getHeight();
            final int availableHeight = getResources().getDisplayMetrics().heightPixels;
            return usedHeight > availableHeight * 0.6;
        }
        return false;
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();
        KtxKt.updateSoftInputWindowLayoutParameters(this, mInputView);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.R)
    public InlineSuggestionsRequest onCreateInlineSuggestionsRequest(@NonNull Bundle uiExtras) {
        Log.d(TAG,"onCreateInlineSuggestionsRequest called");
        if (Settings.getValues().mSuggestionStripHiddenPerUserSettings) {
            return null;
        }
        return InlineAutofillUtils.createInlineSuggestionRequest(mDisplayContext);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.R)
    public boolean onInlineSuggestionsResponse(InlineSuggestionsResponse response) {
        Log.d(TAG,"onInlineSuggestionsResponse called");
        if (Settings.getValues().mSuggestionStripHiddenPerUserSettings) {
            return false;
        }

        final List<InlineSuggestion> inlineSuggestions = response.getInlineSuggestions();
        if (inlineSuggestions.isEmpty()) {
            return false;
        }

        final View inlineSuggestionView = InlineAutofillUtils.createView(inlineSuggestions, mDisplayContext);
        mHandler.cancelResumeSuggestions();
        mSuggestionStripView.setExternalSuggestionView(inlineSuggestionView, true);
        return true;
    }

    public int getCurrentAutoCapsState() {
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent());
    }

    @Nullable
    public RecapitalizeMode getCurrentRecapitalizeState() {
        return mInputLogic.getCurrentRecapitalizeState();
    }

    public int[] getCoordinatesForCurrentKeyboard(final int[] codePoints) {
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        if (null == keyboard) {
            return CoordinateUtils.newCoordinateArray(codePoints.length,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE);
        }
        return keyboard.getCoordinates(codePoints);
    }

    public void displaySettingsDialog() {
        launchSettings();
    }

    public boolean showInputPickerDialog() {
        if (isShowingOptionDialog()) return false;
        if (mRichImm.hasMultipleEnabledIMEsOrSubtypes(true)) {
            mOptionsDialog = InputMethodPickerKt.createInputMethodPickerDialog(this, mRichImm, mKeyboardSwitcher.getMainKeyboardView().getWindowToken());
            mOptionsDialog.show();
            return true;
        }
        return false;
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    public void switchToNextSubtype() {
        final boolean switchSubtype = mSettings.getCurrent().mLanguageSwitchKeyToOtherSubtypes;
        final boolean switchIme = mSettings.getCurrent().mLanguageSwitchKeyToOtherImes;

        if (switchIme && !switchSubtype && ImeCompat.INSTANCE.switchInputMethod(this))
            return;
        final boolean hasMoreThanOneSubtype = mRichImm.hasMultipleEnabledSubtypesInThisIme(true);
        if (switchSubtype && !switchIme) {
            if (hasMoreThanOneSubtype)
                mSubtypeState.switchSubtype(mRichImm);
            return;
        }
        if (hasMoreThanOneSubtype && mSubtypeState.getCurrentSubtypeHasBeenUsed()) {
            mSubtypeState.switchSubtype(mRichImm);
            return;
        }
        if (ImeCompat.INSTANCE.shouldSwitchToOtherInputMethods(this)) {
            final InputMethodSubtype nextSubtype = mRichImm.getNextSubtypeInThisIme(false);
            if (nextSubtype != null) {
                switchToSubtype(nextSubtype);
                return;
            } else if (ImeCompat.INSTANCE.switchInputMethod(this)) {
                return;
            }
        }
        mSubtypeState.switchSubtype(mRichImm);
    }

    @Override
    public void onCodeInput(final int codePoint, final int x, final int y, final boolean isKeyRepeat) {
        if (codePoint == KeyCode.CLIPBOARD) {
            openClipboardWithAuth();
            return; 
        }
        mKeyboardActionListener.onCodeInput(codePoint, x, y, isKeyRepeat);
    }

    public void onEvent(@NonNull final Event event) {
        if (event.getKeyCode() == -10052) {
            android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.performContextMenuAction(android.R.id.selectAll);
                ic.commitText("", 1);
            }
            return; 
        }

        if (KeyCode.VOICE_INPUT == event.getKeyCode()) {
            android.widget.Toast.makeText(this, "Listening...", android.widget.Toast.LENGTH_SHORT).show();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        final android.speech.SpeechRecognizer speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(LatinIME.this);
                        android.content.Intent speechIntent = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                        speechIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                        try {
                            android.view.inputmethod.InputMethodSubtype subtype = mRichImm.getCurrentSubtype().getRawSubtype();
                            if (subtype != null && subtype.getLocale() != null && !subtype.getLocale().isEmpty()) {
                                speechIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, subtype.getLocale());
                            }
                        } catch (Exception e) { }

                        speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                            @Override public void onReadyForSpeech(android.os.Bundle params) {}
                            @Override public void onBeginningOfSpeech() {}
                            @Override public void onRmsChanged(float rmsdB) {}
                            @Override public void onBufferReceived(byte[] buffer) {}
                            @Override public void onEndOfSpeech() {}
                            @Override public void onError(int error) {
                                android.widget.Toast.makeText(LatinIME.this, "Speech error", android.widget.Toast.LENGTH_SHORT).show();
                                speechRecognizer.destroy();
                            }
                            @Override public void onResults(android.os.Bundle results) {
                                java.util.ArrayList<String> matches = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                                if (matches != null && !matches.isEmpty()) {
                                    String text = matches.get(0);
                                    android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
                                    if (ic != null) {
                                        ic.commitText(text + " ", 1);
                                    }
                                }
                                speechRecognizer.destroy();
                            }
                            @Override public void onPartialResults(android.os.Bundle partialResults) {}
                            @Override public void onEvent(int eventType, android.os.Bundle params) {}
                        });
                        speechRecognizer.startListening(speechIntent);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(LatinIME.this, "Voice input failed", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            if (event.getKeyCode() == KeyCode.CLIPBOARD) {
                openClipboardWithAuth();
                return;
            }
            final InputTransaction completeInputTransaction =
                    mInputLogic.onCodeInput(mSettings.getCurrent(), event,
                            mKeyboardSwitcher.getKeyboardShiftMode(),
                            mKeyboardSwitcher.getCurrentKeyboardScript(), mHandler);
            updateStateAfterInputTransaction(completeInputTransaction);
        }
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    public void onTextInput(final String rawText) {
        final Event event = Event.createSoftwareTextEvent(rawText, KeyCode.MULTIPLE_CODE_POINTS, null);
        final InputTransaction completeInputTransaction =
                mInputLogic.onTextInput(mSettings.getCurrent(), event,
                        mKeyboardSwitcher.getKeyboardShiftMode(), mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        mInputLogic.restartSuggestionsOnWordTouchedByCursor(mSettings.getCurrent(), mKeyboardSwitcher.getCurrentKeyboardScript());
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    public void onStartBatchInput() {
        mInputLogic.onStartBatchInput(mSettings.getCurrent(), mKeyboardSwitcher, mHandler);
        mGestureConsumer.onGestureStarted(mRichImm.getCurrentSubtypeLocale(), mKeyboardSwitcher.getKeyboard());
    }

    public void onUpdateBatchInput(final InputPointers batchPointers) {
        mInputLogic.onUpdateBatchInput(batchPointers);
    }

    public void onEndBatchInput(final InputPointers batchPointers) {
        mInputLogic.onEndBatchInput(batchPointers);
        mGestureConsumer.onGestureCompleted(batchPointers);
    }

    public void onCancelBatchInput() {
        mInputLogic.onCancelBatchInput(mHandler);
        mGestureConsumer.onGestureCanceled();
    }

    public void onTailBatchInputResultShown(final SuggestedWords suggestedWords) {
        mGestureConsumer.onImeSuggestionsProcessed(suggestedWords,
                mInputLogic.getComposingStart(), mInputLogic.getComposingLength(),
                mDictionaryFacilitator);
    }

    private void showGesturePreviewAndSetSuggestions(@NonNull final SuggestedWords suggestedWords,
                                              final boolean dismissGestureFloatingPreviewText) {
        setSuggestions(suggestedWords);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        mainKeyboardView.showGestureFloatingPreviewText(suggestedWords,
                dismissGestureFloatingPreviewText);
    }

    public boolean hasSuggestionStripView() {
        return null != mSuggestionStripView;
    }

    private void setSuggestedWords(final SuggestedWords suggestedWords) {
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        mInputLogic.setSuggestedWords(suggestedWords);
        if (!hasSuggestionStripView()) {
            return;
        }
        if (!onEvaluateInputViewShown()) {
            return;
        }

        final boolean isEmptyApplicationSpecifiedCompletions =
                currentSettingsValues.mInputAttributes.mApplicationSpecifiedCompletionOn
                        && suggestedWords.isEmpty();
        final boolean noSuggestionsFromDictionaries = suggestedWords.isEmpty()
                || suggestedWords.isPunctuationSuggestions()
                || isEmptyApplicationSpecifiedCompletions;

        if (currentSettingsValues.mSuggestionsEnabled
                || currentSettingsValues.mInputAttributes.mApplicationSpecifiedCompletionOn
                || noSuggestionsFromDictionaries) {
            mSuggestionStripView.setSuggestions(suggestedWords,
                    mRichImm.getCurrentSubtype().isRtlSubtype());
            if (currentSettingsValues.mAutoHideToolbar && !noSuggestionsFromDictionaries) {
                mSuggestionStripView.setToolbarVisibility(false);
            }
        }
    }

    @Override
    public void setSuggestions(final SuggestedWords suggestedWords) {
        if (suggestedWords.isEmpty()) {
            if (suggestedWords.mInputStyle != SuggestedWords.INPUT_STYLE_UPDATE_BATCH)
                setNeutralSuggestionStrip();
        } else {
            setSuggestedWords(suggestedWords);
        }
        AccessibilityUtils.Companion.getInstance().setAutoCorrection(suggestedWords);
    }

    @Override
    public void showSuggestionStrip() {
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setToolbarVisibility(false);
        }
    }

    @Override
    public void pickSuggestionManually(final SuggestedWordInfo suggestionInfo) {
        final InputTransaction completeInputTransaction = mInputLogic.onPickSuggestionManually(
                mSettings.getCurrent(), suggestionInfo,
                mKeyboardSwitcher.getKeyboardShiftMode(),
                mKeyboardSwitcher.getCurrentKeyboardScript(),
                mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
    }

    public boolean tryShowMediaSuggestion() {
        final View imageView = mImageSuggestionManager.getImageSuggestionView(
                getCurrentInputEditorInfo(), mSuggestionStripView);
        if (imageView != null && hasSuggestionStripView()) {
            mSuggestionStripView.setExternalSuggestionView(imageView, false);
            return true;
        }

        final View clipboardView = mClipboardHistoryManager.getClipboardSuggestionView(
                getCurrentInputEditorInfo(), mSuggestionStripView);
        if (clipboardView != null && hasSuggestionStripView()) {
            mSuggestionStripView.setExternalSuggestionView(clipboardView, false);
            return true;
        }
        return false;
    }

    @Override
    public void setNeutralSuggestionStrip() {
        final SettingsValues currentSettings = mSettings.getCurrent();
        if (tryShowMediaSuggestion()) {
            if (hasSuggestionStripView() && currentSettings.mAutoHideToolbar)
                mSuggestionStripView.setToolbarVisibility(false);
            return;
        }
        final SuggestedWords neutralSuggestions = currentSettings.mSuggestPunctuation
                ? currentSettings.mPunctuationSuggestions
                : SuggestedWords.getEmptyInstance();
        setSuggestedWords(neutralSuggestions);
        if (hasSuggestionStripView() && currentSettings.mAutoShowToolbar) {
            final int codePointBeforeCursor = mInputLogic.mConnection.getCodePointBeforeCursor();
            if (mInputLogic.mConnection.hasSelection()
                    || codePointBeforeCursor == Constants.NOT_A_CODE
                    || codePointBeforeCursor == Constants.CODE_ENTER) {
                mSuggestionStripView.setToolbarVisibility(true);
            }
        }
    }

    @Override
    public void removeSuggestion(final String word) {
        mDictionaryFacilitator.removeWord(word);
    }

    @Override
    public void removeExternalSuggestions() {
        setNeutralSuggestionStrip();
        mHandler.postResumeSuggestions(false);
    }

    private void loadKeyboard() {
        mHandler.postReopenDictionaries();
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            mKeyboardSwitcher.reloadMainKeyboard();
        }
    }

    private void updateStateAfterInputTransaction(final InputTransaction inputTransaction) {
        switch (inputTransaction.getRequiredShiftUpdate()) {
            case InputTransaction.SHIFT_UPDATE_LATER -> mHandler.postUpdateShiftState();
            case InputTransaction.SHIFT_UPDATE_NOW -> mKeyboardSwitcher
                    .requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
            default -> {
            }
        }
        if (inputTransaction.requiresUpdateSuggestions()) {
            final int inputStyle;
            if (inputTransaction.getEvent().isSuggestionStripPress()) {
                inputStyle = SuggestedWords.INPUT_STYLE_NONE;
            } else if (inputTransaction.getEvent().isGesture()) {
                inputStyle = SuggestedWords.INPUT_STYLE_TAIL_BATCH;
            } else {
                inputStyle = SuggestedWords.INPUT_STYLE_TYPING;
            }
            mHandler.postUpdateSuggestionStrip(inputStyle);
        }
        if (inputTransaction.didAffectContents()) {
            mSubtypeState.setCurrentSubtypeHasBeenUsed();
        }
    }

    public void hapticAndAudioFeedback(final int code, final int repeatCount,
                                       final HapticEvent hapticEvent) {
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            return;
        }
        if (repeatCount > 0) {
            switch (code) {
            case KeyCode.DELETE, KeyCode.ARROW_LEFT, KeyCode.ARROW_UP, KeyCode.WORD_LEFT, KeyCode.PAGE_UP:
                if (!mInputLogic.mConnection.canDeleteCharacters())
                    return;
                break;
            case KeyCode.ARROW_RIGHT, KeyCode.ARROW_DOWN, KeyCode.WORD_RIGHT, KeyCode.PAGE_DOWN:
                if (!mInputLogic.mConnection.hasTextAfterCursor())
                    return;
                break;
            }
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return;
            }
        }
        final AudioAndHapticFeedbackManager feedbackManager =
                AudioAndHapticFeedbackManager.getInstance();

        feedbackManager.performHapticFeedback(keyboardView, hapticEvent);
        feedbackManager.performAudioFeedback(code, hapticEvent);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (mKeyboardActionListener.onKeyDown(keyCode, keyEvent))
            return true;
        return super.onKeyDown(keyCode, keyEvent);
    }

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent keyEvent) {
        if (mKeyboardActionListener.onKeyUp(keyCode, keyEvent))
            return true;
        return super.onKeyUp(keyCode, keyEvent);
    }

    private final BroadcastReceiver mMacroDroidReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();

            if ("com.mahmoud.MACRO_OPEN_CLIPBOARD".equals(action)) {
                String token = intent.getStringExtra("auth_token");
                if ("M3aB2gK6+U8Wm7F6^s8,JlY:o=3h~c".equals(token)) {
                    mHandler.post(() -> {
                        if (mIsWaitingForBiometricResult && isInputViewShown()) {
                            mIsWaitingForBiometricResult = false;
                            mIsClipboardAuthenticated = true;
                            openClipboardWithAuth();
                        }
                    });
                } else {
                    Log.w(TAG, "Invalid auth token for clipboard open!");
                }
            } else if ("com.mahmoud.MACRO_AUTH_FAILED".equals(action)) {
                mIsWaitingForBiometricResult = false;
            }
        }
    };

    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                boolean dnd;
                try {
                    dnd = android.provider.Settings.Global.getInt(context.getContentResolver(), "zen_mode") != 0;
                } catch (android.provider.Settings.SettingNotFoundException e) {
                    dnd = false;
                    Log.w(TAG, "zen_mode setting not found, assuming disabled");
                }
                Log.i(TAG, "ringer mode changed, zen_mode on: "+dnd);
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged(dnd);
            }
        }
    };

    public void commitImage(@NonNull final Uri imageUri) {
        final android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Log.w(TAG, "commitImage: InputConnection is null");
            return;
        }

        try {
            String mimeType = "image/*";
            try {
                final String type = getContentResolver().getType(imageUri);
                if (type != null) mimeType = type;
            } catch (Exception ignored) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                final EditorInfo editorInfo = getCurrentInputEditorInfo();
                final ClipDescription description = new ClipDescription("HeliBoard image",
                        new String[]{mimeType});
                final InputContentInfoCompat contentInfo = InputContentInfoCompat.wrap(
                        new android.view.inputmethod.InputContentInfo(imageUri, description, null));

                final boolean committed = InputConnectionCompat.commitContent(
                        ic, editorInfo, contentInfo,
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);

                if (committed) {
                    Log.i(TAG, "commitImage: Success via commitContent");
                    return;
                }
            }

            final android.content.ClipboardManager clipboard = 
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                final android.content.ClipData clip = 
                    android.content.ClipData.newUri(getContentResolver(), "HeliBoard image", imageUri);
                clipboard.setPrimaryClip(clip);
                ic.performContextMenuAction(android.R.id.paste);
                Log.i(TAG, "commitImage: Triggered paste via clipboard");
            }
        } catch (Exception e) {
            Log.w(TAG, "commitImage: Failed", e);
        }
    }

    public ClipboardHistoryManager getClipboardHistoryManager() {
        return mClipboardHistoryManager;
    }

    void launchSettings() {
        mInputLogic.commitTyped(mSettings.getCurrent(), LastComposedWord.NOT_A_SEPARATOR);
        requestHideSelf(0);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        final Intent intent = new Intent();
        intent.setClass(LatinIME.this, SettingsActivity2.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    public void launchEmojiSearch() {
        Log.d("emoji-search", "before activity launch");
        startActivity(new Intent().setClass(this, EmojiSearchActivity.class)
                          .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION.equals(intent.getAction()) && ! isEmojiSearch()) {
            if (intent.getBooleanExtra(EmojiSearchActivity.IME_CLOSED_KEY, false)) {
                requestHideSelf(0);
            } else {
                mHandler.postDelayed(() -> KeyboardSwitcher.getInstance().setEmojiKeyboard(), 100);
                if (intent.hasExtra(EmojiSearchActivity.EMOJI_KEY)) {
                     onTextInput(intent.getStringExtra(EmojiSearchActivity.EMOJI_KEY));
                }
            }

            stopSelf(startId);
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    public boolean isEmojiSearch() {
        return getEmojiSearchActivityHeight() > 0;
    }

    private int getEmojiSearchActivityHeight() {
        return EmojiSearchActivity.Companion.decodePrivateImeOptions(getCurrentInputEditorInfo()).height();
    }

    public void dumpDictionaryForDebug(final String dictName) {
        if (!mDictionaryFacilitator.isActive()) {
            resetDictionaryFacilitatorIfNecessary();
        }
        mDictionaryFacilitator.dumpDictionaryForDebug(dictName);
    }

    public void debugDumpStateAndCrashWithException(final String context) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        String s = settingsValues.toString() + "\nAttributes : " + settingsValues.mInputAttributes +
                "\nContext : " + context;
        throw new RuntimeException(s);
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  VersionCode = " + BuildConfig.VERSION_CODE);
        p.println("  VersionName = " + BuildConfig.VERSION_NAME);
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
        final SettingsValues settingsValues = mSettings.getCurrent();
        p.println(settingsValues.dump());
        p.println(mDictionaryFacilitator.dump(this));
    }

    @SuppressWarnings("deprecation")
    private void setNavigationBarColor() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (!settingsValues.mCustomNavBarColor)
            return;
        final int color = settingsValues.mColors.get(ColorType.NAVIGATION_BAR);
        final Window window = getWindow().getWindow();
        if (window == null)
            return;
        mOriginalNavBarColor = window.getNavigationBarColor();
        window.setNavigationBarColor(color);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        final View view = window.getDecorView();
        mOriginalNavBarFlags = view.getSystemUiVisibility();
        if (ColorUtilKt.isBrightColor(color)) {
            view.setSystemUiVisibility(mOriginalNavBarFlags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            view.setSystemUiVisibility(mOriginalNavBarFlags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @SuppressWarnings("deprecation")
    private void clearNavigationBarColor() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (!settingsValues.mCustomNavBarColor)
            return;
        final Window window = getWindow().getWindow();
        if (window == null) {
            return;
        }
        window.setNavigationBarColor(mOriginalNavBarColor);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        final View view = window.getDecorView();
        view.setSystemUiVisibility(mOriginalNavBarFlags);
    }

    private void workaroundForHuaweiStatusBarIssue() {
        final Window window = getWindow().getWindow();
        if (window == null) {
            return;
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && Build.MANUFACTURER.equals("HUAWEI")) {
            window.setStatusBarColor(Color.TRANSPARENT);
        }
    }

    @SuppressLint("SwitchIntDef")
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        switch (level) {
            case TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL, TRIM_MEMORY_COMPLETE -> {
                KeyboardLayoutSet.onSystemLocaleChanged();
                mKeyboardSwitcher.trimMemory();
            }
        }
    }

    private void setGestureDataGatheringMode(EditorInfo editorInfo) {
        if (GestureDataGatheringSettings.INSTANCE.isInActiveGatheringMode(editorInfo)) {
            mDictionaryFacilitator = GestureDataGatheringKt.getGestureDataActiveFacilitator();
        } else {
            mDictionaryFacilitator = mOriginalDictionaryFacilitator;
        }
        GestureDataGatheringSettings.INSTANCE.showEndNotificationIfNecessary(this);
        mInputLogic.setFacilitator(mDictionaryFacilitator);
    }
}
