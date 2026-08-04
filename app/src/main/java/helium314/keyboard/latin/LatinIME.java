/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package helium314.keyboard.latin;

import static helium314.keyboard.keyboard.KeyCode.CLIPBOARD;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.text.InputType;
import android.text.style.CharacterStyle;
import android.text.style.UnderlineSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import java.util.ArrayList;
import java.util.Locale;

import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.common.FileUtils;
import helium314.keyboard.latin.common.InputPointers;
import helium314.keyboard.latin.common.LocaleUtils;
import helium314.keyboard.latin.common.StringUtils;
import helium314.keyboard.latin.inputlogic.InputLogic;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.spellcheck.AndroidSpellCheckerSession;
import helium314.keyboard.latin.spellcheck.DictionaryFacilitator;
import helium314.keyboard.latin.spellcheck.DictionaryFacilitatorProvider;
import helium314.keyboard.latin.spellcheck.PersonalizationDictionarySessionRegister;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.suggestions.SuggestionStripViewAccessor;
import helium314.keyboard.latin.utils.ClipboardHistoryManager;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.ImageSuggestionManager;
import helium314.keyboard.latin.utils.JniUtils;
import helium314.keyboard.latin.utils.ScriptUtils;
import helium314.keyboard.latin.utils.StatsUtilsManager;
import helium314.keyboard.latin.utils.UncachedBloomFilter;
import helium314.keyboard.settings.screens.theme.KeyboardTheme;
import helium314.keyboard.compat.RichInputMethodManager;
import helium314.keyboard.compat.RichInputMethodSubtype;
import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.KeyCode;

public class LatinIME extends InputMethodService implements
        SuggestionStripView.Listener, SuggestionStripViewAccessor,
        DictionaryFacilitator.DictionaryInitializationListener {
    private static final String TAG = LatinIME.class.getSimpleName();
    private static final int EXTENDED_TOUCH_SLOP = 150;
    private static final int NOT_A_SUBTYPE_ID = -1;
    private static final int DEFAULT_LANGUAGE_SUBTYPE_ID = 0;

    final Settings mSettings;
    public final KeyboardActionListener mKeyboardActionListener;

    private boolean mInCursorMode = false;
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

    // Loading the native library eagerly to avoid unexpected UnsatisfiedLinkError at the initial
    // JNI call as much as possible.
    static {
        JniUtils.loadNativeLibrary();
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mStatsUtilsManager = StatsUtilsManager.getInstance();
        
        // Wrap the keyboard action listener to intercept space swipe in cursor mode
        final KeyboardActionListenerImpl delegate = new KeyboardActionListenerImpl(this, mInputLogic);
        mKeyboardActionListener = new KeyboardActionListener() {
            @Override
            public void onPressKey(int primaryCode, int repeatCount, boolean isSinglePointer, HapticEvent hapticEvent) {
                delegate.onPressKey(primaryCode, repeatCount, isSinglePointer, hapticEvent);
            }
            @Override
            public void onLongPressKey(int primaryCode) {
                delegate.onLongPressKey(primaryCode);
            }
            @Override
            public void onReleaseKey(int primaryCode, boolean withSliding) {
                delegate.onReleaseKey(primaryCode, withSliding);
            }
            @Override
            public boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
                return delegate.onKeyDown(keyCode, keyEvent);
            }
            @Override
            public boolean onKeyUp(int keyCode, KeyEvent keyEvent) {
                return delegate.onKeyUp(keyCode, keyEvent);
            }
            @Override
            public void onCodeInput(int primaryCode, int x, int y, boolean isKeyRepeat) {
                delegate.onCodeInput(primaryCode, x, y, isKeyRepeat);
            }
            @Override
            public void onTextInput(String text) {
                delegate.onTextInput(text);
            }
            @Override
            public void onStartBatchInput() {
                delegate.onStartBatchInput();
            }
            @Override
            public void onUpdateBatchInput(InputPointers batchPointers) {
                delegate.onUpdateBatchInput(batchPointers);
            }
            @Override
            public void onEndBatchInput(InputPointers batchPointers) {
                delegate.onEndBatchInput(batchPointers);
            }
            @Override
            public void onCancelBatchInput() {
                delegate.onCancelBatchInput();
            }
            @Override
            public void onCancelInput() {
                delegate.onCancelInput();
            }
            @Override
            public void onFinishSlidingInput() {
                delegate.onFinishSlidingInput();
            }
            @Override
            public boolean onCustomRequest(int requestCode) {
                return delegate.onCustomRequest(requestCode);
            }
            @Override
            public boolean onHorizontalSpaceSwipe(int steps) {
                if (mInCursorMode) {
                    mInputLogic.mSuggestedWords = SuggestedWords.getEmpty();
                    mKeyboardSwitcher.updateShiftState();
                    return mInputLogic.onHorizontalSpaceSwipe(steps);
                }
                return delegate.onHorizontalSpaceSwipe(steps);
            }
            @Override
            public boolean onVerticalSpaceSwipe(int steps) {
                return delegate.onVerticalSpaceSwipe(steps);
            }
            @Override
            public void onEndSpaceSwipe() {
                delegate.onEndSpaceSwipe();
            }
            @Override
            public boolean toggleNumpad(boolean withSliding, boolean forceReturnToAlpha) {
                return delegate.toggleNumpad(withSliding, forceReturnToAlpha);
            }
            @Override
            public void onMoveDeletePointer(int steps) {
                delegate.onMoveDeletePointer(steps);
            }
            @Override
            public void onUpWithDeletePointerActive() {
                delegate.onUpWithDeletePointerActive();
            }
            @Override
            public void resetMetaState() {
                delegate.resetMetaState();
            }
            @Override
            public void onEnterCursorMode() {
                mInCursorMode = true;
                delegate.onEnterCursorMode();
            }
            @Override
            public void onExitCursorMode() {
                mInCursorMode = false;
                delegate.onExitCursorMode();
            }
            @Override
            public boolean commitImage(Uri uri) {
                LatinIME.this.commitImage(uri);
                return true;
            }
            @Override
            public void onContent(InputContentInfoCompat content) {
                delegate.onContent(content);
            }
        };
    }

    @Override
    public void onCreate() {
        Settings.init(this);
        RichInputMethodManager.init(this);
        StatsUtilsManager.init(this);
        super.onCreate();
        mRichImm = RichInputMethodManager.getInstance();
        mKeyboardSwitcher.initInternal(this);
        
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        registerReceiver(mDictionaryPackInstallReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        
        final IntentFilter dictionaryDumpFilter = new IntentFilter();
        dictionaryDumpFilter.addAction("android.intent.action.VIEW");
        registerReceiver(mDictionaryDumpBroadcastReceiver, dictionaryDumpFilter, Context.RECEIVER_NOT_EXPORTED);
        
        final IntentFilter ringerModeIntentFilter = new IntentFilter();
        ringerModeIntentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, ringerModeIntentFilter, Context.RECEIVER_NOT_EXPORTED);
        
        PersonalizationDictionarySessionRegister.init(this);
        FoldableUtils.setFoldableObserver(this);
    }

    private void loadSettings() {
        mSettings.loadSettings(this, LocaleUtils.getLocaleUsedForResources(getResources()), 
                EditorInfoCompat.getEditorInfoAttribute(null));
        mSettings.getCurrent().mHasHardwareKeyboard = hasHardwareKeyboard();
    }

    private void refreshPersonalizationDictionarySession(
            final SettingsValues currentSettingsValues) {
        final Locale locale = currentSettingsValues.mLocale;
        PersonalizationDictionarySessionRegister.onLocaleSelected(this, locale);
    }

    @Override
    protected void onDestroy() {
        mDictionaryFacilitator.closeAll();
        unregisterReceiver(mDictionaryPackInstallReceiver);
        unregisterReceiver(mDictionaryDumpBroadcastReceiver);
        unregisterReceiver(mRingerModeChangeReceiver);
        PersonalizationDictionarySessionRegister.onInputMethodDestroyed();
        StatsUtilsManager.onInputMethodDestroyed();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        loadSettings();
        mDictionaryFacilitator.onConfigurationChanged(conf);
        if (mDisplayContext != null) {
            mKeyboardSwitcher.updateKeyboardTheme(mDisplayContext);
        }
        super.onConfigurationChanged(conf);
    }

    private boolean hasHardwareKeyboard() {
        return getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS;
    }

    @Override
    public View onCreateInputView() {
        mDisplayContext = new ContextThemeWrapper(this, KeyboardTheme.getThemeId(this));
        return mKeyboardSwitcher.onCreateInputView(mDisplayContext, mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);
        mHandler.onStartInputView(editorInfo, restarting);
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        mHandler.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(final InputMethodSubtype subtype) {
        super.onCurrentInputMethodSubtypeChanged(subtype);
        mRichImm.onSubtypeChanged(subtype);
        mKeyboardSwitcher.onSubtypeChanged(subtype);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues.mWindowLightNavigationBar) {
            final Window window = getWindow().getWindow();
            if (window != null) {
                final int flags = window.getDecorView().getSystemUiVisibility();
                window.getDecorView().setSystemUiVisibility(
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                                flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR :
                                flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }
        return super.onEvaluateFullscreenMode();
    }

    @Override
    public void onCodeInput(final int codePoint, final int x, final int y, final boolean isKeyRepeat) {
        if (codePoint == CLIPBOARD) {
            mKeyboardSwitcher.setClipboardKeyboard();
            return;
        }
        mKeyboardActionListener.onCodeInput(codePoint, x, y, isKeyRepeat);
    }

    @Override
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
            mHandler.post(() -> {
                try {
                    final android.speech.SpeechRecognizer speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(LatinIME.this);
                    android.content.Intent speechIntent = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    speechIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    
                    try {
                        android.view.inputmethod.InputMethodSubtype subtype = mRichImm.getCurrentSubtype().getRawSubtype();
                        if (subtype != null && subtype.getLocale() != null && !subtype.getLocale().isEmpty()) {
                            speechIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, subtype.getLocale());
                        }
                    } catch (Exception e) {
                    }

                    speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                        @Override public void onReadyForSpeech(android.os.Bundle params) {}
                        @Override public void onBeginningOfSpeech() {}
                        @Override public void onRmsChanged(float rmsdB) {}
                        @Override public void onBufferReceived(byte[] buffer) {}
                        @Override public void onEndOfSpeech() {}
                        @Override public void onError(int error) {
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
                }
            });
            return;
        }

        mKeyboardActionListener.onEvent(event);
    }

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
        startActivity(new Intent().setClass(this, EmojiSearchActivity.class)
                          .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null || mInputView == null) {
            return;
        }
        final int[] windowLocation = new int[2];
        mInputView.getLocationInWindow(windowLocation);
        outInsets.contentTopInsets = windowLocation[1];
        outInsets.visibleTopInsets = windowLocation[1];
    }

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 1;
        private static final int MSG_RESET_CACHES = 2;
        private static final int MSG_WAIT_FOR_DICTIONARY_LOAD = 3;
        private static final int MSG_DEALLOCATE_MEMORY = 4;
        private static final int MSG_UPDATE_SUGGESTIONS = 5;

        public UIHandler(final LatinIME latinIme) {
            super(latinIme);
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            latinIme.mKeyboardSwitcher.loadKeyboard(editorInfo,
                    latinIme.mSettings.getCurrent(), 0, null, null);
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            latinIme.mIsExecutingStartShowingInputView = true;
            latinIme.mKeyboardSwitcher.updateShiftState();
            latinIme.mIsExecutingStartShowingInputView = false;
        }

        public void onFinishInputView(final boolean finishingInput) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            final MainKeyboardView mainKeyboardView = latinIme.mKeyboardSwitcher.getMainKeyboardView();
            if (mainKeyboardView != null) {
                mainKeyboardView.closing();
            }
        }

        public void onFinishInput() {
        }
    }

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
                }
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged(dnd);
            }
        }
    };
}
