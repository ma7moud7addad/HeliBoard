package helium314.keyboard.latin;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

public class BiometricActivity extends FragmentActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                finish(); // لو حصل خطأ أو المستخدم قفلها، الشاشة الشفافة تختفي
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // البصمة نجحت! نبعت إشارة سرية للكيبورد عشان يفتح الحافظة
                Intent intent = new Intent(BiometricActivity.this, LatinIME.class);
                intent.setAction("com.mahmoud.OPEN_CLIPBOARD_NATIVE");
                startService(intent);
                finish(); // نقفل الشاشة الشفافة
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                // لو البصمة غلط، هيفضل فاتح يخليه يجرب تاني
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("MacBoard")
                .setSubtitle("قم بتأكيد البصمة لفتح الحافظة")
                .setNegativeButtonText("إلغاء")
                .build();

        biometricPrompt.authenticate(promptInfo);
    }
}
