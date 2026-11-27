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

# Optional codecs in commons-compress not used by OfflineModelInstaller (uses Tar + BZip2 only)
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.osgi.**
-dontwarn org.apache.commons.compress.compressors.brotli.**
-dontwarn org.apache.commons.compress.compressors.zstandard.**
-dontwarn org.apache.commons.compress.harmony.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
