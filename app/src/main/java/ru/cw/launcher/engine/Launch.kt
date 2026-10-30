package ru.cw.launcher.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import ru.cw.launcher.ui.GameActivity
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class GameLaunch(
    private val context: Context,
    private val cfg: CwSettings,
    private val installer: Installer
) {
    @Volatile var process: Process? = null
    @Volatile var booted: Boolean = false
    @Volatile var stoppedByUser: Boolean = false

    fun running(): Boolean {
        val stage = PhoneVm.stage()
        if (stage != PhoneVm.Stage.STARTING && stage != PhoneVm.Stage.RUNNING) return false
        if (PhoneVm.gameProcessDead()) {
            // The game process was killed from outside (swiped away): close the record at once.
            PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
            booted = false
            return false
        }
        val beat = PhoneVm.beat()
        return beat <= 0L || System.currentTimeMillis() - beat < 15_000
    }

    fun stop() {
        stoppedByUser = true
        booted = false
        // Mark the end first so a restarted game screen can never start Minecraft again.
        PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
        // Ask the game screen to close itself and drop its task: killing a process that still
        // owns a visible task makes Android recreate that screen (and relaunch the game).
        try {
            context.sendBroadcast(Intent(GameActivity.ACTION_STOP).setPackage(context.packageName))
        } catch (_: Exception) {
        }
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.appTasks.forEach { task ->
                if (task.taskInfo.baseIntent.component?.className == GameActivity::class.java.name) task.finishAndRemoveTask()
            }
        } catch (_: Exception) {
        }
        val pid = PhoneVm.pid()
        if (pid > 0 && pid != android.os.Process.myPid()) {
            Thread {
                Thread.sleep(1500)
                // Last resort, only for a process that really is the game process.
                val ours = try {
                    File("/proc/$pid/cmdline").readText().substringBefore('\u0000').endsWith(":game")
                } catch (_: Exception) {
                    false
                }
                if (ours) {
                    try {
                        android.os.Process.killProcess(pid)
                    } catch (_: Exception) {
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        process?.destroy()
        process = null
    }

    /** Game folder of this launch: the main one, or an own build folder in developer mode. */
    fun playDir(): File {
        val home = AppPaths.game(cfg.minecraftVersion)
        val name = if (cfg.developerMode) Instances.safeName(cfg.devInstance) else ""
        if (name.isEmpty()) return home
        val dir = File(home, "instances/$name")
        return if (dir.isDirectory) dir else home
    }

    /** True when an own developer build (not the main folder) is started. */
    fun isBuild(): Boolean = playDir() != AppPaths.game(cfg.minecraftVersion)

    /** Host names and addresses of the game server, which own builds must not reach. */
    private fun blockedHosts(): String {
        val found = linkedSetOf("commonworld.pterohost.ru")
        listOf(cfg.serverAddress, cfg.serverIp).forEach { raw ->
            val host = raw.trim().substringBefore(":")
            if (host.isNotBlank()) found.add(host.lowercase())
        }
        for (host in found.toList()) {
            try {
                java.net.InetAddress.getAllByName(host).forEach { found.add(it.hostAddress.orEmpty()) }
            } catch (_: Exception) {
            }
        }
        return found.filter { it.isNotBlank() }.joinToString(",")
    }

    fun javaBinary(): File? {
        val custom = cfg.javaPath.trim()
        if (custom.isNotEmpty()) {
            val file = File(custom)
            if (file.isFile) return file
        }
        return BundledJava.launcher(context)
    }

    fun command(account: Account, profileId: String): List<String> {
        val version = cfg.minecraftVersion
        val profile = installer.resolved(version, profileId)
        if (profile.mainClass.isBlank()) throw java.io.IOException("В профиле $profileId не указан mainClass")
        val java = javaBinary() ?: throw java.io.IOException(
            if (BundledJava.supported()) {
                "Не найдена Java 17 для Minecraft $version. Встроенная Java должна лежать в самом лаунчере."
            } else {
                "Встроенная Java 17 есть для arm64. Этот процессор: ${android.os.Build.SUPPORTED_ABIS.joinToString()}."
            }
        )
        java.setExecutable(true)
        val gameDir = playDir()
        gameDir.mkdirs()
        applyOptions(gameDir)
        val natives = extractNatives(version, profile, gameDir)
        val cp = classpath(version, profile)
        if (profile.mainClass.contains("Knot") && !classpathHasAsm(version, profile)) {
            throw IOException(downloadError("Fabric Loader не видит org/objectweb/asm/ClassReader", "org.ow2.asm:asm"))
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val totalMb = (mem.totalMem / (1024 * 1024)).toInt().coerceAtLeast(1024)
        val availMb = (mem.availMem / (1024 * 1024)).toInt().coerceAtLeast(768)
        // 155 mods with Sodium/Iris do not fit in 2 GB: the collector then runs back to back
        // and start-up takes minutes. Allow up to 3 GB when the phone has the memory free.
        // An explicit RAM choice in settings may go above the automatic 3 GB limit.
        val cap = if (cfg.ramMb > 0) (availMb - 512).coerceAtLeast(768) else minOf(3072, (availMb - 768).coerceAtLeast(768))
        val ram = minOf(cfg.gameRamMb(totalMb), cap).coerceAtLeast(512)
        val xms = minOf(512, ram)
        val cmd = mutableListOf<String>()
        cmd.add(java.absolutePath)
        cmd.add("-Xms${xms}M")
        cmd.add("-Xmx${ram}M")
        val directMemory = when (cfg.optimizationMode) {
            2, 6 -> minOf(cfg.directMemoryMb, 256)
            5 -> maxOf(cfg.directMemoryMb, 512)
            else -> cfg.directMemoryMb
        }
        cmd.add("-XX:MaxDirectMemorySize=${directMemory}M")
        cmd.addAll(memoryFlags())
        val player = account.username.ifBlank { "Player" }
        val jvm = profile.jvmArgs.ifEmpty {
            listOf(
                "-Djava.library.path=\${natives_directory}",
                "-Dminecraft.launcher.brand=\${launcher_name}",
                "-Dminecraft.launcher.version=\${launcher_version}"
            )
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        var jvmIndex = 0
        while (jvmIndex < jvm.size) {
            val raw = jvm[jvmIndex]
            var r = raw
                .replace("\${classpath}", "")
                .replace("\${natives_directory}", natives.absolutePath)
                .replace("\${launcher_name}", "CWLauncher")
                .replace("\${launcher_version}", CwLog.VERSION)
                .replace("\${assets_root}", AppPaths.assets(version).absolutePath)
                .replace("\${game_dir}", gameDir.absolutePath)
                .replace("\${auth_player_name}", player)
                .replace("\${user_properties}", "{}")
                .replace("\${clientid}", "")
                .replace("\${remoteserver}", "")
                .replace("\${version_name}", profileId)
            if (r == "-cp" || r == "-classpath") {
                jvmIndex += 2
                continue
            }
            jvmIndex++
            if (r.startsWith("-Xmx") || r.startsWith("-Xms") || r.startsWith("-XX:MaxDirectMemorySize")) continue
            if (r.startsWith("-Djava.library.path=")) {
                cmd.add("-Djava.library.path=${jreLibraryPath(natives)}")
                continue
            }
            if (hostileJvm(r)) continue
            if (r.contains("\${") || r.isBlank()) continue
            cmd.add(r)
        }
        if (cmd.none { it.startsWith("-Djava.library.path=") }) {
            cmd.add("-Djava.library.path=${jreLibraryPath(natives)}")
        }
        // MJLauncher points LWJGL at the substituted Android ARM64 natives,
        // not at Mojang's desktop-linux classifiers or only at the APK libs.
        cmd.add("-Dorg.lwjgl.librarypath=${natives.absolutePath}")
        cmd.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${File(AppPaths.cache, "lwjgl_native/$profileId").apply { mkdirs() }.absolutePath}")
        cmd.add("-Djna.boot.library.path=$nativeDir")
        cmd.add("-Djna.tmpdir=${AppPaths.tmp.absolutePath}")
        cmd.add("-Dorg.lwjgl.opengl.libname=libGLMojo.so")
        cmd.add("-Dorg.lwjgl.freetype.libname=$nativeDir/libfreetype.so")
        cmd.add("-Dorg.lwjgl.system.allocator=system")
        cmd.add("-Dcw.nativedir=$nativeDir")
        cmd.add("-Dcw.jvm.log=${File(AppPaths.logs, "jvm.log").absolutePath}")
        cmd.add("-Dcw.game.log=${File(gameDir, "logs/latest.log").absolutePath}")
        cmd.add("-Dfabric.noGui=true")
        cmd.add("-Dloader.disable_forked_guis=true")
        cmd.add("-Dfml.earlyprogresswindow=false")
        cmd.add("-Dfabric.log.file=${File(AppPaths.logs, "fabric.log").absolutePath}")
        cmd.add("-Dfabric.gameVersion=$version")
        val clientJar = installer.clientJar(version)
        if (clientJar.isFile) cmd.add("-Dfabric.gameJarPath.client=${clientJar.absolutePath}")
        cmd.add("-Djava.home=${BundledJava.home().absolutePath}")
        cmd.add("-Duser.dir=${gameDir.absolutePath}")
        // Java 17 AWT bridge used by MJLauncher. Some Minecraft/Fabric code
        // touches AWT before GLFW even when no Swing window is shown.
        val cacio = bundledJar(
            "cacio-tta-d4553e5689-jar-with-dependencies.jar",
            File(AppPaths.root, "caciocavallo17")
        )
        cmd.add("-Djava.awt.headless=false")
        cmd.add("-Dcacio.managed.screensize=${cfg.screenWidth}x${cfg.screenHeight}")
        cmd.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
        cmd.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
        cmd.add("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel")
        cmd.add("-javaagent:${cacio.absolutePath}")
        cmd.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
        cmd.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
        listOf(
            "--add-exports=java.desktop/java.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
        ).forEach(cmd::add)
        cmd.add("-Dos.name=Linux")
        cmd.add("-Dos.version=Android-${android.os.Build.VERSION.RELEASE}")
        cmd.add("-Djdk.lang.Process.launchMechanism=FORK")
        cmd.add("-Dlog4j2.formatMsgNoLookups=true")
        cmd.add("-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors().coerceAtLeast(1)}")
        cmd.add("-Dorg.lwjgl.system.allocator=system")
        cmd.add("-Dio.netty.transport.noNative=true")
        cmd.add("-Dio.netty.handler.ssl.noOpenSsl=true")
        cmd.add("-Dfile.encoding=UTF-8")
        val locale = Locale.getDefault()
        cmd.add("-Duser.language=${locale.language.ifBlank { "en" }}")
        cmd.add("-Duser.country=${locale.country.ifBlank { "US" }}")
        cmd.add("-Duser.timezone=${TimeZone.getDefault().id}")
        cmd.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
        // A copy of libjnidispatch on the library path crashes Fabric. Use the copy from the mod jar.
        cmd.add("-Djna.nosys=true")
        cmd.add("-Djava.io.tmpdir=${AppPaths.tmp.absolutePath}")
        cmd.add("-Duser.home=${AppPaths.root.absolutePath}")
        cmd.add("-Dorg.lwjgl.util.NoChecks=true")
        val selectedSkin = Skins.skinFile(account.id).takeIf { it.isFile }
        if (selectedSkin != null) {
            try {
                val png = Skins.gamePng(selectedSkin)
                val published = png != null && SkinApi.publishToEly(account, png, Skins.slim(selectedSkin))
                val api = if (published) Ely.INJECTOR_API else SkinApi.apiRoot(selectedSkin, account)
                cmd.add(agentArgument(api))
                CwLog.info(if (published) "Активный скин опубликован в Ely.by" else "Активный скин подключён через локальный Skin API")
            } catch (e: Exception) {
                CwLog.warn("Скин не подключён: ${e.message}")
                if (account.type == AccountType.ELY) runCatching { cmd.add(agentArgument()) }
            }
        } else if (account.type == AccountType.ELY) {
            try { cmd.add(agentArgument()) }
            catch (e: Exception) { CwLog.warn("authlib-injector недоступен: ${e.message}") }
        }
        cfg.extraJvmArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { arg ->
            if (arg.startsWith("-Xm") || arg == "-cp" || arg == "-classpath" || hostileJvm(arg)) return@forEach
            if (arg.startsWith("-D") || arg.startsWith("-XX") || arg.startsWith("--add-")) cmd.add(arg)
        }
        if (isBuild()) {
            // Own builds can never join the Common World server: the agent refuses its addresses.
            val guard = bundledJar("cw-netguard.jar", File(AppPaths.root, "netguard"))
            cmd.add("-Djava.security.manager=allow")
            cmd.add("-Dcw.blockhosts=${blockedHosts()}")
            cmd.add("-javaagent:${guard.absolutePath}")
        }
        cmd.add("-cp")
        cmd.add(cp)
        // MJLauncher invokes the resolved Minecraft/Fabric main class directly.
        // An intermediate reflective main changes the class-loader context and
        // breaks Fabric Knot before it can create the client window.
        cmd.add(profile.mainClass)
        val token = if (account.type == AccountType.OFFLINE) "0" else Ely.token(account)
        val vars = mapOf(
            "\${auth_player_name}" to player,
            "\${version_name}" to profileId,
            "\${game_directory}" to gameDir.absolutePath,
            "\${assets_root}" to AppPaths.assets(version).absolutePath,
            "\${game_assets}" to AppPaths.assets(version).absolutePath,
            "\${assets_index_name}" to profile.assets,
            "\${auth_session}" to token,
            "\${auth_uuid}" to account.shortUuid(),
            "\${auth_access_token}" to token,
            "\${user_type}" to if (account.type == AccountType.OFFLINE) "legacy" else "mojang",
            "\${version_type}" to if (profileId.startsWith("fabric-loader")) "Fabric" else "release",
            "\${launcher_name}" to "CWLauncher",
            "\${launcher_version}" to CwLog.VERSION,
            "\${natives_directory}" to natives.absolutePath,
            "\${user_properties}" to "{}",
            "\${resolution_width}" to cfg.screenWidth.toString(),
            "\${resolution_height}" to cfg.screenHeight.toString(),
            "\${classpath}" to "",
            "\${clientid}" to "",
            "\${remoteserver}" to "",
            "\${quickPlayPath}" to "",
            "\${primaryServer}" to (if (cfg.autoConnect && !isBuild()) cfg.serverAddress else "")
        )
        val game = mutableListOf<String>()
        for (raw in profile.gameArgs) {
            var r = raw
            vars.forEach { (k, v) -> r = r.replace(k, v) }
            if (r.contains("\${")) continue
            game.add(r)
        }
        putFlag(game, "--gameDir", gameDir.absolutePath)
        putFlag(game, "--assetsDir", AppPaths.assets(version).absolutePath)
        if (profile.assets.isNotBlank()) putFlag(game, "--assetIndex", profile.assets)
        if (cfg.screenWidth > 0) putFlag(game, "--width", cfg.screenWidth.toString())
        if (cfg.screenHeight > 0) putFlag(game, "--height", cfg.screenHeight.toString())
        // Join arguments come only from the "Auto-connect" setting. The game starts the join
        // itself once the client has loaded; it is one attempt, there is no reconnect loop.
        for (flag in listOf("--quickPlayMultiplayer", "--quickPlaySingleplayer", "--quickPlayRealms", "--quickPlayPath", "--server", "--port")) {
            val at = game.indexOf(flag)
            if (at >= 0) {
                game.removeAt(at)
                if (at < game.size && !game[at].startsWith("--")) game.removeAt(at)
            }
        }
        if (cfg.autoConnect && !isBuild()) addJoin(game, version)
        cmd.addAll(game)
        return cmd
    }

    fun outputLogs(): List<File> = listOf(
        AppPaths.gameLog,
        File(playDir(), "logs/latest.log"),
        File(AppPaths.logs, "jvm.log"),
        File(AppPaths.logs, "fabric.log"),
        File(AppPaths.logs, "hs_err.log"),
        File(AppPaths.logs, "android-exit.txt"),
        File(AppPaths.logs, "android-exit-trace.bin"),
        PhoneVm.traceFile()
    ).plus(
        File(playDir(), "crash-reports").listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(2)
            .orEmpty()
    )

    fun windowReady(): Boolean {
        if (booted) return true
        val files = outputLogs()
        for (file in files) {
            val text = tail(file)
            if (text.contains("Sound engine started") || text.contains("LWJGL Version:")
                || text.contains("GLFW инициализирован; Surface передана")) {
                booted = true
                return true
            }
        }
        return false
    }

    fun freshGameLines(cursors: MutableMap<String, Long>): List<String> {
        val files = outputLogs()
        val lines = mutableListOf<String>()
        for (file in files) {
            if (!file.isFile) continue
            val key = file.absolutePath
            val seen = cursors[key] ?: 0L
            val len = file.length()
            if (len <= seen) continue
            val text = try {
                file.inputStream().use { input ->
                    if (seen > 0) input.skip(seen)
                    input.readBytes().toString(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                continue
            }
            cursors[key] = len
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines.add(it) }
        }
        return lines
    }

    private fun tail(file: File, max: Int = 64_000): String {
        if (!file.isFile) return ""
        return try {
            file.inputStream().use { input ->
                val len = file.length()
                if (len > max) input.skip(len - max)
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun start(account: Account, profileId: String) {
        stoppedByUser = false
        booted = false
        val cmd = command(account, profileId)
        AppPaths.gameLog.parentFile?.mkdirs()
        CwLog.rotate(AppPaths.gameLog, 1, 2)
        CwLog.rotate(File(AppPaths.logs, "jvm.log"), 1, 2)
        CwLog.rotate(File(AppPaths.logs, "fabric.log"), 1, 2)
        File(playDir(), "logs/latest.log").delete()
        File(AppPaths.logs, "hs_err.log").delete()
        val heap = cmd.firstOrNull { it.startsWith("-Xmx") }.orEmpty()
        // A game process left over from an earlier launch still holds a Java machine that
        // cannot be restarted (JNI_EEXIST) and competes for memory and CPU. End it first.
        val previous = PhoneVm.pid()
        if (previous > 0 && previous != android.os.Process.myPid()) {
            try {
                android.os.Process.killProcess(previous)
            } catch (_: Exception) {
            }
        }
        val launchId = PhoneVm.prepare(cmd.drop(1), playDir(), cfg.vsync && !(cfg.shaderAutoScale && Shaders.active(AppPaths.game(cfg.minecraftVersion))))
        CwLog.info("Сборка ${CwLog.VERSION}")
        CwLog.info("Запуск $launchId на экране телефона: profile=$profileId, heap=$heap, abi=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        CwLog.info("Запуск $launchId: java=${cmd.first()}, cwd=${playDir().absolutePath}, аргументов=${cmd.size - 1}")
        val intent = Intent(context, GameActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(GameActivity.EXTRA_LAUNCH, launchId)
        context.startActivity(intent)
    }

    private fun classpath(version: String, profile: VersionProfile): String {
        val files = linkedSetOf<String>()
        val mojoLwjgl = profile.libraries.any { it.name.startsWith("org.lwjgl:lwjgl:3.3.1") }
        if (mojoLwjgl) {
            files.addAll(mojoLwjgl31())
            CwLog.info("LWJGL 3.3.1 заменён на Android-сборки MojoLauncher r6")
        }
        for (lib in profile.libraries) {
            if (!lib.download || lib.isNative() || crashesJvm(lib.name)) continue
            if (mojoLwjgl && lib.name.startsWith("org.lwjgl:")) continue
            val file = File(AppPaths.libs(version), lib.path)
            if (!file.isFile) throw java.io.IOException(downloadError("Файл не найден перед запуском", lib.name))
            if (file.name.endsWith(".jar", true) && !looksLikeZip(file)) {
                throw java.io.IOException(downloadError("Файл повреждён", lib.name))
            }
            files.add(file.absolutePath)
        }
        val client = installer.clientJar(version)
        if (!client.isFile) throw java.io.IOException(downloadError("Нет client.jar", client.name))
        files.add(client.absolutePath)
        CwLog.info("Classpath: ${files.size} файлов")
        return files.joinToString(File.pathSeparator)
    }

    private fun mojoLwjgl31(): List<String> {
        val names = listOf(
            "lwjgl-3.3.1.jar",
            "lwjgl-glfw-3.3.1.jar",
            "lwjgl-jemalloc-3.3.1.jar",
            "lwjgl-openal-3.3.1.jar",
            "lwjgl-opengl-3.3.1.jar",
            "lwjgl-stb-3.3.1.jar",
            "lwjgl-tinyfd-3.3.1.jar"
        )
        val dir = File(AppPaths.cache, "mojo-lwjgl/3.3.1-r6").apply { mkdirs() }
        return names.map { name ->
            val target = File(dir, name)
            if (!looksLikeZip(target)) {
                val temporary = File(dir, "$name.part")
                context.assets.open("bridge/$name").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (!looksLikeZip(temporary)) throw IOException("Повреждён встроенный MojoLauncher LWJGL: $name")
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) throw IOException("Не удалось установить MojoLauncher LWJGL: $name")
            }
            target.absolutePath
        }
    }

    private fun bundledJar(name: String, dir: File): File {
        dir.mkdirs()
        val target = File(dir, name)
        if (!looksLikeZip(target)) {
            val temporary = File(dir, "$name.part")
            context.assets.open("bridge/$name").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (!looksLikeZip(temporary)) throw IOException("Повреждён компонент MJLauncher: $name")
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) throw IOException("Не удалось установить компонент MJLauncher: $name")
        }
        return target
    }

    private fun classpathHasAsm(version: String, profile: VersionProfile): Boolean {
        val cores = profile.libraries.filter { it.download && isAsmCore(it.name) }
        val jars = if (cores.isNotEmpty()) cores else profile.libraries.filter { it.download }
        return jars.any { jarHasEntry(File(AppPaths.libs(version), it.path), ASM_CLASS) }
    }

    private fun extractNatives(version: String, profile: VersionProfile, gameDir: File): File {
        val natives = File(gameDir, "natives/${System.nanoTime()}")
        natives.mkdirs()
        var count = extractMojoLwjgl31Natives(profile, natives)
        for (lib in profile.libraries) {
            if (!lib.download || !lib.isNative()) continue
            if (lib.name.startsWith("org.lwjgl:")) continue
            if (lib.name.contains("jemalloc", true) || lib.name.contains("tinyfd", true)) continue
            val jar = File(AppPaths.libs(version), lib.path)
            if (!jar.isFile) continue
            ZipFile(jar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.startsWith("META-INF")) continue
                    val simple = entry.name.substringAfterLast('/')
                    if (!simple.endsWith(".so") || simple.endsWith(".so.git")) continue
                    val out = File(natives, simple)
                    if (!out.canonicalPath.startsWith(natives.canonicalPath)) continue
                    val part = File(natives, "$simple.part")
                    zip.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (!elfMatchesDevice(part) || linksLinuxGlibc(part)) {
                        part.delete()
                        CwLog.info("Библиотека компьютера $simple пропущена, на телефоне своя")
                        continue
                    }
                    if (out.exists()) out.delete()
                    if (!part.renameTo(out)) {
                        part.copyTo(out, overwrite = true)
                        part.delete()
                    }
                    out.setExecutable(true)
                    count++
                }
            }
        }
        CwLog.info("Извлечено natives-файлов: $count (${deviceArch()})")
        if (count == 0) CwLog.info("Библиотеки Linux из Minecraft пропущены. Экран телефона рисуют свои графические библиотеки.")
        return natives
    }

    private fun extractMojoLwjgl31Natives(profile: VersionProfile, target: File): Int {
        if (profile.libraries.none { it.name.startsWith("org.lwjgl:lwjgl:3.3.1") }) return 0
        val names = listOf(
            "lwjgl-natives-linux-arm64-3.3.1.jar",
            "lwjgl-opengl-natives-linux-arm64-3.3.1.jar",
            "lwjgl-stb-natives-linux-arm64-3.3.1.jar",
            "lwjgl-tinyfd-natives-linux-arm64-3.3.1.jar"
        )
        var count = 0
        for (name in names) {
            context.assets.open("bridge/$name").use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory || !entry.name.endsWith(".so")) continue
                        val simple = entry.name.substringAfterLast('/')
                        val out = File(target, simple)
                        out.outputStream().use { zip.copyTo(it) }
                        out.setExecutable(true)
                        count++
                    }
                }
            }
        }
        CwLog.info("UniLWJGL natives заменены по substitutions.json MJLauncher: $count")
        return count
    }

    private fun crashesJvm(name: String): Boolean {
        val n = name.lowercase(java.util.Locale.ROOT)
        return n.contains("native-epoll") || n.contains("native-kqueue") || n.contains("native-io_uring")
            || n.contains("tcnative") || n.contains("jemalloc") || n.contains("tinyfd")
    }

    private fun jreLibraryPath(natives: File): String {
        val home = BundledJava.home()
        return listOf(
            natives.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            File(home, "lib/server").absolutePath,
            File(home, "lib").absolutePath
        ).joinToString(":")
    }

    private fun addJoin(game: MutableList<String>, version: String) {
        val raw = cfg.serverAddress.trim().ifBlank { cfg.serverIp.trim() }
        if (raw.isBlank()) return
        if (game.any { it == "--quickPlayMultiplayer" || it == "--server" }) return
        val host = if (raw.contains(":")) raw.substringBeforeLast(":") else raw
        val port = if (raw.contains(":")) raw.substringAfterLast(":") else "25565"
        val minor = version.substringAfter(".").substringBefore(".").substringBefore("-").toIntOrNull() ?: 0
        val major = version.substringBefore(".").toIntOrNull() ?: 1
        if (major > 1 || minor >= 20) {
            game.add("--quickPlayMultiplayer")
            game.add(if (raw.contains(":")) raw else "$host:$port")
        } else {
            game.add("--server")
            game.add(host)
            game.add("--port")
            game.add(port)
        }
    }

    private fun linksLinuxGlibc(file: File): Boolean {
        val data = file.readBytes()
        val marker = "libc.so.6".toByteArray(Charsets.US_ASCII)
        if (data.size < marker.size) return false
        for (i in 0..data.size - marker.size) {
            var same = true
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }

    private fun elfMatchesDevice(file: File): Boolean {
        val head = ByteArray(20)
        val n = file.inputStream().use { it.read(head) }
        if (n < 20 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte()) return true
        val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
        return when (deviceArch()) {
            "arm64" -> machine == 183
            "arm32" -> machine == 40
            "x64" -> machine == 62
            "x86" -> machine == 3
            else -> true
        }
    }

    fun applyOptions(gameDir: File = AppPaths.game(cfg.minecraftVersion)) {
        if (!gameDir.isDirectory && !gameDir.mkdirs()) return
        val options = File(gameDir, "options.txt")
        val values = linkedMapOf<String, String>()
        if (options.isFile) {
            options.readLines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) values[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        values["fullscreen"] = "false"
        values.putIfAbsent("lang", cfg.resolvedLanguage())
        val mode = cfg.optimizationMode.coerceIn(0, 6)
        if (mode == 0) {
            writeOptionsAtomic(options, values)
            CwLog.info("Оптимизация Minecraft выключена: игровые настройки пользователя сохранены")
            return
        }

        // An enabled preset is intentionally re-applied at launch, so changing the preset has an
        // immediate and predictable result. Turning it off stops touching all graphics values.
        val renderDistance = when (mode) { 1 -> 8; 2 -> 5; 3 -> 6; 4, 5 -> 3; else -> 2 }
        val simulationDistance = when (mode) { 1 -> 6; 3 -> 6; else -> 5 }
        val maxFps = when (mode) { 1 -> 60; 2 -> 45; 3 -> 60; 4 -> 120; 5 -> 90; else -> 60 }
        values["enableVsync"] = if (mode == 1) cfg.vsync.toString() else "false"
        values["maxFps"] = maxFps.toString()
        values["graphicsMode"] = "0"
        values["fancyGraphics"] = "false"
        values["renderDistance"] = renderDistance.toString()
        values["simulationDistance"] = simulationDistance.toString()
        values["entityDistanceScaling"] = when (mode) { 1 -> "0.75"; 3 -> "0.65"; 4, 5, 6 -> "0.5"; else -> "0.6" }
        values["particles"] = if (mode == 1 || mode == 3) "1" else "2"
        values["clouds"] = "false"
        values["renderClouds"] = "false"
        values["mipmapLevels"] = when (mode) { 1 -> "2"; 3 -> "1"; else -> "0" }
        values["biomeBlendRadius"] = if (mode == 1) "2" else "0"
        values["ao"] = if (mode == 1) "true" else "false"
        values["entityShadows"] = "false"
        values["bobView"] = "false"
        values["prioritizeChunkUpdates"] = "0"
        values["chunkBuilderThreads"] = when (mode) { 1, 3 -> "2"; else -> "1" }
        values["menuBackgroundBlurriness"] = "0"
        values["distortionEffectScale"] = "0.0"
        values["fovEffectScale"] = "0.0"
        if (cfg.screenWidth in 640..7680) values["overrideWidth"] = cfg.screenWidth.toString()
        if (cfg.screenHeight in 360..4320) values["overrideHeight"] = cfg.screenHeight.toString()
        writeOptionsAtomic(options, values)
        CwLog.info("Применён профиль оптимизации $mode: $renderDistance чанков, симуляция $simulationDistance, $maxFps fps")
    }

    private fun writeOptionsAtomic(options: File, values: Map<String, String>) {
        val text = values.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n"
        val part = File(options.parentFile, "options.txt.part")
        part.writeText(text)
        if (!part.renameTo(options)) {
            options.writeText(text)
            part.delete()
        }
    }

    private fun agentArgument(apiRoot: String = Ely.INJECTOR_API): String {
        val jar = File(AppPaths.injector, "authlib-injector.jar")
        if (!jar.isFile || jar.length() < 10_000 || !looksLikeZip(jar)) {
            val metas = listOf(
                "https://authlib-injector.yushi.moe/artifact/latest.json",
                "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json"
            )
            var url: String? = null
            val net = Net()
            for (meta in metas) {
                try {
                    val found = JSONObject(net.getText(meta)).optString("download_url")
                    if (found.startsWith("https://")) {
                        url = found
                        break
                    }
                } catch (_: Exception) {
                }
            }
            val targets = listOfNotNull(
                url?.replace("bmclapi1.sakura.sld.tw", "bmclapi2.bangbang93.com"),
                "https://authlib-injector.yushi.moe/artifact/56/authlib-injector-1.2.8.jar",
                "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/56/authlib-injector-1.2.8.jar"
            )
            var last: Exception? = null
            for (target in targets) {
                try {
                    net.download(target, jar, null, 0, null, null)
                    if (looksLikeZip(jar)) break
                    jar.delete()
                    last = java.io.IOException("Загружен не JAR")
                } catch (e: Exception) {
                    last = e
                    jar.delete()
                }
            }
            if (!jar.isFile) throw last ?: java.io.IOException("Не удалось получить authlib-injector")
        }
        return "-javaagent:${jar.absolutePath}=$apiRoot"
    }

    private fun hostileJvm(arg: String): Boolean {
        return arg.contains("G1") || arg.contains("UseStringDeduplication") || arg.contains("MaxGCPauseMillis")
            || arg.contains("UnlockExperimentalVMOptions") || arg.contains("AlwaysPreTouch")
            || arg.contains("UseLargePages") || arg.contains("UseNUMA") || arg.contains("UseTransparentHugePages")
            || arg.contains("UseParallelGC") || arg.contains("UseZGC") || arg.contains("UseShenandoah")
            || arg.contains("UseCompressedOops") || arg.contains("UseLSE") || arg.contains("UseSVE")
            || arg.contains("UseAES") || arg.contains("UseSHA") || arg.contains("UseFMA")
            || arg.contains("UseCRC32") || arg.contains("TieredStopAtLevel") || arg.contains("UseContainerSupport")
    }

    private fun memoryFlags(): List<String> {
        val memorySaver = cfg.optimizationMode == 2 || cfg.optimizationMode == 6
        val throughput = cfg.optimizationMode == 4 || cfg.optimizationMode == 5
        val useParallel = if (memorySaver) false else throughput || cfg.gcMode == 1
        val c1Only = memorySaver || cfg.jitMode == 1
        return listOf(
        if (useParallel) "-XX:+UseParallelGC" else "-XX:+UseSerialGC",
        "-XX:+DisableExplicitGC",
        "-XX:+PerfDisableSharedMem",
        "-Xlog:gc:file=${File(AppPaths.logs, "gc.log").absolutePath}:uptime:filecount=1,filesize=2M",
        "-XX:ErrorFile=${File(AppPaths.logs, "hs_err.log").absolutePath}"
        ) + (if (c1Only) listOf("-XX:TieredStopAtLevel=1") else emptyList())
    }

    private fun putFlag(args: MutableList<String>, flag: String, value: String?) {
        if (args.any { it == flag }) return
        args.add(flag)
        if (value != null) args.add(value)
    }
}
