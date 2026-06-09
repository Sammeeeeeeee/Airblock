# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.sam.airblock.** {
    *** Companion;
}
-keepclasseswithmembers class com.sam.airblock.** {
    kotlinx.serialization.KSerializer serializer(...);
}
