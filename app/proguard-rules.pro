-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.modelcontextprotocol.**$$serializer { *; }
-keepclassmembers class io.modelcontextprotocol.** { *** Companion; }
-keepclasseswithmembers class io.modelcontextprotocol.** { kotlinx.serialization.KSerializer serializer(...); }
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
