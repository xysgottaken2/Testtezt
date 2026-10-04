package com.wzm.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.wzm.launcher.wzm.WzmLauncher
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

// Simple unit test for package name constant and logic without Android framework
class WzmLauncherConfigTest {
    @Test
    fun packageNameIsCorrect() {
        assertEquals("com.activision.callofduty.warzone", LauncherConfig.WZM_PACKAGE)
        assertEquals("127.0.0.1", LauncherConfig.HOST)
        assertEquals(18081, LauncherConfig.PORT)
    }

    @Test
    fun wzmLauncherDetectsNotInstalled() {
        val mockContext = mock(Context::class.java)
        val mockPm = mock(PackageManager::class.java)
        `when`(mockContext.packageManager).thenReturn(mockPm)
        `when`(mockPm.getPackageInfo(LauncherConfig.WZM_PACKAGE, 0))
            .thenThrow(PackageManager.NameNotFoundException())

        val launcher = WzmLauncher(mockContext)
        assertFalse(launcher.isInstalled())
        assertEquals(null, launcher.getInstalledVersion())
        val result = launcher.launch()
        assertFalse(result.success)
        assertTrue(result.message.contains("não está instalado"))
    }

    @Test
    fun wzmLauncherDetectsInstalled() {
        val mockContext = mock(Context::class.java)
        val mockPm = mock(PackageManager::class.java)
        val pi = PackageInfo().apply {
            packageName = LauncherConfig.WZM_PACKAGE
            versionName = "3.10.0"
        }
        `when`(mockContext.packageManager).thenReturn(mockPm)
        `when`(mockPm.getPackageInfo(LauncherConfig.WZM_PACKAGE, 0)).thenReturn(pi)
        // mock launch intent
        val mockIntent = mock(android.content.Intent::class.java)
        `when`(mockPm.getLaunchIntentForPackage(LauncherConfig.WZM_PACKAGE)).thenReturn(mockIntent)

        val launcher = WzmLauncher(mockContext)
        assertTrue(launcher.isInstalled())
        assertEquals("3.10.0", launcher.getInstalledVersion())
    }
}
