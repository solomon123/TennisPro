# kotlinx.serialization ships its own R8 rules for @Serializable classes; these
# keep our serializers explicitly anyway. A stripped serializer fails at
# runtime, not at build time — the phone<->watch protocol would go silent.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.tennispro.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.tennispro.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Verbose/debug logging is for development; warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
