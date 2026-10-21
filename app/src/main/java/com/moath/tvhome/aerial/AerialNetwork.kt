package com.moath.tvhome.aerial

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.moath.tvhome.R
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Process-wide network plumbing for the screensaver.
 *
 * TLS: sylvan.apple.com is issued by "Apple Server Authentication CA" → "Apple Root CA", and
 * Apple Root CA is not in Android's trust store (absent even on API 34). Rather than weakening
 * anything globally, this client trusts the system store **plus** the bundled Apple Root CA
 * (res/raw/aerial_apple_root_ca.pem, SHA-256 B0:B1:73:0E:…:F0:24), and a network interceptor
 * refuses any host outside apple.com. Hostname verification stays on.
 */
@UnstableApi
internal object AerialNetwork {
    private const val TAG = "Aerial"
    private const val CACHE_DIR = "aerial_video"
    private const val CACHE_MAX_BYTES = 400L * 1024 * 1024
    private const val CACHE_MIN_BYTES = 64L * 1024 * 1024

    @Volatile private var client: OkHttpClient? = null
    @Volatile private var cache: SimpleCache? = null

    fun client(context: Context): OkHttpClient =
        client ?: synchronized(this) { client ?: buildClient(context.applicationContext).also { client = it } }

    /** SimpleCache must be a singleton per directory, so it lives for the process. */
    fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) { cache ?: buildCache(context.applicationContext).also { cache = it } }

    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(AerialHttpDataSource.Factory(client(context)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun isAllowedHost(host: String): Boolean = host == "apple.com" || host.endsWith(".apple.com")

    private fun buildCache(context: Context): SimpleCache {
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.mkdirs()
        // The box has little storage: never take more than a quarter of what is free.
        val budget = (dir.usableSpace / 4).coerceIn(CACHE_MIN_BYTES, CACHE_MAX_BYTES)
        return SimpleCache(dir, LeastRecentlyUsedCacheEvictor(budget), StandaloneDatabaseProvider(context))
    }

    private fun buildClient(context: Context): OkHttpClient {
        val trust = AppleAwareTrustManager(systemTrustManager(null), systemTrustManager(appleRootStore(context)))
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .addNetworkInterceptor(Interceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps || !isAllowedHost(url.host)) throw IOException("Aerial client refuses ${url.host}")
                chain.proceed(chain.request())
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun appleRootStore(context: Context): KeyStore {
        val cert = context.resources.openRawResource(R.raw.aerial_apple_root_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("apple_root_ca", cert)
        }
    }

    private fun systemTrustManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** System trust first; only if that fails, a chain anchored at Apple Root CA is accepted. */
    @SuppressLint("CustomX509TrustManager")
    private class AppleAwareTrustManager(
        private val system: X509TrustManager,
        private val apple: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (systemFailure: CertificateException) {
                try {
                    apple.checkServerTrusted(chain, authType)
                } catch (appleFailure: CertificateException) {
                    Log.w(TAG, "Untrusted chain for ${chain.firstOrNull()?.subjectX500Principal}", appleFailure)
                    throw systemFailure
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + apple.acceptedIssuers
    }
}
