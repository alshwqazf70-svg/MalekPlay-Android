# Keep native libass/JNI entry points used by the ASS Kotlin wrapper.
-keep class io.github.peerless2012.ass.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
