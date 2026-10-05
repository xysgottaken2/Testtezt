package com.wzm.launcher.wzm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.wzm.launcher.LauncherConfig

enum class WzmStatus {
    NAO_DETECTADO,
    INSTALADO,
    INICIANDO,
    EXECUTANDO,
    NAO_ESTA_EXECUTANDO
}

data class WzmLaunchResult(val success: Boolean, val message: String)

class WzmLauncher(private val context: Context) {

    fun isInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(LauncherConfig.WZM_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getInstalledVersion(): String? {
        return try {
            val info = context.packageManager.getPackageInfo(LauncherConfig.WZM_PACKAGE, 0)
            info.versionName
        } catch (_: Exception) { null }
    }

    fun getStatus(): WzmStatus {
        return if (isInstalled()) WzmStatus.INSTALADO else WzmStatus.NAO_DETECTADO
    }

    fun launch(): WzmLaunchResult {
        if (!isInstalled()) {
            return WzmLaunchResult(false, "Warzone Mobile não está instalado (${LauncherConfig.WZM_PACKAGE})")
        }
        return try {
            val pm = context.packageManager
            val intent: Intent? = pm.getLaunchIntentForPackage(LauncherConfig.WZM_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                WzmLaunchResult(true, "Warzone Mobile iniciado")
            } else {
                val fallback = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = LauncherConfig.WZM_PACKAGE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolve = pm.resolveActivity(fallback, 0)
                if (resolve != null) {
                    context.startActivity(fallback)
                    WzmLaunchResult(true, "Warzone Mobile iniciado (fallback)")
                } else {
                    WzmLaunchResult(false, "Nenhuma Activity de launcher encontrada para ${LauncherConfig.WZM_PACKAGE}")
                }
            }
        } catch (e: Exception) {
            WzmLaunchResult(false, "Erro ao iniciar WZM: ${e.message}")
        }
    }
}
