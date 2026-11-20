package br.com.checkupcelular

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Locale

data class AppRisk(
    val pkg: String,
    val label: String,
    val score: Int,
    val reasons: List<String>,
    val isAdmin: Boolean
)

data class DeviceCheck(
    val ok: Boolean,
    val title: String,
    val detail: String,
    val fix: Intent?
)

object Scanner {

    /** Lojas oficiais. App instalado por qualquer outra coisa (WhatsApp, navegador, gerenciador de arquivos) conta como "fora da loja". */
    private val TRUSTED_STORES = setOf(
        "com.android.vending",              // Google Play
        "com.sec.android.app.samsungapps",  // Galaxy Store
        "com.huawei.appmarket",             // AppGallery
        "com.xiaomi.market",                // Mi GetApps
        "com.xiaomi.mipicks",
        "com.heytap.market",                // Oppo/Realme
        "com.oppo.market",
        "com.bbk.appstore",                 // Vivo
        "com.motorola.ccc.ota",
        "com.amazon.venezia"                // Amazon
    )

    fun scanApps(ctx: Context): List<AppRisk> {
        val pm = ctx.packageManager
        val cr = ctx.contentResolver

        val accessibility = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val notifListeners = Settings.Secure.getString(cr, "enabled_notification_listeners") ?: ""
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()

        val result = mutableListOf<AppRisk>()
        for (p in installedPackages(pm)) {
            val ai = p.applicationInfo ?: continue
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            val pkg = p.packageName
            if (pkg == ctx.packageName) continue

            val reasons = mutableListOf<String>()
            var score = 0
            val perms = p.requestedPermissions?.toSet() ?: emptySet()

            val outsideStore = (installerOf(pm, pkg) ?: "") !in TRUSTED_STORES
            if (outsideStore) { score += 2; reasons += "Instalado fora da loja oficial" }

            val isAdmin = pkg in admins
            if (isAdmin) { score += 3; reasons += "É administrador do aparelho (isso trava a desinstalação)" }

            if (accessibility.contains("$pkg/")) {
                score += 3; reasons += "Usa Acessibilidade: consegue ler e clicar na tela"
            }
            if (notifListeners.contains("$pkg/")) {
                score += 1; reasons += "Lê todas as suas notificações"
            }
            if (Manifest.permission.READ_SMS in perms || Manifest.permission.RECEIVE_SMS in perms) {
                score += 2; reasons += "Pode ler SMS (códigos de banco e de verificação)"
            }
            if (Manifest.permission.SYSTEM_ALERT_WINDOW in perms) {
                score += 1; reasons += "Pode aparecer por cima de outros apps"
            }
            if (Manifest.permission.REQUEST_INSTALL_PACKAGES in perms) {
                score += 1; reasons += "Pode instalar outros apps"
            }
            if (pm.getLaunchIntentForPackage(pkg) == null) {
                score += 2; reasons += "Não tem ícone na tela (app escondido)"
            }

            // Combinações típicas de golpe bancário / app espião
            if (outsideStore && accessibility.contains("$pkg/")) score += 3

            if (score >= 2) {
                result += AppRisk(pkg, ai.loadLabel(pm).toString(), score, reasons, isAdmin)
            }
        }
        return result.sortedByDescending { it.score }
    }

    fun level(score: Int): String = when {
        score >= 6 -> "ALTO"
        score >= 4 -> "MÉDIO"
        else -> "BAIXO"
    }

    fun checkDevice(ctx: Context): List<DeviceCheck> {
        val cr = ctx.contentResolver
        val list = mutableListOf<DeviceCheck>()

        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        list += DeviceCheck(
            km.isDeviceSecure, "Bloqueio de tela",
            if (km.isDeviceSecure) "Tem senha, PIN ou digital." else "O celular está sem senha. Qualquer pessoa abre.",
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        )

        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        list += DeviceCheck(
            !adb, "Depuração USB",
            if (adb) "Ligada. Permite controlar o celular por cabo. Desligue se não usa." else "Desligada.",
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )

        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        list += DeviceCheck(
            !dev, "Opções do desenvolvedor",
            if (dev) "Ativadas. Normalmente não precisa." else "Desativadas.",
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val unknown = Settings.Secure.getInt(cr, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
            list += DeviceCheck(
                !unknown, "Fontes desconhecidas",
                if (unknown) "Permitido instalar apps de qualquer lugar." else "Bloqueado.",
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            )
        }

        val patchAgeDays = try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)
            if (d == null) -1L else (System.currentTimeMillis() - d.time) / 86_400_000L
        } catch (e: Exception) { -1L }
        val patchOk = patchAgeDays in 0..365
        list += DeviceCheck(
            patchOk, "Atualização de segurança",
            "Última: ${Build.VERSION.SECURITY_PATCH}" +
                if (patchOk) "" else ". Mais de 1 ano sem correções. Procure atualizar o sistema.",
            Intent("android.settings.SYSTEM_UPDATE_SETTINGS")
        )

        val oldAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        list += DeviceCheck(
            !oldAndroid, "Versão do Android",
            "Android ${Build.VERSION.RELEASE}" + if (oldAndroid) ". Versão antiga, mais vulnerável." else "",
            null
        )
        return list
    }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) { null }
}
