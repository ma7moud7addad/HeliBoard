@@
 class KeyboardActionListenerImpl(private val latinIME: LatinIME, private val inputLogic: InputLogic) : KeyboardActionListener {
@@
     override fun onContent(content: InputContentInfoCompat) {
@@
     }
+
+    override fun commitImage(uri: android.net.Uri): Boolean {
+        return try {
+            latinIME.commitImage(uri)
+            true
+        } catch (e: Exception) {
+            // optional: could log the exception
+            false
+        }
+    }
@@
 }
