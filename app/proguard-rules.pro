# Keep runtime-visible annotations and generic signatures used by reflection-based libraries.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Preserve native method names/signatures if JNI-backed components are introduced.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ML Kit Translate uses runtime-loaded/native components; retain its public API entry points.
-keep class com.google.mlkit.** { public *; }

# Hilt/Dagger generated components are referenced through generated Android entry points.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# Room entities/DAOs can be accessed by generated code and schema metadata.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
