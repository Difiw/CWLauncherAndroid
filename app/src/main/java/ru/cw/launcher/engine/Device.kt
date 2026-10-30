package ru.cw.launcher.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** Rough performance class of the phone, from several signals rather than a single number. */
object Device {
    enum class Tier { WEAK, MID, STRONG }

    class Profile(
        val tier: Tier,
        val totalMb: Int,
        val cores: Int,
        val maxMhz: Int,
        val score: Int,
        val summary: String
    )

    @Volatile private var cached: Profile? = null

    fun profile(context: Context): Profile {
        cached?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = (info.totalMem / (1024 * 1024)).toInt()
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMhz = maxCpuMhz()
        val gles = am.deviceConfigurationInfo.reqGlEsVersion
        val arm64 = Build.SUPPORTED_ABIS.contains("arm64-v8a")

        var score = 0
        score += when { totalMb >= 7000 -> 3; totalMb >= 5000 -> 2; totalMb >= 3500 -> 1; else -> 0 }
        score += when { cores >= 8 -> 2; cores >= 6 -> 1; else -> 0 }
        // Frequency is unreadable on some phones (0); then it neither helps nor hurts.
        score += when { maxMhz == 0 -> 1; maxMhz >= 2600 -> 2; maxMhz >= 2000 -> 1; else -> 0 }
        if (gles >= 0x30002) score += 1
        if (!arm64) score -= 2
        if (am.isLowRamDevice) score -= 2

        val tier = when {
            totalMb in 1..2999 -> Tier.WEAK
            score <= 2 -> Tier.WEAK
            score <= 5 -> Tier.MID
            else -> Tier.STRONG
        }
        val summary = "RAM ${totalMb} МБ, ядер $cores" + if (maxMhz > 0) ", до $maxMhz МГц" else ""
        return Profile(tier, totalMb, cores, maxMhz, score, summary).also { cached = it }
    }

    /** Optimization preset to recommend for a weak phone (1..6, see GameLaunch.applyOptions). */
    fun recommendedMode(profile: Profile): Int = if (profile.totalMb < 4000) 2 else 3

    private fun maxCpuMhz(): Int {
        var best = 0
        try {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }?.forEach { cpu ->
                val khz = File(cpu, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull() ?: 0
                if (khz / 1000 > best) best = khz / 1000
            }
        } catch (_: Exception) {
        }
        return best
    }
}
