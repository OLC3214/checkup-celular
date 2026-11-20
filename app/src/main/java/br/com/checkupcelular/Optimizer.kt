package br.com.checkupcelular

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager

/**
 * Otimizações que o Android permite de verdade:
 * - fechar processos em segundo plano dos apps (libera memória RAM)
 * - mostrar espaço livre e lixo
 * - achar apps parados há mais de 30 dias (desinstalar libera espaço)
 * - achar os apps com mais cache (o usuário limpa em 1 toque)
 * - achar apps liberados da economia de bateria (gastam bateria em segundo plano)
 */
object Optimizer {

    data class AppInfo(val pkg: String, val label: String, val detail: String, val bytes: Long)

    fun ram(ctx: Context): Pair<Long, Long> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem to mi.totalMem
    }

    /** Fecha o que está rodando em segundo plano. Devolve quantos apps foram fechados. */
    fun closeBackground(ctx: Context, progress: (String) -> Unit): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val apps = userApps(ctx)
        apps.forEachIndexed { i, ai ->
            if (i % 10 == 0) progress("Fechando apps em segundo plano (${i + 1} de ${apps.size})...")
            try { am.killBackgroundProcesses(ai.packageName) } catch (_: Exception) { }
        }
        return apps.size
    }

    fun storage(): Pair<Long, Long> {
        val st = StatFs(Environment.getDataDirectory().path)
        return st.availableBytes to st.totalBytes
    }

    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    /** Apps que você não abre há mais de 30 dias (precisa do "Acesso ao uso"). */
    fun unusedApps(ctx: Context): List<AppInfo> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val stats = usm.queryAndAggregateUsageStats(now - 180 * day, now)
        val pm = ctx.packageManager
        val out = mutableListOf<AppInfo>()
        for (ai in userApps(ctx)) {
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
            val installed = try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (e: Exception) { now }
            if (now - installed < 30 * day) continue
            val last = stats[ai.packageName]?.lastTimeUsed ?: 0L
            if (last == 0L || now - last > 30 * day) {
                val txt = if (last == 0L) "Não foi aberto nos últimos 6 meses" else "Último uso há ${(now - last) / day} dias"
                out += AppInfo(ai.packageName, ai.loadLabel(pm).toString(), txt, sizeOf(ctx, ai).first)
            }
        }
        return out.sortedByDescending { it.bytes }
    }

    /** Apps com mais cache acumulado (precisa do "Acesso ao uso"). */
    fun biggestCaches(ctx: Context): List<AppInfo> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val pm = ctx.packageManager
        return userApps(ctx).mapNotNull { ai ->
            val cache = sizeOf(ctx, ai).second
            if (cache < 20L * 1024 * 1024) null
            else AppInfo(ai.packageName, ai.loadLabel(pm).toString(), "Cache acumulado", cache)
        }.sortedByDescending { it.bytes }.take(10)
    }

    /** Apps fora da economia de bateria (podem gastar bateria o tempo todo). */
    fun batteryExempt(ctx: Context): List<AppInfo> {
        val pwr = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val pm = ctx.packageManager
        return userApps(ctx).filter {
            try { pwr.isIgnoringBatteryOptimizations(it.packageName) } catch (e: Exception) { false }
        }.map { AppInfo(it.packageName, it.loadLabel(pm).toString(), "Sem economia de bateria", 0) }
    }

    /** (tamanho total, cache) em bytes. */
    private fun sizeOf(ctx: Context, ai: ApplicationInfo): Pair<Long, Long> {
        if (Build.VERSION.SDK_INT < 26) return 0L to 0L
        return try {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
            (s.appBytes + s.dataBytes) to s.cacheBytes
        } catch (e: Exception) { 0L to 0L }
    }

    private fun userApps(ctx: Context): List<ApplicationInfo> {
        val pm = ctx.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return list.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }
    }
}
