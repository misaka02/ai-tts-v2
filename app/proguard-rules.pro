-keepattributes *Annotation*,Signature
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
