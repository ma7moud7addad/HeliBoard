package helium314.keyboard.keyboard;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

public class BiometricAuthActivity extends AppCompatActivity {

    public static final String ACTION_AUTH_RESULT = "helium314.keyboard.BIOMETRIC_RESULT";
    public static final String EXTRA_SUCCESS = "success";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    sendResult(true);
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    sendResult(false);
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    sendResult(false);
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("🔒 MacBoard - الحافظة المحمية")
            .setSubtitle("استخدم بصمة الإصبع للوصول")
            .setNegativeButtonText("إلغاء")
            .setConfirmationRequired(false)
            .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void sendResult(boolean success) {
        Intent intent = new Intent(ACTION_AUTH_RESULT);
        intent.putExtra(EXTRA_SUCCESS, success);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        finish();
    }
}
