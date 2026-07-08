package helium314.keyboard.keyboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

public class BiometricAuthActivity extends Activity {
    private static final String TAG = "MacBoardBio";

    // Static callback to communicate with LatinIME
    public static BiometricCallback sCallback = null;

    public interface BiometricCallback {
        void onSuccess();
        void onFailure();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "BiometricAuthActivity onCreate");

        // Don't set content view - keep it transparent

        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    Log.d(TAG, "Biometric SUCCESS");
                    if (sCallback != null) {
                        sCallback.onSuccess();
                    }
                    finishAndClear();
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Log.d(TAG, "Biometric FAILED");
                    if (sCallback != null) {
                        sCallback.onFailure();
                    }
                    finishAndClear();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Log.d(TAG, "Biometric ERROR: " + errorCode + " - " + errString);
                    if (sCallback != null) {
                        sCallback.onFailure();
                    }
                    finishAndClear();
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("🔒 MacBoard")
            .setSubtitle("استخدم بصمة الإصبع للوصول للحافظة")
            .setNegativeButtonText("إلغاء")
            .setConfirmationRequired(false)
            .build();

        try {
            biometricPrompt.authenticate(promptInfo);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show biometric prompt", e);
            if (sCallback != null) {
                sCallback.onFailure();
            }
            finishAndClear();
        }
    }

    private void finishAndClear() {
        sCallback = null;
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // If activity is paused without auth result, treat as failure
        if (sCallback != null) {
            sCallback.onFailure();
            sCallback = null;
        }
        finish();
    }
}
