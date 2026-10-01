package com.mdmesh.core.install

import android.content.Context
import android.content.pm.PackageManager
import com.mdmesh.core.di.NetworkModule
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class InstallManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `wrong downloaded SHA refuses installation and removes owned temporary APK`() = runTest {
        MockWebServer().use { external ->
            external.enqueue(MockResponse().setBody("not the requested APK"))
            val context = mock(Context::class.java)
            val packageManager = mock(PackageManager::class.java)
            `when`(context.packageManager).thenReturn(packageManager)
            `when`(context.cacheDir).thenReturn(temp.root)
            val installer = InstallManager(context, InstallResultBus(), NetworkModule.provideApkDownloadClient())

            val result = installer.install(
                InstallRequest(
                    url = external.url("/app.apk").toString(),
                    packageName = "com.example.app",
                    sha256 = "0".repeat(64),
                ),
            )

            assertTrue(result is InstallOutcome.Failure)
            assertTrue((result as InstallOutcome.Failure).reason.startsWith("checksum mismatch:"))
            verify(packageManager, never()).packageInstaller
            assertEquals(1, external.requestCount)
            assertTrue(temp.root.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `wrong local SHA refuses installation without deleting caller APK`() = runTest {
        val apk = temp.newFile("local.apk").apply { writeText("local bytes") }
        val context = mock(Context::class.java)
        val packageManager = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packageManager)
        val installer = InstallManager(context, InstallResultBus(), NetworkModule.provideApkDownloadClient())

        val result = installer.install(
            InstallRequest(localPath = apk.absolutePath, packageName = "com.example.app", sha256 = "0".repeat(64)),
        )

        assertTrue(result is InstallOutcome.Failure)
        assertTrue((result as InstallOutcome.Failure).reason.startsWith("checksum mismatch:"))
        verify(packageManager, never()).packageInstaller
        assertEquals("local bytes", apk.readText())
    }
}
