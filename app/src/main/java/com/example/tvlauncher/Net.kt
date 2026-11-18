package com.example.tvlauncher

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTP for wallpapers. Android 5 TVs miss newer root certificates, so we merge the system roots with a bundled set.
 * If a keyless request still fails with an SSL error it is retried once in relaxed mode (never for requests carrying API keys).
 */
object Net {
    private var strict: OkHttpClient? = null
    private var relaxed: OkHttpClient? = null
    private var bundled: X509TrustManager? = null

    @Synchronized
    private fun client(ctx: Context, relax: Boolean): OkHttpClient {
        val cached = if (relax) relaxed else strict
        if (cached != null) return cached
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        try {
            val tm: X509TrustManager = if (relax) trustAll() else mergedTrust(ctx.applicationContext)
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf(tm), SecureRandom())
            b.sslSocketFactory(sc.socketFactory, tm)
            if (relax) b.hostnameVerifier(HostnameVerifier { _, _ -> true })
        } catch (e: Exception) {
            // platform defaults
        }
        val c = b.build()
        if (relax) relaxed = c else strict = c
        return c
    }

    private fun trustAll() = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun platformTrust(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun bundledTrust(ctx: Context): X509TrustManager {
        bundled?.let { return it }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        val text = ctx.resources.openRawResource(R.raw.cacerts).bufferedReader().use { it.readText() }
        val cf = CertificateFactory.getInstance("X.509")
        var i = 0
        for (part in text.split("-----END CERTIFICATE-----")) {
            val idx = part.indexOf("-----BEGIN CERTIFICATE-----")
            if (idx < 0) continue
            val pem = part.substring(idx) + "-----END CERTIFICATE-----\n"
            try {
                ks.setCertificateEntry("bundled$i", cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())))
                i++
            } catch (e: Exception) {
            }
        }
        val tm = platformTrust(ks)
        bundled = tm
        return tm
    }

    private fun mergedTrust(ctx: Context): X509TrustManager {
        val system = platformTrust(null)
        val extra = bundledTrust(ctx)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                system.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
        }
    }

    private fun exec(ctx: Context, req: Request, relax: Boolean): okhttp3.Response = client(ctx, relax).newCall(req).execute()

    private fun run(ctx: Context, req: Request, secret: Boolean): okhttp3.Response {
        if (Store.relaxedSsl && !secret) return exec(ctx, req, true)
        return try {
            exec(ctx, req, false)
        } catch (e: SSLException) {
            if (secret) throw e
            exec(ctx, req, true)
        }
    }

    /** Blocking. Call from a background thread only. */
    fun getString(ctx: Context, url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", "Lumen/1.0 (Android TV)")
        for ((k, v) in headers) b.header(k, v)
        val secret = headers.containsKey("Authorization")
        return run(ctx, b.build(), secret).use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
            r.body?.string() ?: ""
        }
    }

    /** Blocking download to [out] (written to a temp file first). */
    fun download(ctx: Context, url: String, out: File, headers: Map<String, String> = emptyMap(), onProgress: ((Long, Long) -> Unit)? = null) {
        val b = Request.Builder().url(url).header("User-Agent", "Lumen/1.0 (Android TV)")
        for ((k, v) in headers) b.header(k, v)
        val tmp = File(out.parentFile, out.name + ".part")
        run(ctx, b.build(), headers.containsKey("Authorization")).use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
            val body = r.body ?: throw java.io.IOException("Empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (onProgress != null && now - lastReport > 250) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw java.io.IOException("Could not save file")
    }
}
