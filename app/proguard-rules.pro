# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.degoogle.app.**$$serializer { *; }
-keepclassmembers class dev.degoogle.app.** { *** Companion; }
-keepclasseswithmembers class dev.degoogle.app.** { kotlinx.serialization.KSerializer serializer(...); }
