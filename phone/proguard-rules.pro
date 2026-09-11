# kotlinx.serialization ships its own R8 rules for @Serializable classes; these
# keep our serializers explicitly anyway, for every package that persists or
# sends JSON (phone<->watch protocol, match state, calibration, serve results).
# A stripped serializer fails at runtime, not at build time.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.tennispro.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.tennispro.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# MediaPipe's AAR ships no consumer rules, and its native graph reaches back
# into Java classes and protobuf messages by name over JNI — renaming any of
# them crashes pose detection only in release builds.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# MediaPipe logs through Flogger, which finds its caller by walking the stack
# for its own class names. Renamed, it threw "no caller found on the stack" from
# mediapipe.framework.Graph's static initialiser: every serve scan crashed the
# app in release builds only (found on the S25 Ultra, 2026-09-10).
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# Verbose/debug logging is for development; warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
