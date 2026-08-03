@@
     // --- التعديل النهائي والمدمر: دمج حلول الخبراء الثلاثة ---
     override fun onKeyUp(clipId: Long) {
-        val clipContent = clipboardHistoryManager.getHistoryEntryContent(clipId) ?: return
-
-        if (clipContent.filename != null) {
-            try {
-                // 1. حل مشكلة الاسم الغلط (Authority Mismatch) اللي اكتشفها Copilot
-                val file = File(context.filesDir, "clipfiles/${clipContent.filename}")
-                val authority = "com.macboard.keyboard.latin.provider" // الاسم الصحيح 100% من الـ Manifest
-                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
-
-                val latinIME = KeyboardSwitcher.getInstance().latinIME
-                if (latinIME != null && uri != null) {
-                    
-                    // 2. حل مشكلة الصلاحيات اللي اكتشفها Kimi (إعطاء تصريح إجباري للواتساب)
-                    val targetPackage = latinIME.currentInputEditorInfo?.packageName
-                    if (targetPackage != null) {
-                        context.grantUriPermission(targetPackage, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
-                    }
-
-                    // 3. إرسال الصورة باستخدام الدالة القوية بتاعتك اللي بتعمل اللصق الإجباري
-                    latinIME.commitImage(uri)
-                } else {
-                    keyboardActionListener.onTextInput(clipContent.text)
-                }
-            } catch (e: Exception) {
-                keyboardActionListener.onTextInput(clipContent.text)
-            }
-        } else {
-            keyboardActionListener.onTextInput(clipContent.text)
-        }
+        val clipContent = clipboardHistoryManager.getHistoryEntryContent(clipId) ?: return
+
+        if (clipContent.filename != null) {
+            try {
+                val file = File(context.filesDir, "clipfiles/${clipContent.filename}")
+                val uri = androidx.core.content.FileProvider.getUriForFile(
+                    context,
+                    "com.macboard.keyboard.latin.provider",
+                    file
+                )
+
+                val committed = try {
+                    keyboardActionListener.commitImage(uri)
+                } catch (e: Exception) {
+                    false
+                }
+
+                if (!committed) {
+                    keyboardActionListener.onTextInput(clipContent.text.toString())
+                }
+            } catch (e: Exception) {
+                keyboardActionListener.onTextInput(clipContent.text.toString())
+            }
+        } else {
+            keyboardActionListener.onTextInput(clipContent.text.toString())
+        }
@@
 }
