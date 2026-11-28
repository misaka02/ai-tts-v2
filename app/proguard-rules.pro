-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

-keep,includedescriptorclasses class com.aitts.v2.model.**$$serializer { *; }
-keepclassmembers class com.aitts.v2.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.aitts.v2.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.aitts.v2.reader.model.**$$serializer { *; }
-keepclassmembers class com.aitts.v2.reader.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.aitts.v2.reader.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.aitts.v2.reader.**$$serializer { *; }
-keepclassmembers class com.aitts.v2.reader.** {
    *** Companion;
}
-keepclasseswithmembers class com.aitts.v2.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.aitts.v2.engine.cloud.**$$serializer { *; }
-keepclassmembers class com.aitts.v2.engine.cloud.** {
    *** Companion;
}
-keepclasseswithmembers class com.aitts.v2.engine.cloud.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class org.readium.** { *; }
-dontwarn org.readium.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.google.android.gms.auth.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn org.jsoup.**

# Optional codecs in commons-compress not used by OfflineModelInstaller (uses Tar + BZip2 only)
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.osgi.**
-dontwarn org.apache.commons.compress.compressors.brotli.**
-dontwarn org.apache.commons.compress.compressors.zstandard.**
-dontwarn org.apache.commons.compress.harmony.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
