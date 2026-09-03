# kotlinx.serialization keeps generated serializers on the companion; R8 needs
# these hints or the phone<->watch protocol silently fails in release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.tennispro.core.protocol.**$$serializer { *; }
-keepclassmembers class com.tennispro.core.protocol.** {
    *** Companion;
}
-keepclasseswithmembers class com.tennispro.core.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
