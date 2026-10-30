package ru.cw.launcher.engine

import android.content.Context
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

object AppPaths {
    lateinit var root: File
        private set

    fun init(context: Context) {
        root = File(context.filesDir, "CWLauncher").apply { mkdirs() }
        listOf(logs, cache, tmp, updates, javaDir, injector, skins, gamesRoot).forEach { it.mkdirs() }
    }

    val config: File get() = File(root, "config.json")
    val accounts: File get() = File(root, "accounts.json")
    val profiles: File get() = File(root, "profiles.json")
    val secrets: File get() = File(root, "secrets.json")
    val logs: File get() = File(root, "logs")
    val mainLog: File get() = File(logs, "launcher.log")
    val gameLog: File get() = File(logs, "latest.log")
    val cache: File get() = File(root, "cache")
    val tmp: File get() = File(root, "tmp")
    val updates: File get() = File(root, "updates")
    val javaDir: File get() = File(root, "java")
    val injector: File get() = File(root, "injector")
    val skins: File get() = File(root, "skins")
    val gamesRoot: File get() = File(root, "games")

    fun game(version: String): File = File(gamesRoot, sanitize(version)).apply { mkdirs() }
    fun versions(version: String): File = File(game(version), "versions")
    fun versionDir(version: String, id: String): File = File(versions(version), sanitize(id))
    fun versionJson(version: String, id: String): File = File(versionDir(version, id), "${sanitize(id)}.json")
    fun versionJar(version: String, id: String): File = File(versionDir(version, id), "${sanitize(id)}.jar")
    fun libs(version: String): File = File(game(version), "libraries")
    fun assets(version: String): File = File(game(version), "assets")
    fun mods(version: String): File = File(game(version), "mods")
    fun buildState(version: String): File = File(game(version), "build-state.json")
    fun buildManifest(version: String): File = File(game(version), "build-manifest.json")

    fun sanitize(id: String?): String {
        val raw = id?.trim().orEmpty().ifBlank { "1.20.1" }
        return raw.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
    }

    fun freeMb(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes / (1024 * 1024)
        } catch (_: Exception) {
            -1
        }
    }
}

object CwLog {
    /** Always the installed app version, so the screen can never disagree with the package. */
    val VERSION: String = ru.cw.launcher.BuildConfig.VERSION_NAME
    @Volatile private var verbose = false
    @Volatile private var developer = false

    fun configure(verboseLogging: Boolean, developerMode: Boolean) {
        verbose = verboseLogging
        developer = developerMode
        info("Диагностика: verbose=$verboseLogging, developer=$developerMode")
    }

    fun isVerbose(): Boolean = verbose
    fun isDeveloper(): Boolean = developer

    @Synchronized
    fun debug(message: String) {
        if (verbose || developer) write("DEBUG", message)
    }

    @Synchronized
    fun info(message: String) = write("INFO", message)

    @Synchronized
    fun warn(message: String) = write("WARN", message)

    @Synchronized
    fun error(message: String) = write("ERROR", message)

    private fun write(level: String, message: String) {
        val line = "${java.time.LocalDateTime.now()} $level $message"
        try {
            AppPaths.logs.mkdirs()
            rotate(AppPaths.mainLog, 2_000_000, 3)
            AppPaths.mainLog.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    fun rotate(file: File, maxBytes: Long, generations: Int = 2) {
        if (!file.isFile || file.length() < maxBytes) return
        for (index in generations downTo 1) {
            val source = if (index == 1) file else File(file.parentFile, "${file.name}.${index - 1}")
            val target = File(file.parentFile, "${file.name}.$index")
            if (!source.isFile) continue
            if (target.exists()) target.delete()
            source.renameTo(target)
        }
    }

    fun reason(t: Throwable): String {
        val m = t.message
        return if (m.isNullOrBlank()) t.javaClass.simpleName else m
    }
}

fun compareVersions(a: String?, b: String?): Int {
    val pa = normalizeVersion(a).split(".")
    val pb = normalizeVersion(b).split(".")
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa.getOrNull(i)?.toLongOrNull() ?: 0L
        val y = pb.getOrNull(i)?.toLongOrNull() ?: 0L
        if (x != y) return if (x < y) -1 else 1
    }
    return 0
}

fun normalizeVersion(v: String?): String {
    if (v == null) return "0"
    var s = v.trim().removePrefix("v").replace(Regex("[^0-9.]"), "")
    while (s.endsWith(".")) s = s.dropLast(1)
    return s.ifBlank { "0" }
}

fun sha1(file: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun looksLikeZip(file: File): Boolean {
    if (!file.isFile || file.length() < 4) return false
    return file.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }
}

fun sameFile(file: File, size: Long, hash: String?): Boolean {
    if (!file.isFile || file.length() <= 0) return false
    if (size > 0 && file.length() != size) return false
    if (!hash.isNullOrBlank() && !sha1(file).equals(hash, ignoreCase = true)) return false
    return true
}

const val ASM_CLASS = "org/objectweb/asm/ClassReader.class"

fun isAsmCore(name: String): Boolean {
    val parts = name.split(":")
    return parts.size >= 2 && parts[0] == "org.ow2.asm" && parts[1] == "asm"
}

fun jarHasEntry(file: File, entry: String): Boolean {
    if (!looksLikeZip(file)) return false
    return try {
        java.util.zip.ZipFile(file).use { it.getEntry(entry) != null }
    } catch (_: Exception) {
        false
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, if (mb >= 100) "%.0f МБ" else "%.1f МБ", mb)
    return String.format(Locale.US, if (mb >= 10 * 1024) "%.1f ГБ" else "%.2f ГБ", mb / 1024.0)
}

fun formatSpeed(bps: Long): String {
    val mb = bps / (1024.0 * 1024.0)
    return if (mb >= 0.1) String.format(Locale.US, "%.1f МБ/с", mb)
    else String.format(Locale.US, "%.0f КБ/с", bps / 1024.0)
}

fun formatEta(seconds: Long): String {
    val sec = seconds.coerceIn(0, 99 * 3600)
    if (sec < 60) return "$sec сек."
    val min = sec / 60
    val rem = sec % 60
    if (min < 60) return if (rem == 0L) "$min мин." else "$min мин. $rem сек."
    return "${min / 60} ч. ${min % 60} мин."
}

fun downloadError(reason: String, file: String): String =
    "Не удалось загрузить необходимые файлы.\n\nПричина:\n$reason\n\nФайл:\n$file"

class SpeedMeter {
    private val samples = ArrayDeque<LongArray>()

    @Synchronized fun reset() {
        samples.clear()
    }

    @Synchronized fun note(done: Long): Long {
        val now = System.currentTimeMillis()
        samples.addLast(longArrayOf(now, done))
        while (samples.size > 2 && now - samples.first()[0] > 5000) samples.removeFirst()
        if (samples.size < 2) return 0
        val first = samples.first()
        val dt = now - first[0]
        if (dt < 700) return 0
        val delta = done - first[1]
        if (delta <= 0) return 0
        return delta * 1000 / dt
    }
}

fun deleteTree(file: File) {
    if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
    file.delete()
}

fun clearDir(dir: File) {
    dir.mkdirs()
    dir.listFiles()?.forEach { deleteTree(it) }
}

fun extractZip(zip: File, dest: File): Int {
    dest.mkdirs()
    var count = 0
    ZipInputStream(zip.inputStream().buffered()).use { zin ->
        while (true) {
            val entry = zin.nextEntry ?: break
            val out = File(dest, entry.name).canonicalFile
            if (!out.path.startsWith(dest.canonicalPath + File.separator) && out.path != dest.canonicalPath) {
                throw java.io.IOException("ZIP Slip: ${entry.name}")
            }
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                out.outputStream().use { zin.copyTo(it) }
                count++
            }
            zin.closeEntry()
        }
    }
    return count
}

fun readVersionToken(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val line = raw.replace("\uFEFF", "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return null
    val token = line.substringBefore("|").substringBefore(" ").trim()
    return token.ifBlank { null }
}

fun currentOs(): String {
    val os = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT)
    return when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "osx"
        else -> "linux"
    }
}

fun is64(): Boolean {
    val model = System.getProperty("sun.arch.data.model") ?: "64"
    val arch = (System.getProperty("os.arch") ?: "").lowercase(Locale.ROOT)
    return model.contains("64") || arch.contains("64") || deviceArch() == "arm64" || deviceArch() == "x64"
}

/** Архитектура телефона в обозначениях библиотек Minecraft: arm64, arm32, x64, x86. */
fun deviceArch(): String {
    val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase(Locale.ROOT)
    val arch = (System.getProperty("os.arch") ?: "").lowercase(Locale.ROOT)
    val s = "$abi $arch"
    return when {
        s.contains("arm64") || s.contains("aarch64") -> "arm64"
        s.contains("armeabi") || s.contains("armv7") -> "arm32"
        s.contains("x86_64") || s.contains("amd64") -> "x64"
        s.contains("x86") || s.contains("i386") -> "x86"
        else -> "arm64"
    }
}

class CwSettings {
    var minecraftVersion: String = "1.20.1"
    var commonWorld: Boolean = true
    var loader: String = "fabric"
    var fabricLoaderVersion: String = ""
    val fabricVersions: MutableMap<String, Boolean> = linkedMapOf()
    var ramMb: Int = 0
    var autoUpdateBuild: Boolean = true
    var autoUpdateLauncher: Boolean = true
    var internetLeader: Boolean = false
    var theme: String = "dark"
    var telegramUrl: String = "https://t.me/CommonWorldNews"
    var discordUrl: String = "https://discord.gg/V6HnUWWj3J"
    var supportUrl: String = "https://discord.gg/vEkuksnUQ4"
    var screenWidth: Int = 1280
    var screenHeight: Int = 720
    var screenFullscreen: Boolean = false
    var framerateLimit: Int = 240
    var vsync: Boolean = true
    var extraJvmArgs: String = ""
    var serverAddress: String = ""
    /** Join the configured server right after the game client has loaded. */
    var autoConnect: Boolean = true

    /** Screen switched off: 0 quit the game at once, 1 quit after a timer, 2 keep playing (default). */
    var screenOffMode: Int = 2
    var screenOffMinutes: Int = 5

    /** Interface palette: blue (default), emerald, ruby. Only colours change, not the background picture. */
    var uiPalette: String = "blue"

    /** Opacity of panels and cards, percent of the default. */
    var uiSurface: Int = 100
    /** Hue (0-359) of the player-made colour style, theme id "custom". */
    var uiCustomHue: Int = 280
    /** Roundness of cards in dp and the width of their outline in dp. */
    var uiCorner: Int = 18
    var uiBorder: Int = 1
    /** Dynamic style: three hues "left,middle,right"; empty for a solid style. */
    var uiDynHues: String = ""
    /** "Pick background": the launcher chooses the main background of the current theme. */
    var bgAuto: Boolean = true

    /** Renderer: 0 automatic, 1 GL4ES, 2 OpenLTW. */
    var rendererMode: Int = 0

    /** Garbage collector: 0 Serial (default), 1 Parallel. */
    var gcMode: Int = 0

    /** JIT: 0 normal tiered compilation, 1 C1 only (less memory and heat, slower peak speed). */
    var jitMode: Int = 0
    var directMemoryMb: Int = 256

    /** Game render resolution, percent of the screen (the picture is scaled up by the GPU). */
    var renderScale: Int = 100

    /** Lower the render resolution automatically when a shader pack is switched on. */
    var shaderAutoScale: Boolean = true
    var shaderScale: Int = 60

    /** Game options preset: 0 disabled (default), 1..6 specialised performance profiles. */
    var optimizationMode: Int = 0

    /** The one-time "enable optimization?" offer for weak phones has already been shown. */
    var optimizationOffered: Boolean = false
    var cwVersionUrl: String = CW_VERSION_URL
    var lVersionUrl: String = L_VERSION_URL
    var launcherDownloadUrl: String = LAUNCHER_URL
    var modpackUrl: String = MODPACK_URL
    var newsUrl: String = NEWS_URL
    var updateInfoUrl: String = UPDATE_INFO_URL
    var serverIp: String = SERVER
    var elyClientId: String = ""
    var elyRedirectUri: String = ""
    var elyBackendUrl: String = ""
    var checkIntervalSec: Int = 120
    var javaPath: String = ""
    var gameLanguage: String = ""
    var offlineAllowed: Boolean = true
    var developerMode: Boolean = false
    /** Developer mode: hide the live game log on the start-up screen. */
    var hideBootLogs: Boolean = false
    /** Start-up screen design: card, minimal, cinema, pixels or ring. */
    var bootStyle: String = "card"
    /** Developer mode: name of the own build folder to start (empty = the main one). */
    var devInstance: String = ""
    /** Developer mode: temporary nick for the next launches (empty = the current account). */
    var devNick: String = ""
    var verboseLogging: Boolean = false
    var notificationsEnabled: Boolean = true
    var verifyBuildOnLaunch: Boolean = true
    var modBackups: Boolean = true
    var modBackupCount: Int = 5
    var checkDiskSpace: Boolean = true
    var autoStart: Boolean = true
    var launcherUpdateEvery: String = "day"
    var playTimeUnit: String = "sec"
    var limitBandwidth: Boolean = false
    var bandwidthMbit: Int = 0
    var disableFullscreenOpt: Boolean = false
    var highPriority: Boolean = false
    var disableRealtimeOpt: Boolean = false
    var modsUpdateEvery: String = "day"
    var backgroundDim: Int = 48
    var backgroundPath: String = ""
    var uiEffects: Boolean = true
    var uiBlur: Boolean = true
    var uiAnim: Boolean = true
    var uiTransitions: Boolean = false
    var uiStyle: String = "standard"
    var uiFontScale: Int = 100
    var launcherImmersive: Boolean = true
    var keepScreenAwake: Boolean = true
    var showFps: Boolean = false
    var useSystemProxy: Boolean = false
    var maxAttempts: Int = 3
    var activeAccountId: String = ""

    @Volatile var remoteBuildVersion: String? = null
    @Volatile var remoteLauncherVersion: String? = null

    fun commonWorldMods(): Boolean = commonWorld

    fun fabricFor(version: String): Boolean {
        if (fabricVersions.containsKey(version)) return fabricVersions[version] == true
        return false
    }

    fun resolvedLanguage(): String {
        if (gameLanguage.matches(Regex("[a-z]{2}_[a-z]{2}"))) return gameLanguage
        val locale = Locale.getDefault()
        val language = locale.language.lowercase(Locale.ROOT)
        var country = locale.country.lowercase(Locale.ROOT)
        if (country.isBlank()) {
            country = when (language) {
                "en" -> "us"
                "uk" -> "ua"
                "be" -> "by"
                else -> language
            }
        }
        if (language.isBlank()) return "ru_ru"
        return language + "_" + country
    }

    fun uiCode(): String {
        val code = resolvedLanguage()
        return if (code.startsWith("ru")) "ru" else "en"
    }

    fun gameRamMb(totalMb: Int): Int {
        val chosen = if (ramMb > 0) ramMb else (totalMb / 2)
        return chosen.coerceIn(512, totalMb.coerceAtLeast(512))
    }

    fun takeFrom(other: CwSettings) {
        minecraftVersion = other.minecraftVersion
        commonWorld = other.commonWorld
        loader = other.loader
        fabricLoaderVersion = other.fabricLoaderVersion
        fabricVersions.clear()
        fabricVersions.putAll(other.fabricVersions)
        ramMb = other.ramMb
        autoUpdateBuild = other.autoUpdateBuild
        autoUpdateLauncher = other.autoUpdateLauncher
        internetLeader = other.internetLeader
        theme = other.theme
        telegramUrl = other.telegramUrl
        discordUrl = other.discordUrl
        supportUrl = other.supportUrl
        screenWidth = other.screenWidth
        screenHeight = other.screenHeight
        screenFullscreen = other.screenFullscreen
        framerateLimit = other.framerateLimit
        vsync = other.vsync
        extraJvmArgs = other.extraJvmArgs
        serverAddress = other.serverAddress
        autoConnect = other.autoConnect
        screenOffMode = other.screenOffMode
        screenOffMinutes = other.screenOffMinutes
        uiPalette = other.uiPalette
        uiSurface = other.uiSurface
        uiCustomHue = other.uiCustomHue
        uiCorner = other.uiCorner
        uiBorder = other.uiBorder
        uiDynHues = other.uiDynHues
        bgAuto = other.bgAuto
        rendererMode = other.rendererMode
        gcMode = other.gcMode
        jitMode = other.jitMode
        directMemoryMb = other.directMemoryMb
        renderScale = other.renderScale
        shaderAutoScale = other.shaderAutoScale
        shaderScale = other.shaderScale
        optimizationMode = other.optimizationMode
        optimizationOffered = other.optimizationOffered
        cwVersionUrl = other.cwVersionUrl
        lVersionUrl = other.lVersionUrl
        launcherDownloadUrl = other.launcherDownloadUrl
        modpackUrl = other.modpackUrl
        newsUrl = other.newsUrl
        updateInfoUrl = other.updateInfoUrl
        serverIp = other.serverIp
        elyClientId = other.elyClientId
        elyRedirectUri = other.elyRedirectUri
        elyBackendUrl = other.elyBackendUrl
        checkIntervalSec = other.checkIntervalSec
        javaPath = other.javaPath
        gameLanguage = other.gameLanguage
        offlineAllowed = other.offlineAllowed
        developerMode = other.developerMode
        hideBootLogs = other.hideBootLogs
        bootStyle = other.bootStyle
        devInstance = other.devInstance
        devNick = other.devNick
        verboseLogging = other.verboseLogging
        notificationsEnabled = other.notificationsEnabled
        verifyBuildOnLaunch = other.verifyBuildOnLaunch
        modBackups = other.modBackups
        modBackupCount = other.modBackupCount
        checkDiskSpace = other.checkDiskSpace
        autoStart = other.autoStart
        launcherUpdateEvery = other.launcherUpdateEvery
        playTimeUnit = other.playTimeUnit
        limitBandwidth = other.limitBandwidth
        bandwidthMbit = other.bandwidthMbit
        disableFullscreenOpt = other.disableFullscreenOpt
        highPriority = other.highPriority
        disableRealtimeOpt = other.disableRealtimeOpt
        modsUpdateEvery = other.modsUpdateEvery
        backgroundDim = other.backgroundDim
        backgroundPath = other.backgroundPath
        uiEffects = other.uiEffects
        uiBlur = other.uiBlur
        uiAnim = other.uiAnim
        uiTransitions = other.uiTransitions
        uiStyle = other.uiStyle
        uiFontScale = other.uiFontScale
        launcherImmersive = other.launcherImmersive
        keepScreenAwake = other.keepScreenAwake
        showFps = other.showFps
        useSystemProxy = other.useSystemProxy
        maxAttempts = other.maxAttempts
        activeAccountId = other.activeAccountId
        remoteBuildVersion = null
        remoteLauncherVersion = null
    }

    fun save() {
        val o = JSONObject()
        o.put("minecraftVersion", minecraftVersion)
        o.put("commonWorld", commonWorld)
        o.put("loader", loader)
        o.put("fabricLoaderVersion", fabricLoaderVersion)
        val fabric = org.json.JSONObject()
        fabricVersions.forEach { (key, value) -> fabric.put(key, value) }
        o.put("fabricVersions", fabric)
        o.put("ramMb", ramMb)
        o.put("autoUpdateBuild", autoUpdateBuild)
        o.put("autoUpdateLauncher", autoUpdateLauncher)
        o.put("internetLeader", internetLeader)
        o.put("theme", theme)
        o.put("telegramUrl", telegramUrl)
        o.put("discordUrl", discordUrl)
        o.put("supportUrl", supportUrl)
        o.put("screenWidth", screenWidth)
        o.put("screenHeight", screenHeight)
        o.put("screenFullscreen", screenFullscreen)
        o.put("framerateLimit", framerateLimit)
        o.put("vsync", vsync)
        o.put("extraJvmArgs", extraJvmArgs)
        o.put("serverAddress", serverAddress)
        o.put("autoConnect", autoConnect)
        o.put("screenOffMode", screenOffMode)
        o.put("screenOffMinutes", screenOffMinutes)
        o.put("uiPalette", uiPalette)
        o.put("uiSurface", uiSurface)
        o.put("uiCustomHue", uiCustomHue)
        o.put("uiCorner", uiCorner)
        o.put("uiBorder", uiBorder)
        o.put("uiDynHues", uiDynHues)
        o.put("bgAuto", bgAuto)
        o.put("rendererMode", rendererMode)
        o.put("gcMode", gcMode)
        o.put("jitMode", jitMode)
        o.put("directMemoryMb", directMemoryMb)
        o.put("renderScale", renderScale)
        o.put("shaderAutoScale", shaderAutoScale)
        o.put("shaderScale", shaderScale)
        o.put("optimizationMode", optimizationMode)
        o.put("optimizationOffered", optimizationOffered)
        o.put("cwVersionUrl", cwVersionUrl)
        o.put("lVersionUrl", lVersionUrl)
        o.put("launcherDownloadUrl", launcherDownloadUrl)
        o.put("modpackUrl", modpackUrl)
        o.put("newsUrl", newsUrl)
        o.put("updateInfoUrl", updateInfoUrl)
        o.put("serverIp", serverIp)
        o.put("elyClientId", elyClientId)
        o.put("elyRedirectUri", elyRedirectUri)
        o.put("elyBackendUrl", elyBackendUrl)
        o.put("checkIntervalSec", checkIntervalSec)
        o.put("javaPath", javaPath)
        o.put("gameLanguage", gameLanguage)
        o.put("offlineAllowed", offlineAllowed)
        o.put("developerMode", developerMode)
        o.put("hideBootLogs", hideBootLogs)
        o.put("bootStyle", bootStyle)
        o.put("devInstance", devInstance)
        o.put("devNick", devNick)
        o.put("verboseLogging", verboseLogging)
        o.put("notificationsEnabled", notificationsEnabled)
        o.put("verifyBuildOnLaunch", verifyBuildOnLaunch)
        o.put("modBackups", modBackups)
        o.put("modBackupCount", modBackupCount)
        o.put("checkDiskSpace", checkDiskSpace)
        o.put("autoStart", autoStart)
        o.put("launcherUpdateEvery", launcherUpdateEvery)
        o.put("playTimeUnit", playTimeUnit)
        o.put("limitBandwidth", limitBandwidth)
        o.put("bandwidthMbit", bandwidthMbit)
        o.put("disableFullscreenOpt", disableFullscreenOpt)
        o.put("highPriority", highPriority)
        o.put("disableRealtimeOpt", disableRealtimeOpt)
        o.put("modsUpdateEvery", modsUpdateEvery)
        o.put("backgroundDim", backgroundDim)
        o.put("backgroundPath", backgroundPath)
        o.put("uiEffects", uiEffects)
        o.put("uiBlur", uiBlur)
        o.put("uiAnim", uiAnim)
        o.put("uiTransitions", uiTransitions)
        o.put("uiStyle", uiStyle)
        o.put("uiFontScale", uiFontScale)
        o.put("launcherImmersive", launcherImmersive)
        o.put("keepScreenAwake", keepScreenAwake)
        o.put("showFps", showFps)
        o.put("useSystemProxy", useSystemProxy)
        o.put("maxAttempts", maxAttempts)
        o.put("activeAccountId", activeAccountId)
        AppPaths.config.writeText(o.toString(2))
    }

    companion object {
        const val CW_VERSION_URL =
            "https://drive.google.com/file/d/1BM_LzV7hhjvwEMsozMjZVPFUEg1nQP93/view?usp=drivesdk"
        const val L_VERSION_URL =
            "https://drive.google.com/file/d/1Wvi1Thz8ty0jCESSfdrfCTxABZafjdkh/view?usp=sharing"
        const val NEWS_URL =
            "https://drive.google.com/file/d/1NdVMevnHKWKcg07_zGrtaeF0G_otdXBz/view?usp=drivesdk"
        const val LAUNCHER_URL =
            "https://drive.google.com/file/d/1fnfLPRYOZE8ZiDArl6Bjunu3hLUnEEra/view?usp=drivesdk"
        const val MODPACK_URL =
            "https://drive.google.com/file/d/1yPV7USzhlWKNdBdqypyyB2huFLlBxmAF/view?usp=drive_link"
        const val UPDATE_INFO_URL =
            "https://drive.google.com/file/d/1VNUrgWIhZZG84th81syjGBMbNnJk2JSR/view?usp=drive_link"
        const val PATCH_URL =
            "https://drive.google.com/file/d/191WVmeWzJfScBzpG-XAIQPU7GFaMamlB/view?usp=drivesdk"
        const val SERVER = "commonworld.pterohost.ru:25965"

        fun load(): CwSettings {
            val s = CwSettings()
            if (!AppPaths.config.isFile) {
                s.save()
                return s
            }
            return try {
                val o = JSONObject(AppPaths.config.readText())
                s.minecraftVersion = o.optString("minecraftVersion", s.minecraftVersion).ifBlank { "1.20.1" }
                s.commonWorld = o.optBoolean("commonWorld", true)
                s.loader = o.optString("loader", "fabric")
                s.fabricLoaderVersion = o.optString("fabricLoaderVersion", "")
                o.optJSONObject("fabricVersions")?.let { map ->
                    map.keys().forEach { key -> s.fabricVersions[key] = map.optBoolean(key) }
                }
                s.ramMb = o.optInt("ramMb", 0)
                s.autoUpdateBuild = o.optBoolean("autoUpdateBuild", true)
                s.autoUpdateLauncher = o.optBoolean("autoUpdateLauncher", true)
                s.internetLeader = o.optBoolean("internetLeader", false)
                s.theme = o.optString("theme", "dark")
                s.telegramUrl = o.optString("telegramUrl", s.telegramUrl)
                s.discordUrl = o.optString("discordUrl", s.discordUrl)
                s.supportUrl = o.optString("supportUrl", s.supportUrl)
                s.screenWidth = o.optInt("screenWidth", 1280)
                s.screenHeight = o.optInt("screenHeight", 720)
                s.screenFullscreen = o.optBoolean("screenFullscreen", false)
                s.framerateLimit = o.optInt("framerateLimit", 240)
                s.vsync = o.optBoolean("vsync", true)
                s.extraJvmArgs = o.optString("extraJvmArgs", "")
                s.serverAddress = o.optString("serverAddress", "")
                s.autoConnect = o.optBoolean("autoConnect", true)
                s.screenOffMode = o.optInt("screenOffMode", 2).coerceIn(0, 2)
                s.screenOffMinutes = o.optInt("screenOffMinutes", 5).coerceIn(1, 120)
                s.uiPalette = o.optString("uiPalette", "blue")
                s.uiSurface = o.optInt("uiSurface", 100).coerceIn(50, 140)
                s.uiCustomHue = o.optInt("uiCustomHue", 280).coerceIn(0, 359)
                s.uiCorner = o.optInt("uiCorner", 18).coerceIn(4, 32)
                s.uiBorder = o.optInt("uiBorder", 1).coerceIn(0, 4)
                s.uiDynHues = o.optString("uiDynHues", "")
                s.bgAuto = o.optBoolean("bgAuto", true)
                s.rendererMode = o.optInt("rendererMode", 0).coerceIn(0, 2)
                s.gcMode = o.optInt("gcMode", 0).coerceIn(0, 1)
                s.jitMode = o.optInt("jitMode", 0).coerceIn(0, 1)
                s.directMemoryMb = o.optInt("directMemoryMb", 256).coerceIn(128, 2048)
                s.renderScale = o.optInt("renderScale", 100).coerceIn(33, 100)
                s.shaderAutoScale = o.optBoolean("shaderAutoScale", true)
                s.shaderScale = o.optInt("shaderScale", 60).coerceIn(33, 100)
                s.optimizationMode = o.optInt("optimizationMode", 0).coerceIn(0, 6)
                // Settings saved before this field existed belong to an existing install: do not offer again.
                s.optimizationOffered = o.optBoolean("optimizationOffered", o.length() > 0)
                s.cwVersionUrl = o.optString("cwVersionUrl", s.cwVersionUrl)
                s.lVersionUrl = o.optString("lVersionUrl", s.lVersionUrl)
                if (s.lVersionUrl.isBlank() || s.lVersionUrl.contains("1mxxkPgndfSy0HiJ31sy2q1SYauZx8L83")) {
                    s.lVersionUrl = L_VERSION_URL
                }
                s.launcherDownloadUrl = o.optString("launcherDownloadUrl", s.launcherDownloadUrl)
                s.modpackUrl = o.optString("modpackUrl", s.modpackUrl)
                s.newsUrl = o.optString("newsUrl", s.newsUrl)
                s.updateInfoUrl = o.optString("updateInfoUrl", s.updateInfoUrl)
                s.serverIp = o.optString("serverIp", s.serverIp)
                s.elyClientId = o.optString("elyClientId", "")
                s.elyRedirectUri = o.optString("elyRedirectUri", "")
                s.elyBackendUrl = o.optString("elyBackendUrl", "")
                s.checkIntervalSec = o.optInt("checkIntervalSec", 120)
                s.javaPath = o.optString("javaPath", "")
                s.gameLanguage = o.optString("gameLanguage", "")
                s.offlineAllowed = o.optBoolean("offlineAllowed", true)
                s.developerMode = o.optBoolean("developerMode", false)
                s.hideBootLogs = o.optBoolean("hideBootLogs", false)
                s.bootStyle = o.optString("bootStyle", "card")
                s.devInstance = o.optString("devInstance", "")
                s.devNick = o.optString("devNick", "")
                s.verboseLogging = o.optBoolean("verboseLogging", false)
                s.notificationsEnabled = o.optBoolean("notificationsEnabled", true)
                s.verifyBuildOnLaunch = o.optBoolean("verifyBuildOnLaunch", true)
                s.modBackups = o.optBoolean("modBackups", true)
                s.modBackupCount = o.optInt("modBackupCount", 5)
                s.checkDiskSpace = o.optBoolean("checkDiskSpace", true)
                s.autoStart = o.optBoolean("autoStart", true)
                s.launcherUpdateEvery = o.optString("launcherUpdateEvery", "day")
                s.playTimeUnit = o.optString("playTimeUnit", "sec")
                s.limitBandwidth = o.optBoolean("limitBandwidth", false)
                s.bandwidthMbit = o.optInt("bandwidthMbit", 0)
                s.disableFullscreenOpt = o.optBoolean("disableFullscreenOpt", false)
                s.highPriority = o.optBoolean("highPriority", false)
                s.disableRealtimeOpt = o.optBoolean("disableRealtimeOpt", false)
                s.modsUpdateEvery = o.optString("modsUpdateEvery", "day")
                s.backgroundDim = o.optInt("backgroundDim", 48).coerceIn(0, 80)
                s.backgroundPath = o.optString("backgroundPath", "")
                s.uiEffects = o.optBoolean("uiEffects", true)
                s.uiBlur = o.optBoolean("uiBlur", true)
                s.uiAnim = o.optBoolean("uiAnim", true)
                s.uiTransitions = o.optBoolean("uiTransitions", false)
                s.uiStyle = o.optString("uiStyle", "standard")
                s.uiFontScale = o.optInt("uiFontScale", 100).coerceIn(85, 125)
                s.launcherImmersive = o.optBoolean("launcherImmersive", true)
                s.keepScreenAwake = o.optBoolean("keepScreenAwake", true)
                s.showFps = o.optBoolean("showFps", false)
                s.useSystemProxy = o.optBoolean("useSystemProxy", false)
                s.maxAttempts = o.optInt("maxAttempts", 3).coerceAtLeast(1)
                s.activeAccountId = o.optString("activeAccountId", "")
                if (s.commonWorld) {
                    s.minecraftVersion = "1.20.1"
                    s.loader = "fabric"
                }
                s
            } catch (e: Exception) {
                CwLog.warn("config.json: ${e.message}")
                s
            }
        }
    }
}
