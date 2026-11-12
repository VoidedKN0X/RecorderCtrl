# kotlinx.serialization models (library ships its own rules; these keep saved history and routes intact).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.vknxworks.recorderctrl.**$$serializer { *; }
-keepclassmembers class com.vknxworks.recorderctrl.** {
    *** Companion;
}
-keepclasseswithmembers class com.vknxworks.recorderctrl.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google-free: Guava's listenablefuture jar is excluded from the build (see app/build.gradle.kts).
# AndroidX's concurrent-futures still names its interface in classes RecorderCtrl never uses.
-dontwarn com.google.common.util.concurrent.ListenableFuture
