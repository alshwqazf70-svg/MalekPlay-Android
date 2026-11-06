# ==========================================
# 1. Media3 & ExoPlayer
# ==========================================
# Media3 AARs provide their own consumer rules. Do not keep the entire
# namespace: that prevents R8 from shrinking unused player/UI classes.
-dontwarn androidx.media3.**

# ==========================================
# 2. Hilt & Dependency Injection (حقن التبعيات)
# ==========================================
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-dontwarn dagger.hilt.**

# ==========================================
# 3. Google ML Kit (ترجمة الذكاء الاصطناعي الفورية)
# ==========================================
# ML Kit / Play Services AARs ship consumer rules for reflective entry points.
# Keeping every class in these namespaces retains unused SDK implementation.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ==========================================
# 4. Room Database (قاعدة البيانات والمجلدات)
# ==========================================
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class com.malekplay.data.local.entity.** { *; }

# ==========================================
# 5. Network Streaming (SMBJ, SSHJ, OkHttp, WebDAV)
# ==========================================
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep class net.schmizz.** { *; }
-dontwarn net.schmizz.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jzlib.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ==========================================
# 6. General Kotlin & Coroutines
# ==========================================
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlinx.coroutines.**
-keepclassmembers enum * { *; }

# Optional Java Expression Language integration in the transitive MBassador dependency.
# MalekPlay does not use EL-based event filters; R8 can safely omit this optional path.
-dontwarn javax.el.**
