# Proguard rules for iTantra
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# JNI entry points and result objects used by the optional translation bridge.
-keep class com.itantra.core.translation.CTranslate2TranslationEngine { *; }
-keep class com.itantra.core.translation.NativeTranslationResult { *; }

# Remove release logging, including unconditional ML Kit token log calls.
# Supply a return value too: Kotlin can box the logging return in a coroutine.
# Debug logs remain available; JNI names and fields stay preserved above.
-assumenosideeffects class android.util.Log {
    public static int v(...) return 0;
    public static int d(...) return 0;
    public static int i(...) return 0;
    public static int w(...) return 0;
    public static int e(...) return 0;
    public static int wtf(...) return 0;
    public static int println(...) return 0;
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace(...);
}
