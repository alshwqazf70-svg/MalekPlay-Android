# ==========================================
# 1. Media3 & ExoPlayer (مشغل الفيديو والصوت)
# ==========================================
-keep class androidx.media3.** { *; }
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
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.** { *; }
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
