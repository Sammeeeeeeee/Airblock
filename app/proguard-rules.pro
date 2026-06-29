# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.sam.airblock.** {
    *** Companion;
}
-keepclasseswithmembers class com.sam.airblock.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# androidx.security:security-crypto rides on Google Tink, whose protobuf-backed
# key types are loaded reflectively — R8 must not strip or rename them, or
# EncryptedSharedPreferences fails to open the keyset on release builds.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
