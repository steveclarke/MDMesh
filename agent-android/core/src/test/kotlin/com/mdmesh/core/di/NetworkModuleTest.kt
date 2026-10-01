package com.mdmesh.core.di

import android.content.Context
import android.content.SharedPreferences
import com.mdmesh.core.config.ServerConfigStore
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class NetworkModuleTest {

    @Test
    fun `APK client preserves external HTTPS origin path and query`() {
        MockWebServer().use { external ->
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            external.useHttps(serverTls.sslSocketFactory(), false)
            external.enqueue(MockResponse().setBody("apk bytes"))
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val client = NetworkModule.provideApkDownloadClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .build()
            val url = external.url("/releases/app.apk?version=2")

            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(url, response.request.url)
                assertEquals("apk bytes", response.body!!.string())
            }

            val received = external.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/releases/app.apk?version=2", received.path)
            assertEquals(url, received.requestUrl)
            assertTrue(received.handshake != null)
        }
    }

    @Test
    fun `APK client does not inherit MDM interceptors credentials cookies or authenticator`() {
        MockWebServer().use { mdm ->
            MockWebServer().use { external ->
                val context = mock(Context::class.java)
                val prefs = mock(SharedPreferences::class.java)
                `when`(context.getSharedPreferences("mdm_server", Context.MODE_PRIVATE)).thenReturn(prefs)
                `when`(prefs.getString("base_url", null)).thenReturn(mdm.url("/").toString())
                val mdmClient = NetworkModule.provideOkHttp(ServerConfigStore(context)).newBuilder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder().header("Authorization", "Bearer mdm-secret").build()
                        chain.proceed(request)
                    }
                    .addNetworkInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("X-MDM-Device-Secret", "device-secret").build()
                        chain.proceed(request)
                    }
                    .cookieJar(object : CookieJar {
                        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                        override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                            Cookie.Builder().name("mdm-session").value("session-secret").domain(url.host).build(),
                        )
                    })
                    .authenticator { _, response ->
                        response.request.newBuilder().header("Proxy-Authorization", "mdm-auth").build()
                    }
                    .build()
                val downloadClient = NetworkModule.provideApkDownloadClient()
                mdm.enqueue(MockResponse().setBody("management response"))
                external.enqueue(MockResponse().setBody("apk"))

                // This is the rewriting client that previously handled APK requests.
                mdmClient.newCall(Request.Builder().url(external.url("/app.apk")).build()).execute().close()
                val management = mdm.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("Bearer mdm-secret", management.getHeader("Authorization"))
                assertEquals("device-secret", management.getHeader("X-MDM-Device-Secret"))
                assertEquals("mdm-session=session-secret", management.getHeader("Cookie"))

                downloadClient.newCall(Request.Builder().url(external.url("/app.apk")).build()).execute().close()
                val download = external.takeRequest(1, TimeUnit.SECONDS)!!
                assertNull(download.getHeader("Authorization"))
                assertNull(download.getHeader("X-MDM-Device-Secret"))
                assertNull(download.getHeader("Cookie"))
                assertNull(download.getHeader("Proxy-Authorization"))
                assertTrue(downloadClient.interceptors.isEmpty())
                assertTrue(downloadClient.networkInterceptors.isEmpty())
                assertEquals(CookieJar.NO_COOKIES, downloadClient.cookieJar)
                assertEquals(Authenticator.NONE, downloadClient.authenticator)
                assertEquals(Authenticator.NONE, downloadClient.proxyAuthenticator)
                assertNotSame(mdmClient.connectionPool, downloadClient.connectionPool)
                assertFalse(mdmClient.interceptors.isEmpty())
                assertEquals(1, mdm.requestCount)
            }
        }
    }

    @Test
    fun `APK client rejects an untrusted HTTPS certificate`() {
        MockWebServer().use { external ->
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            external.useHttps(tls.sslSocketFactory(), false)
            external.enqueue(MockResponse().setBody("apk"))

            try {
                NetworkModule.provideApkDownloadClient()
                    .newCall(Request.Builder().url(external.url("/app.apk")).build()).execute().close()
                fail("untrusted TLS certificate must be refused")
            } catch (_: SSLHandshakeException) {
                assertEquals(0, external.requestCount)
            }
        }
    }
}
