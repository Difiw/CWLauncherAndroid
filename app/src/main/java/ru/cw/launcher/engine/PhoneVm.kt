package ru.cw.launcher.engine

import android.app.Activity
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import git.artdeell.mojo.utils.JREUtils
import git.artdeell.mojo.utils.jre.JavaRunner
import git.artdeell.dnbootstrap.glfw.GLFW
import git.artdeell.mojoexec.MojoExec
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Runs Minecraft in the game process and draws it on the phone screen.
 * The status files are shared with the launcher process.
 */
object PhoneVm {
    enum class Stage { IDLE, STARTING, RUNNING, EXITED }

    const val UNKNOWN_EXIT_CODE: Int = Int.MIN_VALUE

    @Volatile var exitCode: Int = UNKNOWN_EXIT_CODE
    @Volatile var error: String? = null

    private var args: List<String> = emptyList()
    private var gameDir: File = File(".")
    private var forceVsync: Boolean = false
    @Volatile private var windowSurface: Surface? = null
    @Volatile private var glfwReady: Boolean = false
    private var surfaceAttached: Boolean = false

    /**
     * A Java machine cannot be created twice in one process (JNI_CreateJavaVM returns
     * JNI_EEXIST, -5). Once started, a new game screen must only re-attach its Surface.
     */
    @Volatile var vmStarted: Boolean = false

    fun prepare(jvmArgs: List<String>, dir: File, vsync: Boolean): String {
        args = jvmArgs
        gameDir = dir
        forceVsync = vsync
        exitCode = UNKNOWN_EXIT_CODE
        error = null
        val launchId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val saved = JSONArray()
        jvmArgs.forEach { saved.put(it) }
        file("args.json").writeText(saved.toString())
        file("gamedir").writeText(dir.absolutePath)
        file("vsync").writeText(if (vsync) "1" else "0")
        write("launch-id", launchId)
        write("started-at", System.currentTimeMillis().toString())
        write("stage", Stage.STARTING.name)
        write("code", UNKNOWN_EXIT_CODE.toString())
        write("error", "")
        write("pid", "0")
        write("beat", System.currentTimeMillis().toString())
        trace("[$launchId] PREPARED: аргументы=${jvmArgs.size}, каталог=${dir.absolutePath}")
        trace("[$launchId] Команда: java ${redactedCommand(jvmArgs)}")
        return launchId
    }

    /** Launch id this process has already opened a game screen for (in memory: dies with the process). */
    @Volatile private var openedHere: String = ""

    /**
     * A game screen may only start the Java machine for a launch that the launcher just
     * prepared and nobody has used yet. When Android restarts a killed game process it
     * re-delivers the old intent; that stale start must close itself instead of launching
     * Minecraft again.
     */
    @Synchronized
    fun claimLaunch(id: String): Boolean {
        if (id.isBlank()) return false
        if (openedHere == id) return true // same process: the screen was only recreated
        if (id != launchId() || stage() == Stage.EXITED || read("consumed") == id) return false
        write("consumed", id)
        openedHere = id
        return true
    }

    fun stage(): Stage {
        val raw = read("stage")
        return try {
            if (raw.isBlank()) Stage.IDLE else Stage.valueOf(raw)
        } catch (_: Exception) {
            Stage.IDLE
        }
    }

    fun pid(): Int = read("pid").toIntOrNull() ?: 0

    /** False only when a game process was recorded and no longer exists (killed from outside). */
    fun gameProcessDead(): Boolean {
        val pid = pid()
        if (pid <= 0) return false
        return try {
            !File("/proc/$pid/cmdline").readText().substringBefore('\u0000').endsWith(":game")
        } catch (_: Exception) {
            true
        }
    }

    fun beat(): Long = read("beat").toLongOrNull() ?: 0L

    fun storedCode(): Int = read("code").toIntOrNull() ?: UNKNOWN_EXIT_CODE

    fun storedError(): String = read("error")

    fun launchId(): String = read("launch-id")

    fun startedAt(): Long = read("started-at").toLongOrNull() ?: 0L

    fun traceFile(): File {
        val safeId = launchId().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
        return File(AppPaths.logs, "launch-$safeId.log")
    }

    fun beatNow() {
        write("beat", System.currentTimeMillis().toString())
    }

    fun mark(stage: Stage, code: Int, message: String?) {
        exitCode = code
        error = message
        write("code", code.toString())
        write("error", message.orEmpty().replace('\n', ' '))
        write("stage", stage.name)
        write("beat", System.currentTimeMillis().toString())
        trace("[${launchId().ifBlank { "unknown" }}] ${stage.name}: code=${formatCode(code)}" +
            if (message.isNullOrBlank()) "" else ", ${message.replace('\n', ' ').take(300)}")
    }

    private fun trace(message: String) {
        try {
            traceFile().appendText("${java.time.LocalDateTime.now()} $message\n")
        } catch (_: Exception) {
        }
        CwLog.info(message)
    }

    fun launch(context: Context, surface: Surface, width: Int, height: Int): Int {
        if (!surface.isValid) throw IOException("Поверхность окна игры не готова")
        AppPaths.init(context)
        loadSaved()
        thread(name = "cw-beat", isDaemon = true) {
            while (true) {
                try {
                    beatNow()
                } catch (_: Exception) {
                }
                Thread.sleep(1000)
            }
        }
        val home = BundledJava.ensure(context)
        val jvm = File(home, "lib/server/libjvm.so")
        if (!jvm.isFile) throw IOException("В Java 17 нет lib/server/libjvm.so")
        // Start the persistent JVM log before touching EGL or loading game natives.
        // Native renderer diagnostics are written to stdout/stderr too, and must
        // survive a native crash before Minecraft has a chance to create latest.log.
        redirectJvmOutput()
        trace("[${launchId()}] Запуск диагностики JVM: ${File(AppPaths.logs, "jvm.log").absolutePath}")
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val runtimeVersion = File(home, "release").takeIf { it.isFile }?.useLines { lines ->
            lines.firstOrNull { it.startsWith("JAVA_VERSION=") }?.substringAfter('=')?.trim('"')
        }.orEmpty().ifBlank { "unknown" }
        trace("Java=$runtimeVersion, Android ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()}, process64=${android.os.Process.is64Bit()}")
        trace("Рабочая директория=${gameDir.absolutePath}")
        trace("Каталог native-библиотек=$nativeDir")
        if (CwLog.isDeveloper()) {
            val inventory = File(nativeDir).listFiles()?.filter { it.isFile }
                ?.sortedBy { it.name }?.joinToString { "${it.name}:${it.length()}" }.orEmpty()
            trace("DEVELOPER native inventory: $inventory")
            trace("DEVELOPER runtime home=${home.absolutePath}, jvm=${jvm.absolutePath}:${jvm.length()}")
        }
        set("JAVA_HOME", home.absolutePath)
        set("HOME", AppPaths.root.absolutePath)
        set("TMPDIR", AppPaths.tmp.absolutePath)
        set("POJAV_NATIVEDIR", nativeDir)
        // Environment used by MojoLauncher's GL4ESRenderSpec.
        set("force_glsl_extensions_warn", "true")
        set("allow_higher_compat_version", "true")
        set("allow_glsl_extension_directive_midshader", "true")
        set("LIBGL_NOERROR", "1")
        if (hangingLinker()) set("POJAV_EMUI_ITERATOR_MITIGATE", "1")
        set("FORCE_VSYNC", if (forceVsync) "true" else "false")
        set("MESA_GLSL_CACHE_DIR", AppPaths.cache.absolutePath)
        set("PATH", File(home, "bin").absolutePath + ":" + (Os.getenv("PATH") ?: ""))
        val initialLd = javaLibraryPath(home.absolutePath, nativeDir)
        set("LD_LIBRARY_PATH", initialLd)
        trace("NATIVE: вызов setLdLibraryPath")
        JREUtils.setLdLibraryPath(initialLd)
        trace("NATIVE: setLdLibraryPath завершён")
        val jvmLog = File(AppPaths.logs, "jvm.log")
        if (JREUtils.redirectStdio(jvmLog.absolutePath)) {
            trace("STDIO: native stdout/stderr перенаправлены без буферизации")
        } else {
            trace("STDIO ERROR: native stdout/stderr не перенаправлены")
        }
        trace("Загружен согласованный JNI-комплект MojoLauncher v3_openjdk")
        // Use MojoLauncher's renderer loader and GLFW backend as one matched stack.
        try {
            prepareGraphics()
            trace("GRAPHICS: системные EGL/GLES пути определены")
        } catch (e: Throwable) {
            trace("GRAPHICS ERROR: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            throw e
        }
        val jre = home.absolutePath
        val ld = javaLibraryPath(jre, nativeDir)
        set("LD_LIBRARY_PATH", ld)
        JREUtils.setLdLibraryPath(ld)
        CwLog.info("Путь Java для запуска: $jre")
        val renderer = chooseRenderer(nativeDir)
        if (!File(nativeDir, renderer.first).isFile) throw IOException("Не открылась графическая библиотека ${renderer.first}")
        enterGameDir()
        try {
            setupWindow(context, surface, width, height)
            trace("EGL: GL4ES renderer подготовлен, ожидание glfwInit")
        } catch (e: Throwable) {
            trace("EGL/NATIVE ERROR: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            throw e
        }
        patchOptions(width, height)
        enterGameDir()
        write("pid", android.os.Process.myPid().toString())
        // This only means the Android surface is attached. Do not label it as a
        // created Minecraft window: GLFW may still fail while JLI is starting.
        CwLog.info("Поверхность ${width}x${height} подключена; запускаю Java main через JNI_CreateJavaVM")
        set("LD_LIBRARY_PATH", ld)
        JavaRunner.nativeSetupExit(context.applicationContext)
        CwLog.info("Завершение игры перехвачено")
        val launch = parseLaunch(sized(width, height).toList())
        trace("JNI main=${launch.mainClass}, JVM-аргументов=${launch.vmArgs.size}, игровых=${launch.gameArgs.size}")
        launch.vmArgs.filter { it.startsWith("-Djava.") || it.startsWith("-Dglfwstub.") || it.startsWith("-Dorg.lwjgl.") }
            .forEach { CwLog.info("JVM option: $it") }
        // RUNNING means JNI_CreateJavaVM is being entered, not that Minecraft has rendered a
        // frame. The UI must use the game/native logs to distinguish those states.
        mark(Stage.RUNNING, UNKNOWN_EXIT_CODE, null)
        val vmArgsAddress = JavaRunner.nativeTransferArguments(launch.vmArgs.toTypedArray())
        val gameArgsAddress = JavaRunner.nativeTransferArguments(launch.gameArgs.toTypedArray())
        if (vmArgsAddress == 0L || gameArgsAddress == 0L) {
            throw IOException("MJLauncher не перенёс аргументы запуска в нативную память")
        }
        trace("Вход в JavaRunner.nativeLoadJVM")
        vmStarted = true
        val loaded = JavaRunner.nativeLoadJVM(
            jvm.absolutePath,
            vmArgsAddress,
            launch.mainClass,
            gameArgsAddress,
            launch.vmArgs.any { it.startsWith("-javaagent:") }
        )
        trace("JavaRunner.nativeLoadJVM вернулся: $loaded")
        return if (loaded) 0 else 1
    }

    private data class ParsedLaunch(
        val vmArgs: List<String>,
        val mainClass: String,
        val gameArgs: List<String>
    )

    private fun parseLaunch(input: List<String>): ParsedLaunch {
        val cpIndex = input.indexOfFirst { it == "-cp" || it == "-classpath" }
        if (cpIndex < 0 || cpIndex + 2 >= input.size) {
            throw IOException("Команда запуска не содержит classpath и main class")
        }
        val classpath = input[cpIndex + 1]
        val mainClass = input[cpIndex + 2]
        trace("Classpath (${classpath.split(File.pathSeparator).size} файлов): $classpath")
        val vmArgs = ArrayList<String>()
        var index = 0
        while (index < cpIndex) {
            val arg = input[index]
            if ((arg == "--add-opens" || arg == "--add-exports" || arg == "--add-reads" || arg == "--add-modules")
                && index + 1 < cpIndex) {
                vmArgs.add("$arg=${input[index + 1]}")
                index += 2
            } else {
                vmArgs.add(arg)
                index++
            }
        }
        vmArgs.removeAll { it == "-d32" || it == "-d64" || it == "-Xint" || it.contains("UseLargePages") }
        vmArgs.removeAll { it.startsWith("-Djava.class.path=") }
        vmArgs.add("-Djava.class.path=$classpath")
        return ParsedLaunch(vmArgs, mainClass, input.drop(cpIndex + 3))
    }

    private fun enterGameDir() {
        if (!gameDir.isDirectory && !gameDir.mkdirs()) {
            throw IOException("Нет папки игры: ${gameDir.absolutePath}")
        }
        if (JREUtils.chdir(gameDir.absolutePath) != 0) {
            throw IOException("Не удалось открыть папку игры: ${gameDir.absolutePath}")
        }
        set("PWD", gameDir.absolutePath)
        CwLog.info("Папка игры: ${gameDir.absolutePath}")
    }

    /**
     * Sodium/Embeddium/Rubidium need OpenGL 3.2 core, which GL4ES (OpenGL 2.1) cannot give.
     * MojoLauncher switches such instances to OpenLTW (GLES 3); do the same here.
     */
    private fun chooseRenderer(nativeDir: String): Pair<String, Int> {
        val mods = File(gameDir, "mods").listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty()
        val needsCore = mods.any { f ->
            val n = f.name.lowercase()
            n.contains("sodium") || n.contains("embeddium") || n.contains("rubidium")
        }
        val mode = try {
            CwSettings.load().rendererMode
        } catch (_: Exception) {
            0
        }
        val ltwOk = File(nativeDir, "libltw.so").isFile
        if (mode == 2 && ltwOk) return "libltw.so" to 3
        if (mode == 1) return "libgl4es_114.so" to 2
        if (needsCore && ltwOk) return "libltw.so" to 3
        return "libgl4es_114.so" to 2
    }

    // ---------------------------------------------------------------- start-up progress

    private class Marker(val text: String, val percent: Int, val stage: String, val regex: Boolean = false)

    private val markers = listOf(
        Marker("Loading Minecraft", 6, "Запуск Fabric"),
        Marker("Loading \\d+ mods", 12, "Чтение списка модов", true),
        Marker("SpongePowered MIXIN", 20, "Подготовка модов"),
        Marker("Searching for graphics cards", 30, "Проверка видеокарты"),
        Marker("Datafixer Bootstrap", 38, "Подготовка данных игры"),
        Marker("Environment: authHost", 45, "Запуск клиента"),
        Marker("Setting user", 48, "Запуск клиента"),
        Marker("Reloading ResourceManager", 84, "Загрузка ресурсов"),
        Marker("Created: ", 91, "Сборка текстур"),
        Marker("Sound engine started", 97, "Почти готово")
    )
    private var bootStartedAt = 0L
    private var bootPercent = 0f
    private var bootStage = "Запуск Java"
    private var bootStageSince = 0L
    private var bootFloor = 0

    class Boot(val percent: Int, val stage: String, val etaSeconds: Int)

    /**
     * Estimates start-up progress from the game log: the latest known milestone, then a slow
     * climb towards the next one while the log is quiet. It never goes backwards. The numbers
     * are an estimate, not a measured percentage.
     */
    fun boot(now: Long): Boot {
        if (bootStartedAt == 0L) {
            bootStartedAt = now
            bootStageSince = now
        }
        val text = try {
            val game = File(gameDir, "logs/latest.log")
            val jvm = File(AppPaths.logs, "jvm.log")
            val source = if (game.isFile && game.length() > 0) game else jvm
            tailText(source, 60_000)
        } catch (_: Exception) {
            ""
        }
        var floor = 3
        var stage = "Запуск Java"
        for (m in markers) {
            val hit = if (m.regex) Regex(m.text).containsMatchIn(text) else text.contains(m.text)
            if (hit) {
                floor = m.percent
                stage = m.stage
            }
        }
        if (glfwReady && floor < 78) {
            floor = 78
            stage = "Создание окна"
        } else if (floor in 48..77) {
            // Mod initialisation: more log lines after "Setting user" means further along.
            val after = text.substringAfter("Setting user", "")
            val lines = after.count { it == '\n' }
            floor = (48 + (lines / 8)).coerceAtMost(76)
        }
        if (floor > bootFloor) {
            bootFloor = floor
            bootStageSince = now
        }
        val next = markers.firstOrNull { it.percent > bootFloor }?.percent ?: 99
        val gap = (next - bootFloor).coerceAtLeast(1)
        val quiet = ((now - bootStageSince) / 1000f).coerceAtLeast(0f)
        val creep = gap * 0.85f * (1f - Math.exp((-quiet / 90f).toDouble()).toFloat())
        val shown = (bootFloor + creep).coerceAtMost(99f)
        if (shown > bootPercent) bootPercent = shown
        if (stage != "Запуск Java" || bootFloor > 3) bootStage = stage
        val elapsed = ((now - bootStartedAt) / 1000f).coerceAtLeast(1f)
        val eta = if (bootPercent >= 12f) (elapsed * (100f - bootPercent) / bootPercent).toInt() else -1
        return Boot(bootPercent.toInt(), bootStage, eta)
    }

    /** True once the game log shows the resource loading screen has been reached. */
    fun windowShowing(): Boolean {
        if (!glfwReady) return false
        val text = try {
            tailText(File(gameDir, "logs/latest.log"), 60_000)
        } catch (_: Exception) {
            ""
        }
        return text.contains("Created: ") || text.contains("Sound engine started") || text.contains("Reloading ResourceManager")
    }

    /** The last [count] non-empty lines of the game log (or of jvm.log before it exists). */
    fun lastGameLines(count: Int, bytes: Int = 8_000): List<String> {
        return try {
            val game = File(gameDir, "logs/latest.log")
            val source = if (game.isFile && game.length() > 0) game else File(AppPaths.logs, "jvm.log")
            tailText(source, bytes).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList().takeLast(count)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tailText(file: File, max: Int): String {
        if (!file.isFile) return ""
        java.io.RandomAccessFile(file, "r").use { f ->
            val size = f.length().coerceAtMost(max.toLong()).toInt()
            val buf = ByteArray(size)
            f.seek(f.length() - size)
            f.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
    }

    /** Last line of the game's latest.log, shown on the start-up screen. */
    fun lastGameLine(): String {
        return try {
            val log = File(gameDir, "logs/latest.log")
            if (!log.isFile) return ""
            java.io.RandomAccessFile(log, "r").use { f ->
                val size = f.length().toInt().coerceAtMost(4096)
                val buf = ByteArray(size)
                f.seek(f.length() - size)
                f.readFully(buf)
                String(buf, Charsets.UTF_8).lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().take(140)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun setupWindow(context: Context, surface: Surface, width: Int, height: Int) {
        MojoExec.setNativeLibraryDir(context.applicationInfo.nativeLibraryDir)
        MojoExec.setDisplayParams(width, height, context.resources.displayMetrics.refreshRateCompat())
        val renderer = chooseRenderer(context.applicationInfo.nativeLibraryDir)
        trace("Renderer: ${renderer.first}, GLES ${renderer.second}")
        if (!MojoExec.prepareEgl(renderer.first, false, true, renderer.second)) {
            throw IOException("MojoLauncher не смог открыть renderer ${renderer.first}")
        }
        // MojoLauncher keeps the Android Surface pending. It only hands it to the
        // native GLFW backend after Minecraft calls glfwInit and receiveInit fires.
        windowSurface = surface
        glfwReady = false
        surfaceAttached = false
        trace("Surface сохранена до инициализации GLFW")
    }

    /** True once Minecraft has called glfwInit, i.e. it reached window creation. */
    fun glfwStarted(): Boolean = glfwReady

    /** Integer value from the game options.txt, or [fallback]. */
    fun optionInt(key: String, fallback: Int): Int {
        return try {
            File(gameDir, "options.txt").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.toIntOrNull()
            } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /** Shows the launcher while the game keeps running in its own task (no restart). */
    fun showLauncher(context: Context) {
        try {
            val intent = android.content.Intent(context, ru.cw.launcher.ui.MainActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            CwLog.warn("Не удалось открыть лаунчер: ${e.message}")
        }
    }

    /** Brings the launcher back to the front; used when the game screen is left or the game ends. */
    fun returnToLauncher(context: Context) {
        try {
            val intent = android.content.Intent(context, ru.cw.launcher.ui.MainActivity::class.java)
            intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            CwLog.warn("Не удалось вернуться в лаунчер: ${e.message}")
        }
    }

    @Synchronized
    fun onGlfwInitialized() {
        glfwReady = true
        val surface = windowSurface
        if (surface != null && surface.isValid) {
            GLFW.nativeSurfaceCreated(surface)
            surfaceAttached = true
            trace("GLFW инициализирован; Surface передана графическому окну")
        } else {
            trace("ОШИБКА: GLFW инициализирован без готовой Surface")
        }
    }

    /** Called when Android gives the activity a new or resized Surface after the game started. */
    @Synchronized
    fun surfaceChanged(surface: Surface) {
        windowSurface = surface
        if (!glfwReady || !surface.isValid) return
        if (surfaceAttached) {
            runCatching { GLFW.nativeSurfaceUpdated() }
        } else {
            GLFW.nativeSurfaceCreated(surface)
            surfaceAttached = true
            trace("Surface пересоздана и передана графическому окну")
        }
    }

    @Synchronized
    fun destroySurface() {
        windowSurface = null
        if (glfwReady && surfaceAttached) runCatching { GLFW.nativeSurfaceDestroyed() }
        surfaceAttached = false
    }

    private fun android.util.DisplayMetrics.refreshRateCompat(): Float = 60f

    private fun redirectJvmOutput() {
        try {
            val log = File(AppPaths.logs, "jvm.log")
            log.parentFile?.mkdirs()
            val flags = OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_APPEND
            val fd = Os.open(log.absolutePath, flags, 420)
            Os.dup2(fd, 1)
            Os.dup2(fd, 2)
            Os.close(fd)
            val stdout = PrintStream(FileOutputStream(log, true), true, Charsets.UTF_8.name())
            val stderr = PrintStream(FileOutputStream(log, true), true, Charsets.UTF_8.name())
            System.setOut(stdout)
            System.setErr(stderr)
            stdout.println("CW STDIO capture active for launch ${launchId()}")
        } catch (e: Exception) {
            CwLog.warn("Журнал Java не открылся: ${e.message}")
        }
    }

    private fun sized(width: Int, height: Int): Array<String> {
        val out = args.toMutableList()
        fun prop(prefix: String, value: String) {
            val line = prefix + value
            val index = out.indexOfFirst { it.startsWith(prefix) }
            if (index >= 0) out[index] = line else out.add(line)
        }
        fun flag(name: String, value: String) {
            val index = out.indexOf(name)
            if (index >= 0 && index + 1 < out.size) out[index + 1] = value
            else {
                out.add(name)
                out.add(value)
            }
        }
        flag("--width", width.toString())
        flag("--height", height.toString())
        out.removeAll { it == "--fullscreen" }
        return out.toTypedArray()
    }

    private fun patchOptions(width: Int, height: Int) {
        val options = File(gameDir, "options.txt")
        val values = linkedMapOf<String, String>()
        if (options.isFile) {
            options.readLines().forEach { line ->
                val cut = line.indexOf(':')
                if (cut > 0) values[line.substring(0, cut)] = line.substring(cut + 1)
            }
        }
        values["fullscreen"] = "false"
        values["overrideWidth"] = width.toString()
        values["overrideHeight"] = height.toString()
        options.writeText(values.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
    }

    private var eglPath: String? = null
    private var glesPath: String? = null

    private fun prepareGraphics() {
        eglPath = chooseGraphics("LIBGL_EGL", "libEGL.so")
        glesPath = chooseGraphics("LIBGL_GLES", "libGLESv3.so") ?: chooseGraphics("LIBGL_GLES", "libGLESv2.so")
        if (eglPath == null || glesPath == null) {
            set("LIBGL_NOTEST", "1")
            CwLog.warn("Системная графика не найдена, проверка видеокарты пропущена")
        }
    }

    private fun chooseGraphics(env: String, name: String): String? {
        val path = graphicsCandidates(name).firstOrNull()
        if (path == null) {
            CwLog.warn("Нет файла $name")
            return null
        }
        CwLog.info("Графика $env=$path")
        set(env, path)
        return path
    }

    private fun graphicsCandidates(name: String): List<String> {
        val paths = ArrayList<String>()
        if (translatedPc()) {
            paths.add("/system/lib64/arm64/$name")
            paths.add("/system/lib64/houdini/arm64/$name")
        } else {
            paths.add("/system/lib64/$name")
            paths.add("/system/lib/$name")
            paths.add(name)
        }
        return paths.distinct().filter { path -> path == name || File(path).isFile }
    }

    private fun hangingLinker(): Boolean {
        val brand = android.os.Build.BRAND.orEmpty()
        val maker = android.os.Build.MANUFACTURER.orEmpty()
        val display = android.os.Build.DISPLAY.orEmpty()
        return brand.equals("HUAWEI", true) || brand.equals("HONOR", true)
            || maker.equals("HUAWEI", true) || maker.equals("HONOR", true)
            || display.contains("EMUI", true) || display.contains("Harmony", true)
    }

    private fun translatedPc(): Boolean {
        return File("/system/lib64/libhoudini.so").isFile || File("/system/lib/libhoudini.so").isFile
    }

    private fun stopReexec(jli: File) {
        // CreateExecutionEnvironment at file offset 0x720c. The stock instruction
        // jumps past execve only when LD_LIBRARY_PATH is empty. Always jump there.
        val stock = byteArrayOf(0x60, 0x1e, 0x00, 0xb4.toByte())
        val stay = byteArrayOf(0xf3.toByte(), 0x00, 0x00, 0x14)
        try {
            java.io.RandomAccessFile(jli, "rw").use { file ->
                if (file.length() < 0x7210) {
                    CwLog.warn("libjli.so короче ожидаемого")
                    return
                }
                file.seek(0x720c)
                val now = ByteArray(4)
                file.readFully(now)
                if (now.contentEquals(stay)) {
                    CwLog.info("Повторный запуск Java уже отключён")
                    return
                }
                if (!now.contentEquals(stock)) {
                    CwLog.warn("Место проверки Java не совпало")
                    return
                }
                file.seek(0x720c)
                file.write(stay)
            }
            CwLog.info("Повторный запуск Java отключён")
        } catch (e: Exception) {
            CwLog.warn("Не удалось отключить повторный запуск Java: ${e.message}")
        }
    }

    private fun restoreBootLookup(jli: File) {
        // FindBootStrapClass at 0x8334. A previous build replaced dlsym with a jump
        // into the Android note that called dlopen(libjvm). Restore the original call.
        if (!jli.isFile) return
        val site = 0x8334
        val noteAt = 0xbac4
        val stock = hex("010000f021582591e0031faa74e8ff97")
        val buried = hex("e40d00141f2003d51f2003d51f2003d5")
        val branch = hex("eb0d00141f2003d51f2003d51f2003d5")
        val note = hex(
            "080000008400000001000000416e64726f6964001500000072323100" +
                "00000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000000000000000000000000000000000000" +
                "00000000000000003631313336363900000000000000000000000000" +
                "00000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000000000000"
        )
        val now = try {
            java.io.RandomAccessFile(jli, "r").use { file ->
                if (file.length() < noteAt + note.size) {
                    CwLog.warn("libjli.so короче ожидаемого")
                    return
                }
                file.seek(site.toLong())
                ByteArray(stock.size).also { file.readFully(it) }
            }
        } catch (e: Exception) {
            CwLog.warn("Не удалось прочитать libjli.so: ${e.message}")
            return
        }
        if (now.contentEquals(stock)) return
        if (!now.contentEquals(branch) && !now.contentEquals(buried)) {
            CwLog.warn("Место поиска класса Java не совпало")
            return
        }
        try {
            java.io.RandomAccessFile(jli, "rw").use { file ->
                file.seek(noteAt.toLong())
                file.write(note)
                file.seek(site.toLong())
                file.write(stock)
            }
            CwLog.info("Поиск класса Java возвращён")
        } catch (e: Exception) {
            CwLog.warn("Не удалось вернуть поиск класса Java: ${e.message}")
        }
    }

    private fun hex(text: String): ByteArray {
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun loadedJreHome(opened: File): String {
        val candidates = ArrayList<String>()
        try {
            candidates.add(opened.canonicalPath)
        } catch (_: Exception) {
        }
        candidates.add(opened.absolutePath)
        for (path in candidates) {
            val cut = path.lastIndexOf("/lib/libjli.so")
            if (cut > 0) return path.substring(0, cut)
        }
        return opened.parentFile?.parentFile?.absolutePath ?: opened.absolutePath
    }

    private fun javaLibraryPath(jre: String, nativeDir: String): String {
        val root = jre.trimEnd('/')
        // Keep the runtime lookup order identical to MojoLauncher's JavaRunner:
        // JRE libraries first, then APK natives, HotSpot and the optional JLI dir.
        val runtime = listOf("$root/lib", nativeDir, "$root/lib/server", "$root/lib/jli")
        val system = if (translatedPc()) {
            listOf("/system/lib64/arm64", "/system/lib64/houdini/arm64", "/system/lib64")
        } else {
            listOf("/system/lib64")
        }
        return runtime
            .plus(system)
            .plus(listOf("/vendor/lib64", "/vendor/lib64/hw"))
            .joinToString(":")
    }

    private fun set(key: String, value: String) {
        try {
            Os.setenv(key, value, true)
        } catch (e: ErrnoException) {
            CwLog.warn("Среда $key не записалась: ${e.message}")
        }
    }

    private fun loadSaved() {
        val text = file("args.json").readText()
        val saved = JSONArray(text)
        args = (0 until saved.length()).map { saved.getString(it) }
        gameDir = File(file("gamedir").readText().trim())
        forceVsync = file("vsync").readText().trim() == "1"
        if (args.isEmpty()) throw IOException("Команда запуска игры пустая")
    }

    private fun file(name: String): File {
        val dir = File(AppPaths.root, "phone")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, name)
    }

    private fun write(name: String, value: String) {
        val target = file(name)
        val part = File(target.parentFile, "$name.part")
        part.writeText(value)
        if (!part.renameTo(target)) {
            target.writeText(value)
            part.delete()
        }
    }

    private fun read(name: String): String {
        val target = file(name)
        return if (target.isFile) target.readText().trim() else ""
    }

    private fun redactedCommand(arguments: List<String>): String {
        val secretOptions = setOf(
            "--accessToken", "--clientToken", "--xuid", "--userProperties",
            "--proxyPass", "--password", "--token"
        )
        val secretProperties = listOf("token", "password", "secret", "credential", "authorization")
        val out = ArrayList<String>(arguments.size)
        var hideNext = false
        arguments.forEach { argument ->
            val safe = when {
                hideNext -> "<скрыто>"
                secretOptions.any { argument.equals(it, true) } -> argument
                argument.startsWith("--") && argument.contains('=') &&
                    secretProperties.any { argument.substringBefore('=').contains(it, true) } ->
                    argument.substringBefore('=') + "=<скрыто>"
                argument.startsWith("-D") && argument.contains('=') &&
                    secretProperties.any { argument.substringBefore('=').contains(it, true) } ->
                    argument.substringBefore('=') + "=<скрыто>"
                else -> argument
            }
            out.add(quoteForLog(safe))
            hideNext = secretOptions.any { argument.equals(it, true) }
        }
        return out.joinToString(" ")
    }

    private fun quoteForLog(value: String): String {
        if (value.none { it.isWhitespace() || it == '"' }) return value
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun formatCode(code: Int): String = if (code == UNKNOWN_EXIT_CODE) "неизвестен" else code.toString()
}
