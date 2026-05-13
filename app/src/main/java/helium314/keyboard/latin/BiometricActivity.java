package helium314.keyboard.latin;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

public class BiometricActivity extends FragmentActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // السر الحقيقي للشركات الكبرى: جعل الشاشة "شبح" لا تسحب التركيز أبداً
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                             WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                             WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                             
        super.onCreate(savedInstanceState);
        
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                finish(); // نقفل الشبح
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // البصمة نجحت! نبعت إشارة للكيبورد يفتح الحافظة
                Intent intent = new Intent(BiometricActivity.this, LatinIME.class);
                intent.setAction("com.mahmoud.OPEN_CLIPBOARD_NATIVE");
                startService(intent);
                finish(); // نقفل الشبح
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
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
