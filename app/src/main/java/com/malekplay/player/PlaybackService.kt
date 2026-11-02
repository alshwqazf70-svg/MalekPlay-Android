package com.malekplay.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.malekplay.MainActivity
import com.malekplay.network.NetworkCredentialVault
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * خدمة مشغل الوسائط الاحترافية وإدارة الجلسات في خلفية النظام (MediaSessionService)
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 1. عميل شبكة فائق السرعة والأمان لبث الفيديوهات من السيرفرات المنزلية
        val httpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(httpClient))
        val vault = NetworkCredentialVault(this)

        // حل مسارات وبيانات اعتماد WebDAV المحمية
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            val uri = dataSpec.uri
            if (!uri.scheme.equals("https", true) || uri.userInfo != null) {
                dataSpec
            } else {
                val raw = getSharedPreferences("malekplay_network_profiles", MODE_PRIVATE)
                    .getString("profiles", "[]") ?: "[]"
                val profiles = runCatching { JSONArray(raw) }.getOrNull()
                var authorization: String? = null
                if (profiles != null) {
                    for (index in 0 until profiles.length()) {
                        val profile = profiles.optJSONObject(index) ?: continue
                        if (!profile.optString("protocol").equals("WEBDAV", true)) continue
                        val host = profile.optString("host")
                        val port = profile.optInt("port", 443)
                        val uriPort = if (uri.port == -1) 443 else uri.port
                        if (host.equals(uri.host, true) && port == uriPort) {
                            val credentials = runCatching { vault.load(profile.optString("id")) }.getOrNull()
                            if (credentials != null) {
                                authorization = Credentials.basic(credentials.first, credentials.second)
                            }
                            break
                        }
                    }
                }
                if (authorization == null) dataSpec
                else dataSpec.buildUpon()
                    .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("Authorization" to authorization!!))
                    .build()
            }
        }

        val resolvingFactory = ResolvingDataSource.Factory(upstream, resolver)

        // 2. ضبط الذاكرة المؤقتة للتشغيل الفوري (سر MX Player في فتح المقاطع بنصف ثانية)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // الحد الأدنى للذاكرة المؤقتة (15 ثانية)
                50_000, // الحد الأقصى للذاكرة المؤقتة (50 ثانية)
                500,    // البدء الفوري بعد تحميل نصف ثانية فقط!
                1_000   // إعادة التشغيل السريع بعد التقطيع
            )
            .build()

        // 3. بناء المشغل مع حماية استمرار التشغيل في الخلفية
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true // تركيز الصوت التلقائي
                )
                setHandleAudioBecomingNoisy(true) // الإيقاف التلقائي عند فصل السماعة
                setWakeMode(C.WAKE_MODE_NETWORK) // منع النظام من إيقاف المشغل عند قفل الشاشة
            }

        // 4. ربط الإشعار بفتح التطبيق عند الضغط عليه
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
