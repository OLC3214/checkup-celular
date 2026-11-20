package br.com.checkupcelular

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale

data class AppRisk(
    val pkg: String,
    val label: String,
    val score: Int,
    val reasons: MutableList<String>,
    val isAdmin: Boolean,
    val outsideStore: Boolean,
    val apkPath: String?,
    /** Preenchido quando é ameaça CONFIRMADA por banco de dados (não só suspeita). */
    var confirmed: String? = null
)

data class DeviceCheck(
    val ok: Boolean,
    val title: String,
    val detail: String,
    val fix: Intent?
)

object Scanner {

    /** Lojas oficiais. App instalado por qualquer outra coisa (WhatsApp, navegador, gerenciador de arquivos) conta como "fora da loja". */
    val TRUSTED_STORES = setOf(
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

    /** Nomes que golpistas costumam imitar. Se um app com esse nome NÃO veio da loja, é muito suspeito. */
    val FAMOUS_NAMES = listOf(
        "whatsapp", "instagram", "facebook", "messenger", "telegram", "tiktok", "youtube", "netflix",
        "google", "chrome", "play store", "nubank", "itaú", "itau", "bradesco", "caixa", "banco do brasil",
        "santander", "mercado pago", "picpay", "pix", "gov.br", "serasa", "correios", "receita",
        "atualização", "atualizacao", "update", "sistema", "system", "segurança", "security", "antivírus", "antivirus"
    )

    private val SENSITIVE_PERMS = listOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_SMS,
        Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE
    )

    /**
     * Analisa os apps instalados pelo usuário.
     * deep = true (varredura completa) também confere assinatura, imitação de apps famosos,
     * data de instalação, versão alvo e quantidade de permissões sensíveis concedidas.
     */
    fun scanApps(ctx: Context, deep: Boolean, progress: (String) -> Unit): List<AppRisk> {
        val pm = ctx.packageManager
        val db = Threats.load(ctx, progress)
        val cr = ctx.contentResolver

        val accessibility = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val notifListeners = Settings.Secure.getString(cr, "enabled_notification_listeners") ?: ""
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()

        val userApps = installedPackages(pm).filter {
            val ai = it.applicationInfo
            ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName
        }

        val result = mutableListOf<AppRisk>()
        userApps.forEachIndexed { index, p ->
            val ai = p.applicationInfo ?: return@forEachIndexed
            val pkg = p.packageName
            val label = ai.loadLabel(pm).toString()
            progress("Analisando app ${index + 1} de ${userApps.size}: $label")

            val reasons = mutableListOf<String>()
            var score = 0
            val perms = p.requestedPermissions?.toSet() ?: emptySet()

            val outsideStore = (installerOf(pm, pkg) ?: "") !in TRUSTED_STORES
            if (outsideStore) { score += 2; reasons += "Instalado fora da loja oficial" }

            val isAdmin = pkg in admins
            if (isAdmin) { score += 3; reasons += "É administrador do aparelho (isso trava a desinstalação)" }

            val usesAccessibility = accessibility.contains("$pkg/")
            if (usesAccessibility) { score += 3; reasons += "Usa Acessibilidade: consegue ler e clicar na tela" }
            if (notifListeners.contains("$pkg/")) { score += 1; reasons += "Lê todas as suas notificações" }
            if (Manifest.permission.READ_SMS in perms || Manifest.permission.RECEIVE_SMS in perms) {
                score += 2; reasons += "Pode ler SMS (códigos de banco e de verificação)"
            }
            if (Manifest.permission.SYSTEM_ALERT_WINDOW in perms) { score += 1; reasons += "Pode aparecer por cima de outros apps" }
            if (Manifest.permission.REQUEST_INSTALL_PACKAGES in perms) { score += 1; reasons += "Pode instalar outros apps" }
            if (pm.getLaunchIntentForPackage(pkg) == null) { score += 2; reasons += "Não tem ícone na tela (app escondido)" }

            // Combinação típica de golpe bancário
            if (outsideStore && usesAccessibility) score += 3

            if (deep) {
                val lower = label.lowercase(Locale.ROOT)
                if (outsideStore && FAMOUS_NAMES.any { lower.contains(it) }) {
                    score += 4; reasons += "Usa nome de app conhecido (\"$label\") mas não veio da loja: possível app falso"
                }
                if (isDebugSigned(pm, pkg)) {
                    score += 2; reasons += "Assinado com certificado de teste (apps legítimos não usam)"
                }
                if (ai.targetSdkVersion in 1..22) {
                    score += 1; reasons += "Feito para Android muito antigo (pega permissões sem perguntar)"
                }
                val days = (System.currentTimeMillis() - p.firstInstallTime) / 86_400_000L
                if (outsideStore && days <= 7) {
                    score += 1; reasons += "Instalado há $days dia(s)"
                }
                val granted = grantedSensitive(p)
                if (granted >= 5) {
                    score += 1; reasons += "Tem $granted permissões sensíveis liberadas (câmera, microfone, contatos...)"
                }
                if (Manifest.permission.RECEIVE_BOOT_COMPLETED in perms && pm.getLaunchIntentForPackage(pkg) == null) {
                    score += 1; reasons += "Liga sozinho quando o celular inicia"
                }
            }

            val spy = Threats.matchStalkerware(ctx, db, pkg)
            if (spy != null) {
                score += 20; reasons.add(0, "Está no banco de apps espiões conhecidos ($spy)")
            }

            if (score >= 2) {
                result += AppRisk(pkg, label, score, reasons, isAdmin, outsideStore, ai.sourceDir,
                    spy?.let { "App espião conhecido: $it" })
            }
        }
        return result.sortedByDescending { it.score }
    }

    fun level(score: Int): String = when {
        score >= 6 -> "ALTO"
        score >= 4 -> "MÉDIO"
        else -> "BAIXO"
    }

    fun checkDevice(ctx: Context, deep: Boolean): List<DeviceCheck> {
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

        if (deep) {
            val rooted = isRooted(ctx)
            list += DeviceCheck(
                !rooted, "Root (acesso total ao sistema)",
                if (rooted) "Sinais de root encontrados. Qualquer app com root controla o celular inteiro." else "Nenhum sinal de root.",
                null
            )

            val userCerts = userCaCertificates()
            list += DeviceCheck(
                userCerts == 0, "Certificados instalados pelo usuário",
                if (userCerts == 0) "Nenhum." else "$userCerts certificado(s). Eles permitem espionar o que você acessa na internet. Remova se não sabe de onde vieram.",
                Intent("android.settings.SECURITY_SETTINGS")
            )

            val vpn = vpnActive(ctx)
            list += DeviceCheck(
                !vpn, "VPN ativa",
                if (vpn) "Tem uma VPN ligada. Todo o tráfego passa por ela. Confira se foi você que instalou." else "Nenhuma VPN ligada.",
                Intent("android.settings.VPN_SETTINGS")
            )
        }
        return list
    }

    // ---------- auxiliares ----------

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

    private fun grantedSensitive(p: PackageInfo): Int {
        val perms = p.requestedPermissions ?: return 0
        val flags = p.requestedPermissionsFlags ?: return 0
        var n = 0
        for (i in perms.indices) {
            if (i < flags.size && perms[i] in SENSITIVE_PERMS &&
                flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) n++
        }
        return n
    }

    @Suppress("DEPRECATION")
    private fun isDebugSigned(pm: PackageManager, pkg: String): Boolean = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val cf = CertificateFactory.getInstance("X.509")
        sigs?.any {
            val cert = cf.generateCertificate(ByteArrayInputStream(it.toByteArray())) as X509Certificate
            cert.subjectX500Principal.name.contains("CN=Android Debug")
        } ?: false
    } catch (e: Exception) { false }

    private fun isRooted(ctx: Context): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/local/xbin/su", "/data/local/bin/su"
        )
        if (paths.any { File(it).exists() }) return true
        val rootApps = listOf("com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser")
        return rootApps.any {
            try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
        }
    }

    private fun userCaCertificates(): Int = try {
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        ks.aliases().toList().count { it.startsWith("user:") }
    } catch (e: Exception) { 0 }

    private fun vpnActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
    } catch (e: Exception) { false }
}
