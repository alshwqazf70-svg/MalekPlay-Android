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

# Android does not provide the optional Java SE Expression Language API.
# MBassador's EL-based filtering is not used by the app; keep the rest of
# the SMB/event-bus functionality available to R8.
-dontwarn javax.el.**

# GSS/Kerberos support in SMBJ relies on the Java SE GSS-API, which is not
# part of the Android runtime. The app's SMB login uses supported mechanisms.
-dontwarn org.ietf.jgss.**
