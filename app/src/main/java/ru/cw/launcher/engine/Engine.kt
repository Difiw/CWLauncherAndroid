package ru.cw.launcher.engine

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.cw.launcher.ui.Pal
import ru.cw.launcher.work.WorkService
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase {
    NOT_INSTALLED, CHECKING, DOWNLOADING, INSTALLING, UPDATING,
    UPDATE_AVAILABLE, READY, STARTING, RUNNING, ERROR, OFFLINE
}

enum class Screen { HOME, SETTINGS, ACCOUNT, VERSIONS, NEWS, UPDATES, FILES, MODS, SKIN, LOG }

data class DialogModel(
    val text: String,
    val confirm: String,
    val dismiss: String?,
    val action: String,
    val alt: String? = null,
    val altAction: String? = null
)

data class UiState(
    val booting: Boolean = true,
    val bootText: String = "Загрузка данных о лаунчере",
    val screen: Screen = Screen.HOME,
    val phase: Phase = Phase.CHECKING,
    val detail: String = "",
    val progress: Float = 0f,
    val progressText: String = "",
    val busy: Boolean = false,
    val accountName: String = "",
    val versionLabel: String = "Common World · 1.20.1 · Fabric",
    val buildLabel: String = "Моды не установлены",
    val server: ServerInfo? = null,
    val news: NewsItem? = null,
    /** loading | ok | empty | error: the real state of the news source, never inferred from text. */
    val newsStatus: String = "loading",
    val notes: List<UpdateNote> = emptyList(),
    val releases: List<String> = emptyList(),
    val dialog: DialogModel? = null,
    val info: String? = null,
    val files: List<String> = emptyList(),
    val launcherRemote: String? = null,
    /** The "update the launcher" window is open. */
    val updatePrompt: Boolean = false,
    val javaFound: Boolean = false,
    val bootProgress: Float = 0.08f,
    val fabricLabel: String = "Fabric",
    val modsCount: Int = 0,
    val buildVersion: String = "",
    val mcReady: Boolean = false,
    val fabricOk: Boolean = false,
    val modsOk: Boolean = false,
    val accountTick: Int = 0,
    val installedVersions: Set<String> = emptySet(),
    val fabricOn: Set<String> = emptySet(),
    val logText: String = "",
    val browserPath: String = "",
    val lookTick: Int = 0,
    val xferTitle: String = "",
    val xferDone: Long = 0,
    val xferTotal: Long = 0,
    val xferSpeed: Long = 0,
    val xferExtra: String = ""
)

class Engine(private val app: Context) {
    val cfg: CwSettings
    private val net = Net()
    val accounts: Accounts
    val ely: ElyLogin
    private val cancel = AtomicBoolean(false)
    @Volatile private var deletePartialOnCancel = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installer: Installer
    private val game: GameLaunch
    @Volatile private var lastVersionFetch = 0L
    private var booting = true
    private var network = true
    private var filesNeedUpdate = false
    private var pendingGameUpdate = false
    private var launchAfterMods = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        AppPaths.init(app)
        cfg = CwSettings.load()
        Pal.set(cfg.uiPalette, cfg.uiSurface, cfg.uiCustomHue, cfg.uiDynHues)
        Pal.look(cfg.uiCorner, cfg.uiBorder)
        CwLog.configure(cfg.verboseLogging, cfg.developerMode)
        Lang.use(cfg.resolvedLanguage())
        accounts = Accounts(cfg)
        ely = ElyLogin(net, cfg, accounts)
        installer = Installer(net, cfg, cancel) { p ->
            if (booting) {
                _state.update { it.copy(bootText = p.title) }
            } else {
                _state.update {
                    it.copy(
                        progressText = p.title,
                        progress = p.fraction,
                        detail = p.title,
                        xferTitle = if (p.totalBytes > 0) p.title else "",
                        xferDone = p.doneBytes,
                        xferTotal = p.totalBytes,
                        xferSpeed = p.speedBps,
                        xferExtra = p.extra
                    )
                }
            }
        }
        game = GameLaunch(app, cfg, installer)
        CwLog.info("=== CWLauncher v${CwLog.VERSION} запущен ===")
    }

    fun start() {
        scope.launch {
            boot()
            while (true) {
                delay(30_000)
                // No background pings while the game runs or a download is in progress.
                if (!_state.value.busy && !game.running()) checkServer()
            }
        }
        scope.launch {
            delay((cfg.checkIntervalSec.coerceAtLeast(30) * 1000L))
            while (true) {
                if (!_state.value.busy && !game.running()) {
                    refreshRemote(false)
                }
                delay((cfg.checkIntervalSec.coerceAtLeast(30) * 1000L))
            }
        }
    }

    fun deliverOauth(uri: String) {
        ely.deliver(uri)
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val actionLock = Mutex()
    private val _working = MutableStateFlow(0)

    /** Number of user actions currently running; the UI disables submit buttons while > 0. */
    val working: StateFlow<Int> = _working

    /**
     * Runs a user action off the UI thread. One action key runs once at a time (a second tap
     * while it runs is ignored) and all actions are serialized, so account and settings
     * files are never written concurrently. Failures are reported, never swallowed.
     */
    private fun act(key: String, block: () -> Unit) {
        if (!inFlight.add(key)) return
        _working.update { it + 1 }
        scope.launch {
            try {
                actionLock.withLock { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                CwLog.warn("Действие $key: ${e.message}")
                _state.update { it.copy(info = e.message ?: "Операция не выполнена") }
            } finally {
                inFlight.remove(key)
                _working.update { it - 1 }
            }
        }
    }

    fun open(screen: Screen) {
        if (screen == Screen.FILES || screen == Screen.MODS) scope.launch { reloadFiles(screen) }
        if (screen == Screen.LOG) scope.launch { reloadLog() }
        if (screen == Screen.VERSIONS) {
            scope.launch {
                val list = if (_state.value.releases.isEmpty()) {
                    try {
                        installer.releases()
                    } catch (e: Exception) {
                        CwLog.warn("Список версий: ${e.message}")
                        emptyList()
                    }
                } else {
                    _state.value.releases
                }
                val installed = (list + cfg.minecraftVersion + "1.20.1").distinct()
                    .filter { installer.present(it) }
                    .toSet()
                val fabric = cfg.fabricVersions.filterValues { it }.keys
                _state.update { it.copy(releases = list, installedVersions = installed, fabricOn = fabric) }
            }
        }
        _state.update { it.copy(screen = screen) }
    }

    fun altDialog() {
        val action = _state.value.dialog?.altAction
        _state.update { it.copy(dialog = null) }
        if (action == "cancel-keep") {
            deletePartialOnCancel = false
            cancel.set(true)
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    fun dismissInfo() = _state.update { it.copy(info = null) }

    fun confirmDialog() {
        val action = _state.value.dialog?.action
        _state.update { it.copy(dialog = null) }
        when (action) {
            "cancel" -> cancel.set(true)
            "cancel-delete" -> {
                deletePartialOnCancel = true
                cancel.set(true)
            }
            "cancel-keep" -> {
                deletePartialOnCancel = false
                cancel.set(true)
            }
            "install" -> install(false)
            "repair" -> install(false)
            "account" -> open(Screen.ACCOUNT)
            "optimize-on" -> {
                cfg.optimizationMode = Device.recommendedMode(Device.profile(app))
                cfg.save()
                CwLog.info("Оптимизация включена игроком: профиль ${cfg.optimizationMode}")
            }
        }
    }

    fun onMain() {
        val phase = _state.value.phase
        if (_state.value.busy || phase == Phase.CHECKING || phase == Phase.INSTALLING || phase == Phase.DOWNLOADING || phase == Phase.UPDATING) {
            _state.update {
                it.copy(dialog = DialogModel(
                    "Остановить загрузку? Скачанное можно оставить и продолжить позже или удалить файлы.",
                    "Удалить файлы",
                    "Продолжить",
                    "cancel-delete",
                    "Остановить",
                    "cancel-keep"
                ))
            }
            return
        }
        when (phase) {
            Phase.READY, Phase.OFFLINE -> {
                if (!requireAccount()) return
                launchGame()
            }
            Phase.NOT_INSTALLED -> {
                if (!requireAccount()) return
                install(false)
            }
            Phase.UPDATE_AVAILABLE -> {
                if (!requireAccount()) return
                if (pendingGameUpdate) install(true) else {
                    launchAfterMods = true
                    updateMods()
                }
            }
            Phase.ERROR -> {
                if (!requireAccount()) return
                val text = _state.value.detail
                if (text.startsWith("Не удалось") || text.startsWith("Игра закрылась")) install(true)
                else verifyThenRepair()
            }
            Phase.RUNNING, Phase.STARTING -> resumeGame()
            else -> Unit
        }
    }

    /** Brings the running game screen back to the front; the game itself is not restarted. */
    fun resumeGame() {
        if (!game.running()) return
        try {
            val intent = Intent(app, ru.cw.launcher.ui.GameActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            app.startActivity(intent)
        } catch (e: Exception) {
            CwLog.warn("Возврат в игру: ${e.message}")
        }
    }

    /** [done] runs on the main thread with an error text, or null on success. */
    fun addOffline(nick: String, done: (String?) -> Unit = {}) {
        act("account-add") {
            val error = try {
                accounts.addOffline(nick)
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "Ник не подошёл"
            }
            if (error == null) {
                publishAccount()
                evaluate()
            }
            postMain { done(error) }
        }
    }

    fun removeAccount(id: String, done: () -> Unit = {}) {
        act("account-remove-$id") {
            accounts.remove(id)
            publishAccount()
            postMain { done() }
        }
    }

    fun selectAccount(id: String) {
        act("account-select") {
            accounts.select(id)
            publishAccount()
        }
    }

    /** [done] runs on the main thread with an error text, or null on success. */
    fun renameOffline(id: String, nick: String, done: (String?) -> Unit) {
        act("account-rename-$id") {
            val error = accounts.renameOffline(id, nick)
            if (error == null) publishAccount()
            postMain { done(error) }
        }
    }

    private fun postMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    fun beginEly(openUrl: (String) -> Unit) {
        scope.launch {
            val url = ely.login { text -> _state.update { it.copy(detail = text) } }
            if (!url.startsWith("http")) {
                _state.update { it.copy(info = url) }
                return@launch
            }
            withContext(Dispatchers.Main) { openUrl(url) }
            val error = ely.finish { text -> _state.update { it.copy(detail = text) } }
            if (error != null) _state.update { it.copy(info = error) }
            else {
                publishAccount()
                evaluate()
                _state.update { it.copy(info = "Вход через Ely.by выполнен") }
            }
        }
    }

    fun selectCommonWorld() {
        act("select-common") {
            cfg.commonWorld = true
            cfg.minecraftVersion = "1.20.1"
            cfg.loader = "fabric"
            cfg.save()
            CwLog.configure(cfg.verboseLogging, cfg.developerMode)
            evaluate()
            open(Screen.HOME)
            scope.launch { refreshRemote(true) }
        }
    }

    fun selectRelease(id: String, fabric: Boolean) {
        act("select-release") {
            cfg.commonWorld = false
            cfg.minecraftVersion = id
            cfg.loader = if (fabric) "fabric" else "vanilla"
            cfg.fabricVersions[id] = fabric
            cfg.save()
            evaluate()
            open(Screen.HOME)
        }
    }

    fun toggleFabric(id: String) {
        act("toggle-fabric-$id") {
            val shown = if (cfg.fabricVersions.containsKey(id)) cfg.fabricFor(id) else id == "1.20.1" && cfg.commonWorld
            val on = !shown
            cfg.fabricVersions[id] = on
            if (!cfg.commonWorld && cfg.minecraftVersion.equals(id, true)) {
                cfg.loader = if (on) "fabric" else "vanilla"
            }
            cfg.save()
            _state.update { state ->
                val next = state.fabricOn.toMutableSet()
                if (on) next.add(id) else next.remove(id)
                state.copy(fabricOn = next)
            }
            evaluate()
        }
    }

    fun saveSettings(block: CwSettings.() -> Unit) {
        act("save-settings") {
            cfg.block()
            if (cfg.commonWorld) {
                cfg.minecraftVersion = "1.20.1"
                cfg.loader = "fabric"
            }
            cfg.checkIntervalSec = cfg.checkIntervalSec.coerceAtLeast(30)
            cfg.save()
            CwLog.configure(cfg.verboseLogging, cfg.developerMode)
            Lang.use(cfg.resolvedLanguage())
            // The settings are on disk and applied: tell the user at once. Rewriting options.txt
            // and re-checking the build (opens every mod file) follow without blocking the UI.
            _state.update { it.copy(info = Lang.t("saved"), lookTick = it.lookTick + 1) }
            game.applyOptions()
            evaluate()
            _state.update { it.copy(javaFound = game.javaBinary() != null) }
        }
    }

    // ---------------------------------------------------------------- developer mode builds

    fun devBuilds(): List<String> = Instances.list(cfg.minecraftVersion)

    /** [done] runs on the main thread with an error text, or null on success. */
    fun createBuild(name: String, copyMain: Boolean, done: (String?) -> Unit) {
        act("dev-create") {
            val error = try {
                val created = Instances.create(cfg.minecraftVersion, name, copyMain)
                game.applyOptions(Instances.dir(cfg.minecraftVersion, created))
                cfg.devInstance = created
                cfg.save()
                null
            } catch (e: Exception) {
                e.message ?: "Не удалось создать сборку"
            }
            postMain { done(error); noteLook() }
        }
    }

    fun deleteBuild(name: String, done: () -> Unit) {
        act("dev-delete") {
            Instances.delete(cfg.minecraftVersion, name)
            if (cfg.devInstance == name) {
                cfg.devInstance = ""
                cfg.save()
            }
            postMain { done(); noteLook() }
        }
    }

    // ---------------------------------------------------------------- styles made from pictures

    fun styleList(): List<SavedStyle> = Styles.list()

    /** The picture that is shown as the launcher background right now. */
    private fun currentBackground(): android.graphics.Bitmap? {
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        val file = ru.cw.launcher.ui.Backgrounds.file(cfg)
        return if (file != null) android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        else android.graphics.BitmapFactory.decodeResource(app.resources, ru.cw.launcher.ui.Backgrounds.item(cfg).res, options)
    }

    /**
     * Builds a new style from the current background: solid (one colour everywhere) or dynamic
     * (each third of the picture gives its own colour). [contrast] picks the opposite colours.
     * The style is saved for good and applied at once.
     */
    fun makeStyle(dynamic: Boolean, contrast: Boolean, done: (String) -> Unit) {
        act("style-make") {
            val bitmap = currentBackground()
            if (bitmap == null) {
                postMain { done("Не удалось прочитать картинку фона.") }
                return@act
            }
            val found = Styles.analyze(bitmap, dynamic, contrast)
            bitmap.recycle()
            val id = "s" + System.currentTimeMillis()
            val kept = ru.cw.launcher.ui.Backgrounds.file(cfg)?.let { source ->
                File(Styles.pictures, "$id.${source.extension.ifBlank { "jpg" }}").also { source.copyTo(it, overwrite = true) }.absolutePath
            } ?: (ru.cw.launcher.ui.Backgrounds.BUILTIN + ru.cw.launcher.ui.Backgrounds.item(cfg).id)
            val number = Styles.list().count { it.dynamic == dynamic } + 1
            val name = (if (dynamic) "Динамичный" else "Однотонный") + " $number" + if (contrast) " · контраст" else ""
            val style = SavedStyle(id, name, dynamic, found.hues, kept, found.grid)
            Styles.add(style)
            applyStyleNow(style)
            postMain { done("Создан и применён стиль «$name». Он сохранён в «Мои стили».") }
        }
    }

    private fun applyStyleNow(style: SavedStyle) {
        cfg.uiPalette = "custom"
        cfg.uiCustomHue = style.hues[1]
        cfg.uiDynHues = if (style.dynamic && style.grid.isNotEmpty()) style.grid.joinToString(",") else ""
        cfg.backgroundPath = style.background
        cfg.bgAuto = false
        cfg.save()
        postMain {
            Pal.set("custom", cfg.uiSurface, cfg.uiCustomHue, cfg.uiDynHues)
            noteLook()
        }
    }

    fun applyStyle(id: String) {
        act("style-apply") {
            Styles.list().firstOrNull { it.id == id }?.let { applyStyleNow(it) }
        }
    }

    fun deleteStyle(id: String, done: () -> Unit) {
        act("style-delete") {
            Styles.remove(id)
            postMain { done(); noteLook() }
        }
    }

    /** Mod files of the selected own build; empty for the main folder. */
    fun buildMods(): List<File> {
        if (cfg.devInstance.isBlank()) return emptyList()
        val dir = File(Instances.dir(cfg.minecraftVersion, cfg.devInstance), "mods")
        return dir.listFiles()?.filter { it.isFile && it.extension.equals("jar", true) }?.sortedBy { it.name.lowercase() }.orEmpty()
    }

    fun deleteBuildMod(file: File, done: () -> Unit) {
        act("dev-mod-delete") {
            file.delete()
            postMain { done(); noteLook() }
        }
    }

    /**
     * Copies chosen .jar files into the mods of the selected own build. A file replaces an older
     * file of the same mod (same name before the version number), so a newer version can be tried.
     */
    fun importBuildMods(uris: List<Uri>, done: (String) -> Unit) {
        act("dev-mods-import") {
            if (cfg.devInstance.isBlank()) {
                postMain { done("Выберите свою сборку: в основную моды не загружаются.") }
                return@act
            }
            val mods = File(Instances.dir(cfg.minecraftVersion, cfg.devInstance), "mods").apply { mkdirs() }
            var added = 0
            val notes = mutableListOf<String>()
            for (uri in uris) {
                var name = "mod.jar"
                try {
                    app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    if (!safe.endsWith(".jar", true)) {
                        notes.add("$safe: нужен файл .jar")
                        continue
                    }
                    val part = File(mods, "$safe.part")
                    app.contentResolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it) } }
                        ?: throw java.io.IOException("файл не открылся")
                    if (!looksLikeZip(part)) {
                        part.delete()
                        notes.add("$safe: это не jar-файл")
                        continue
                    }
                    val family = modFamily(safe)
                    mods.listFiles()?.filter { it.isFile && it.extension.equals("jar", true) && modFamily(it.name) == family }?.forEach {
                        it.delete()
                        notes.add("заменён ${it.name}")
                    }
                    val dest = File(mods, safe)
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    added++
                } catch (e: Exception) {
                    notes.add("$name: ${e.message ?: "ошибка"}")
                }
            }
            val text = buildString {
                append(if (added > 0) "Загружено модов: $added." else "Ничего не загружено.")
                if (notes.isNotEmpty()) append("\n").append(notes.joinToString("\n"))
            }
            postMain { done(text); noteLook() }
        }
    }

    /** "admincontrol-1.20.1-2.0.jar" and "admincontrol-1.20.1-2.1.jar" are one mod: "admincontrol". */
    private fun modFamily(fileName: String): String {
        val base = fileName.removeSuffix(".jar").removeSuffix(".JAR")
        return Regex("^(.*?)[-_+ ]v?\\d").find(base)?.groupValues?.get(1)?.lowercase() ?: base.lowercase()
    }

    /** Empty [name] selects the main folder. The choice is stored at once. */
    fun selectBuild(name: String) {
        cfg.devInstance = name
        cfg.save()
        noteLook()
    }

    fun setTempNick(nick: String) {
        cfg.devNick = nick.trim()
        cfg.save()
        noteLook()
    }

    fun noteLook() {
        _state.update { it.copy(lookTick = it.lookTick + 1) }
    }

    fun tell(text: String) {
        _state.update { it.copy(info = text) }
    }

    fun clearCache() {
        act("clear-cache") {
            clearDir(AppPaths.cache)
            clearDir(AppPaths.tmp)
            _state.update { it.copy(info = "Кэш очищен. Миры, аккаунты и настройки не трогались.") }
        }
    }

    fun resetGame() {
        if (_state.value.busy) return
        act("reset-game") { resetGameNow() }
    }

    private fun resetGameNow() {
        game.stop()
        deleteTree(AppPaths.gamesRoot)
        AppPaths.gamesRoot.mkdirs()
        cfg.commonWorld = true
        cfg.minecraftVersion = "1.20.1"
        cfg.loader = "fabric"
        cfg.fabricLoaderVersion = ""
        cfg.save()
        evaluate()
        _state.update { it.copy(info = "Скачанные версии удалены") }
    }

    fun reinstallLauncher() {
        if (_state.value.busy) return
        act("reinstall") { reinstallNow() }
    }

    private fun reinstallNow() {
        game.stop()
        AppPaths.root.listFiles()?.forEach { child ->
            if (child.name != "jre") deleteTree(child)
        }
        AppPaths.init(app)
        CwSettings().save()
        cfg.takeFrom(CwSettings.load())
        accounts.wipe()
        Lang.use(cfg.resolvedLanguage())
        evaluate()
        publishAccount()
        _state.update {
            it.copy(
                screen = Screen.HOME,
                info = "Переустановка выполнена. Фоны, аккаунты, миры, моды и скачанные версии удалены.",
                lookTick = it.lookTick + 1,
                accountTick = it.accountTick + 1
            )
        }
    }

    fun uninstallLauncher() {
        if (_state.value.busy) return
        act("uninstall") { uninstallNow() }
    }

    private fun uninstallNow() {
        game.stop()
        try {
            AppPaths.root.listFiles()?.forEach { deleteTree(it) }
            app.cacheDir.listFiles()?.forEach { deleteTree(it) }
            app.getExternalFilesDir(null)?.listFiles()?.forEach { deleteTree(it) }
        } catch (e: Exception) {
            CwLog.warn("Очистка перед удалением: ${e.message}")
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun downloadLauncher() {
        val url = cfg.launcherDownloadUrl
        if (url.isBlank()) {
            _state.update { it.copy(info = "Адрес пакета лаунчера не задан") }
            return
        }
        if (!begin("Обновление лаунчера")) return
        scope.launch {
            try {
                setPhase(Phase.DOWNLOADING, "Обновление CWLauncher")
                val dest = File(AppPaths.updates, "CWLauncher.zip")
                val part = File(dest.parentFile, dest.name + ".part")
                if (part.isFile && part.length() > 0) {
                    CwLog.info("Продолжаю обновление лаунчера с ${formatBytes(part.length())}")
                }
                val meter = SpeedMeter()
                val tick = java.util.concurrent.atomic.AtomicLong(0)
                val report = { done: Long, total: Long ->
                    val now = System.currentTimeMillis()
                    val prev = tick.get()
                    if (now - prev >= 200 && tick.compareAndSet(prev, now)) {
                        val speed = meter.note(done)
                        val fraction = if (total > 0) done.toFloat() / total else 0f
                        _state.update {
                            it.copy(
                                progress = fraction,
                                progressText = "Обновление CWLauncher",
                                detail = "Обновление CWLauncher",
                                xferTitle = "Обновление CWLauncher",
                                xferDone = done,
                                xferTotal = total,
                                xferSpeed = speed
                            )
                        }
                    }
                }
                if (url.contains("drive.google.com")) {
                    Drive.download(net, url, dest, false, cancel, report)
                } else {
                    net.download(url, dest, null, 0, cancel, report)
                }
                endBusy()
                evaluate()
                _state.update {
                    it.copy(info = "Пакет сохранён: ${dest.absolutePath}. На сервере это архив Windows-лаунчера. Эта сборка Android — ${CwLog.VERSION}.")
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun stopGame() {
        scope.launch {
            game.stop()
            evaluate()
        }
    }

    /** Reads, checks and stores the chosen skin PNG; [done] runs on the main thread with a result text. */
    fun importSkinFrom(uri: Uri, done: (String) -> Unit) {
        act("skin-import") {
            val account = accounts.active()
            val message = when {
                account == null -> "Сначала создайте аккаунт."
                else -> {
                    val bytes = try {
                        app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                    val problem = if (bytes == null) "Не удалось прочитать выбранный файл." else Skins.validate(bytes)
                    if (problem != null) {
                        problem
                    } else {
                        var displayName = "Скин"
                        try {
                            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: displayName
                            }
                        } catch (_: Exception) {
                        }
                        Skins.add(account.id, bytes!!, displayName)
                        Skins.clearLegacyDelivery(account, AppPaths.game(cfg.minecraftVersion))
                        publishAccount()
                        "Скин добавлен и выбран. При запуске он будет передан игре через Skin API, как в ПК-лаунчере."
                    }
                }
            }
            postMain { done(message) }
        }
    }

    fun selectSkin(id: String, done: (String) -> Unit) {
        act("skin-select") {
            val account = accounts.active()
            val selected = account != null && Skins.select(account.id, id)
            if (selected) {
                Skins.clearLegacyDelivery(account!!, AppPaths.game(cfg.minecraftVersion))
                publishAccount()
            }
            postMain { done(if (selected) "Скин выбран. Он будет использован при следующем запуске." else "Скин не найден.") }
        }
    }

    fun removeSkin(id: String, done: (String) -> Unit) {
        act("skin-remove") {
            val account = accounts.active()
            if (account != null) {
                Skins.remove(account.id, id)
                Skins.clearLegacyDelivery(account, AppPaths.game(cfg.minecraftVersion))
                publishAccount()
            }
            postMain { done("Скин удалён. Активным выбран следующий скин профиля или стандартный.") }
        }
    }

    fun skinItems(): List<Skins.Item> {
        val account = accounts.active() ?: return emptyList()
        return Skins.list(account.id)
    }

    fun skinPreview(): File? {
        val account = accounts.active() ?: return null
        return Skins.skinFile(account.id).takeIf { it.isFile }
    }

    fun shaderDir(): File = File(AppPaths.game(cfg.minecraftVersion), "shaderpacks").apply { mkdirs() }

    fun shaderPacks(): List<File> =
        shaderDir().listFiles()?.filter { it.isFile || it.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()

    /** Copies chosen shader archives into the game shaderpacks folder (read by Iris). */
    fun importShaders(uris: List<Uri>, done: (String) -> Unit) {
        act("shaders-import") {
            var added = 0
            val problems = mutableListOf<String>()
            for (uri in uris) {
                var name = "shader.zip"
                try {
                    app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    val safe = name.replace(Regex("[\\/:*?\"<>|]"), "_")
                    if (!safe.endsWith(".zip", true)) {
                        problems.add("$safe: нужен файл .zip")
                        continue
                    }
                    val dest = File(shaderDir(), safe)
                    val part = File(shaderDir(), "$safe.part")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        part.outputStream().use { input.copyTo(it) }
                    } ?: throw java.io.IOException("файл не открылся")
                    if (!looksLikeZip(part)) {
                        part.delete()
                        problems.add("$safe: это не zip-архив")
                        continue
                    }
                    if (dest.exists()) dest.delete()
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    added++
                } catch (e: Exception) {
                    problems.add("$name: ${e.message ?: "ошибка"}")
                }
            }
            val text = buildString {
                if (added > 0) append("Добавлено шейдеров: $added. В игре: Настройки → Графика → Шейдеры.")
                if (problems.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(problems.joinToString("\n"))
                }
                if (isEmpty()) append("Ничего не добавлено.")
            }
            postMain { done(text) }
        }
    }

    fun deleteShader(name: String, done: () -> Unit) {
        act("shader-delete") {
            val file = File(shaderDir(), name)
            if (file.parentFile == shaderDir()) file.deleteRecursively()
            postMain { done() }
        }
    }

    fun reloadLog() {
        val text = if (AppPaths.mainLog.isFile) AppPaths.mainLog.readText().takeLast(12_000) else ""
        val gameLog = if (AppPaths.gameLog.isFile) AppPaths.gameLog.readText().takeLast(8_000) else ""
        _state.update { it.copy(logText = text + if (gameLog.isBlank()) "" else "\n\n--- latest.log ---\n" + gameLog) }
    }

    fun exportLogs() {
        scope.launch {
            try {
                val archive = withContext(Dispatchers.IO) { createDiagnosticArchive() }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", archive)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "CWLauncher diagnostics")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(Intent.createChooser(share, "Экспорт диагностических логов").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CwLog.info("Диагностические логи подготовлены: ${archive.name}")
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun createDiagnosticArchive(): File {
        val exportDir = File(app.cacheDir, "diagnostic-exports").apply { mkdirs() }
        exportDir.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }?.forEach { it.delete() }
        val archive = File(exportDir, "cwlauncher-diagnostics-${System.currentTimeMillis()}.zip")
        val candidates = linkedSetOf<File>()
        candidates.add(AppPaths.mainLog)
        candidates.addAll(game.outputLogs())
        AppPaths.logs.listFiles()?.filterTo(candidates) { it.isFile }
        val gameDir = AppPaths.game(cfg.minecraftVersion)
        gameDir.resolve("logs").listFiles()?.filterTo(candidates) { it.isFile && it.extension.equals("log", true) }
        gameDir.resolve("crash-reports").listFiles()?.filterTo(candidates) { it.isFile && it.extension.equals("txt", true) }
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            val used = HashSet<String>()
            zip.putNextEntry(ZipEntry("diagnostic-summary.txt"))
            zip.write(diagnosticSummary(candidates).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("adb-logcat-instructions.txt"))
            zip.write(adbInstructions().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            candidates.filter { it.isFile && it.length() > 0 }.forEach { file ->
                var name = when {
                    file.absolutePath.startsWith(AppPaths.root.absolutePath) ->
                        file.relativeTo(AppPaths.root).invariantSeparatorsPath
                    else -> file.name
                }
                if (!used.add(name)) {
                    name = "extra/${file.parentFile?.name.orEmpty()}-${file.name}"
                    if (!used.add(name)) return@forEach
                }
                zip.putNextEntry(ZipEntry(name))
                val text = if (file.extension.lowercase() in setOf("log", "txt", "json")) {
                    runCatching { file.readText() }.getOrNull()
                } else null
                if (text != null) zip.write(redactSecrets(text).toByteArray(Charsets.UTF_8))
                else file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (archive.length() == 0L) throw java.io.IOException("Нет диагностических логов для экспорта")
        return archive
    }

    private fun diagnosticSummary(candidates: Collection<File>): String = buildString {
        appendLine("CWLauncher ${CwLog.VERSION} diagnostic summary")
        appendLine("created=${java.time.OffsetDateTime.now()}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("minecraft=${cfg.minecraftVersion}")
        appendLine("loader=${cfg.loader} ${cfg.fabricLoaderVersion}")
        appendLine("launchId=${PhoneVm.launchId()}")
        appendLine("stage=${PhoneVm.stage()}")
        appendLine("jvmStatus=${PhoneVm.formatCode(PhoneVm.storedCode())}")
        appendLine("lastError=${redactSecrets(PhoneVm.storedError())}")
        appendLine("verboseLogging=${cfg.verboseLogging}")
        appendLine("developerMode=${cfg.developerMode}")
        appendLine("androidExitStatusIsJvmExitCode=false")
        val expected = linkedMapOf(
            "launcher.log" to AppPaths.mainLog,
            "launch-${PhoneVm.launchId()}.log" to PhoneVm.traceFile(),
            "jvm.log" to File(AppPaths.logs, "jvm.log"),
            "gc.log" to File(AppPaths.logs, "gc.log"),
            "latest.log" to File(AppPaths.game(cfg.minecraftVersion), "logs/latest.log"),
            "fabric.log" to File(AppPaths.logs, "fabric.log"),
            "hs_err.log" to File(AppPaths.logs, "hs_err.log"),
            "android-exit.txt" to File(AppPaths.logs, "android-exit.txt"),
            "android-exit-trace.bin" to File(AppPaths.logs, "android-exit-trace.bin")
        )
        appendLine("logs:")
        expected.forEach { (label, file) ->
            val state = when {
                file.isFile && file.length() > 0 -> "FOUND size=${file.length()} path=${file.absolutePath}"
                file.isFile -> "MISSING reason=empty file"
                label == "latest.log" || label == "fabric.log" -> "MISSING reason=game/Fabric logging did not start"
                label == "hs_err.log" -> "MISSING reason=HotSpot did not create an error file"
                label == "android-exit-trace.bin" -> "MISSING reason=Android did not expose a trace stream to this app"
                else -> "MISSING reason=file was not created"
            }
            appendLine("  $label: $state")
        }
        val crashes = candidates.filter { it.isFile && it.parentFile?.name == "crash-reports" }
        appendLine("crashReports=${if (crashes.isEmpty()) "MISSING reason=no Minecraft crash report" else crashes.joinToString { it.name }}")
        appendLine("note=Android logcat is not generally available to the application; see adb-logcat-instructions.txt.")
    }

    private fun adbInstructions(): String = """
        CWLauncher: сбор Android Logcat после сбоя

        1. На телефоне включите «Для разработчиков» и «Отладка по USB».
        2. Подключите телефон к ПК, подтвердите ключ и выполните: adb devices
        3. Очистите старый буфер непосредственно перед проверкой: adb logcat -c
        4. Запустите CWLauncher и воспроизведите сбой Minecraft.
        5. Сразу после возврата в лаунчер сохраните полный буфер:
           adb logcat -b all -d -v threadtime > cwlauncher-logcat.txt
        6. Сохраните сведения о последнем завершении процесса:
           adb shell dumpsys activity exit-info ru.cw.launcher > cwlauncher-exit-info.txt
        7. Если устройство разрешает чтение tombstone:
           adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE > cwlauncher-tombstones.txt

        Не применяйте узкий фильтр при сборе. При анализе ищите ru.cw.launcher,
        ru.cw.launcher:game, FATAL EXCEPTION, AndroidRuntime, DEBUG, libc, SIGSEGV,
        SIGABRT, linker, tombstone, JNI и HotSpot. Перед отправкой проверьте файлы
        на наличие личных данных и токенов.
    """.trimIndent() + "\n"

    private fun redactSecrets(text: String): String {
        var result = text
        val option = Regex("(?i)(--(?:accessToken|clientToken|proxyPass|password|token)\\s+)(\\S+)")
        result = result.replace(option, "$1<скрыто>")
        val assigned = Regex("(?i)((?:token|password|secret|credential|authorization)[^=\\s]*=)([^\\s]+)")
        return result.replace(assigned, "$1<скрыто>")
    }

    private fun boot() {
        try {
            bootText("Распаковка Java 17", 0.08f)
            try {
                BundledJava.ensure(app) { bootText(it, 0.16f) }
            } catch (e: Exception) {
                CwLog.error("Java: ${e.message}")
            }
            bootText("Загрузка данных о лаунчере", 0.32f)
            applyPatch(net, cfg)
            bootText("Проверка установленной игры", 0.55f)
            val ready = installer.versionReady(cfg.minecraftVersion)
            if (ready) filesNeedUpdate = installer.remoteJsonDiffers(cfg.minecraftVersion)
            bootText("Подгрузка версии лаунчера", 0.74f)
            fetchLauncherVersion()
            fetchBuildVersion(true)
            fetchNews()
            fetchNotes()
            checkServer()
            network = true
            bootText("Готово", 1f)
        } catch (e: Exception) {
            network = false
            CwLog.error("Подготовка: ${e.message}")
        } finally {
            booting = false
            publishAccount()
            evaluate()
            _state.update { it.copy(booting = false, javaFound = game.javaBinary() != null, updatePrompt = launcherDiffers()) }
            offerOptimization()
        }
    }

    /**
     * One-time suggestion on a weak phone. It never switches anything on by itself: the preset is
     * applied only when the player confirms, and the offer is not shown again afterwards.
     */
    private fun offerOptimization() {
        try {
            if (cfg.optimizationOffered || cfg.optimizationMode != 0 || _state.value.dialog != null) return
            val profile = Device.profile(app)
            if (profile.tier != Device.Tier.WEAK) return
            cfg.optimizationOffered = true
            cfg.save()
            CwLog.info("Слабое устройство (${profile.summary}, баллы ${profile.score}): предложена оптимизация")
            _state.update {
                it.copy(dialog = DialogModel(
                    "Устройство небольшой мощности (${profile.summary}).\n" +
                        "Включить оптимизацию Minecraft? Она снизит дальность прорисовки и нагрузку, FPS вырастет. " +
                        "Позже её можно изменить в настройках.",
                    "Включить", "Не сейчас", "optimize-on"
                ))
            }
        } catch (e: Exception) {
            CwLog.warn("Определение устройства: ${e.message}")
        }
    }

    private fun bootText(text: String, progress: Float = _state.value.bootProgress) {
        _state.update { it.copy(bootText = text, bootProgress = progress.coerceIn(0f, 1f)) }
    }

    fun reloadNews() {
        scope.launch { fetchNews() }
    }

    fun reloadNotes() {
        scope.launch { fetchNotes() }
    }

    private fun refreshRemote(readZip: Boolean) {
        try {
            fetchLauncherVersion()
            fetchBuildVersion(readZip)
            if (cfg.newsUrl.isNotBlank()) fetchNews()
        } catch (e: Exception) {
            CwLog.warn("Плановая проверка: ${e.message}")
        }
        evaluate()
    }

    private fun launcherDiffers(): Boolean {
        val remote = cfg.remoteLauncherVersion ?: return false
        return compareVersions(remote, CwLog.VERSION) != 0
    }

    /** Shows the update window again each time the launcher comes back to the front. */
    fun onForeground() {
        scope.launch {
            if (booting) return@launch
            val now = System.currentTimeMillis()
            // Re-read lVersion.txt at most once a minute; the cached value still re-opens the prompt.
            if (now - lastVersionFetch > 60_000 && !game.running()) {
                lastVersionFetch = now
                fetchLauncherVersion()
            }
            _state.update { it.copy(updatePrompt = launcherDiffers()) }
        }
    }

    fun postponeLauncherUpdate() {
        _state.update { it.copy(updatePrompt = false) }
    }

    /** Reads patchCWL.txt anew; [done] runs on the main thread with the link, or null if there is none. */
    fun openLauncherUpdate(done: (String?) -> Unit) {
        scope.launch {
            val url = fetchUpdateUrl(net)
            postMain { done(url) }
        }
    }

    private fun fetchLauncherVersion() {
        try {
            val text = readRemoteText(net, cfg.lVersionUrl)
            val value = readVersionToken(text)
            cfg.remoteLauncherVersion = value
            if (value != null) CwLog.info("lVersion.txt = $value")
            _state.update { it.copy(launcherRemote = value) }
        } catch (e: Exception) {
            cfg.remoteLauncherVersion = null
            CwLog.warn("lVersion.txt недоступен: ${e.message}")
        }
    }

    private fun fetchBuildVersion(readZip: Boolean) {
        if (!cfg.commonWorldMods()) return
        val local = installer.installedBuild(cfg.minecraftVersion)
        if (local.isNullOrBlank()) {
            cfg.remoteBuildVersion = null
            CwLog.info("Папка модов пустая, сверка версии пропущена")
            return
        }
        val remote = try {
            readVersionToken(readRemoteText(net, cfg.cwVersionUrl))
        } catch (e: Exception) {
            CwLog.warn("cwVersion.txt: ${e.message}")
            null
        }
        cfg.remoteBuildVersion = remote
        if (remote == null) CwLog.info("Версия модов в папке $local, cwVersion.txt недоступен")
        else CwLog.info("Версия модов в папке $local, cwVersion.txt = $remote")
        if (readZip && remote != null && compareVersions(local, remote) < 0) {
            CwLog.info("На диске версия модов новее: $local → $remote")
        }
    }

    private fun fetchNews() {
        if (cfg.newsUrl.isBlank()) {
            _state.update { it.copy(news = null, newsStatus = "empty") }
            return
        }
        val item = loadNews(net, cfg)
        _state.update {
            when {
                item != null -> it.copy(news = item, newsStatus = "ok")
                it.news != null -> it.copy(newsStatus = "ok")
                else -> it.copy(news = null, newsStatus = "error")
            }
        }
    }

    private fun fetchNotes() {
        val notes = loadUpdateNotes(net, cfg)
        _state.update { it.copy(notes = notes) }
    }

    private fun checkServer() {
        val info = ServerPing.check(cfg.serverIp)
        if (info.error == null) network = true
        _state.update { it.copy(server = info) }
    }

    fun evaluate() {
        if (_state.value.busy) return
        val version = cfg.minecraftVersion
        val jar = installer.clientJar(version).isFile
        val mcReady = installer.versionReady(version)
        val useFabric = cfg.loader.equals("fabric", true)
        val installedLoader = installer.installedLoader(version)
        val fabricReady = !useFabric || installer.fabricReady(version, installedLoader)
        val modsInstalled = installer.modsInstalled(version)
        val modsProblems = if (cfg.commonWorldMods() && cfg.verifyBuildOnLaunch) installer.verifyMods(version) else emptyList()
        val modsNeed = cfg.commonWorldMods() && cfg.autoUpdateBuild && cfg.remoteBuildVersion != null &&
            compareVersions(installer.installedBuild(version), cfg.remoteBuildVersion) < 0 &&
            installer.installedBuild(version) != null
        val gameNeeds = jar && (filesNeedUpdate || !mcReady || !fabricReady)
        pendingGameUpdate = gameNeeds
        val phase: Phase
        val detail: String
        if (game.running()) {
            if (game.booted) {
                phase = Phase.RUNNING
                detail = "Minecraft запущен"
            } else {
                phase = Phase.STARTING
                detail = "Minecraft запускается"
            }
        } else if (!jar) {
            phase = Phase.NOT_INSTALLED
            detail = "Minecraft $version не установлен"
        } else if (gameNeeds) {
            phase = Phase.UPDATE_AVAILABLE
            detail = when {
                !mcReady -> "Файлы игры скачаны не полностью"
                !fabricReady -> "Нужно обновить Fabric для Minecraft $version"
                else -> "Файлы Minecraft $version нужно обновить"
            }
        } else if (cfg.commonWorldMods() && !modsInstalled) {
            phase = Phase.NOT_INSTALLED
            detail = "Моды Common World не установлены"
        } else if (modsNeed) {
            phase = Phase.UPDATE_AVAILABLE
            detail = "Доступно обновление модов: ${cfg.remoteBuildVersion}"
        } else if (modsProblems.isNotEmpty()) {
            phase = Phase.ERROR
            detail = modsProblems.first()
        } else if (!network && cfg.offlineAllowed) {
            phase = Phase.OFFLINE
            detail = "Нет соединения — запуск из локальной установки"
        } else {
            phase = Phase.READY
            detail = "Сборка актуальна"
        }
        val build = installer.installedBuild(version)
        val modsCount = AppPaths.mods(version).listFiles()?.count { it.isFile && it.extension.equals("jar", true) } ?: 0
        val fabricLabel = if (installedLoader.isNullOrBlank()) "Fabric" else "Fabric $installedLoader"
        val label = if (cfg.commonWorld) "Common World · 1.20.1 · Fabric" else "$version · ${cfg.loader}"
        val buildLabel = when {
            !cfg.commonWorldMods() -> "Моды сборки не используются"
            build == null -> "Моды не установлены"
            modsNeed -> "Есть обновление $build → ${cfg.remoteBuildVersion}"
            else -> "Сборка: $build"
        }
        _state.update {
            it.copy(
                phase = phase,
                detail = detail,
                versionLabel = label,
                buildLabel = buildLabel,
                accountName = accounts.active()?.username?.ifBlank { "без имени" } ?: "Нет аккаунта",
                javaFound = game.javaBinary() != null,
                launcherRemote = cfg.remoteLauncherVersion,
                fabricLabel = fabricLabel,
                modsCount = modsCount,
                buildVersion = build ?: "",
                mcReady = mcReady,
                fabricOk = fabricReady,
                modsOk = modsInstalled && modsProblems.isEmpty() && !modsNeed
            )
        }
    }

    private fun requireAccount(): Boolean {
        if (accounts.active() != null) return true
        _state.update {
            it.copy(dialog = DialogModel(
                "Сначала создайте профиль: оффлайн-ник или вход Ely.by.",
                "Профиль", "Закрыть", "account"
            ))
        }
        return false
    }

    private fun install(thenLaunch: Boolean) {
        if (!begin("Установка")) return
        scope.launch {
            try {
                val version = cfg.minecraftVersion
                val useFabric = cfg.loader.equals("fabric", true)
                if (filesNeedUpdate || !installer.versionReady(version)) {
                    setPhase(Phase.INSTALLING, "Установка Minecraft $version")
                    installer.installMinecraft(version)
                    filesNeedUpdate = false
                }
                if (useFabric) {
                    val loader = cfg.fabricLoaderVersion.ifBlank {
                        installer.latestLoader(version) ?: installer.installedLoader(version) ?: ""
                    }
                    if (loader.isBlank()) throw java.io.IOException("Не удалось определить версию Fabric. Проверьте интернет.")
                    if (!installer.fabricReady(version, loader)) {
                        setPhase(Phase.INSTALLING, "Проверка Fabric")
                        installer.installFabric(version, loader)
                    }
                }
                val modsNeed = cfg.commonWorldMods() && (
                    !installer.modsInstalled(version) ||
                        (cfg.remoteBuildVersion != null &&
                            compareVersions(installer.installedBuild(version), cfg.remoteBuildVersion) < 0)
                    )
                if (modsNeed) {
                    launchAfterMods = thenLaunch
                    endBusy()
                    updateMods()
                    return@launch
                }
                endBusy()
                evaluate()
                if (thenLaunch) launchGame()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun updateMods() {
        if (_state.value.busy) return
        if (!cfg.commonWorldMods()) {
            launchAfterMods = false
            evaluate()
            return
        }
        if (!begin("Обновление сборки")) return
        scope.launch {
            try {
                setPhase(Phase.UPDATING, "Загрузка сборки Common World")
                if (cfg.remoteBuildVersion == null) fetchBuildVersion(true)
                installer.installMods(cfg.remoteBuildVersion)
                val start = launchAfterMods
                launchAfterMods = false
                endBusy()
                evaluate()
                if (start) launchGame()
            } catch (e: Exception) {
                launchAfterMods = false
                fail(e)
            }
        }
    }

    private fun launchGame() {
        if (!begin("Запуск")) return
        scope.launch {
            try {
                val customJava = cfg.javaPath.trim().let { path -> path.isNotEmpty() && File(path).isFile }
                if (!customJava) {
                    setPhase(Phase.STARTING, "Подготовка Java 17")
                    BundledJava.ensure(app) { text -> setPhase(Phase.STARTING, text) }
                }
                val signedIn = accounts.active() ?: throw java.io.IOException("Нет аккаунта")
                // Developer mode: a temporary nick joins as an offline player without touching the account.
                val tempNick = cfg.devNick.trim()
                val account = if (cfg.developerMode && Account.validNick(tempNick)) {
                    Account(id = "dev-$tempNick", type = AccountType.OFFLINE, username = tempNick, uuid = Account.offlineUuid(tempNick), login = tempNick, clientToken = "dev")
                } else signedIn
                val version = cfg.minecraftVersion
                val profileId = if (!cfg.loader.equals("fabric", true)) version else {
                    val loader = cfg.fabricLoaderVersion.ifBlank { installer.installedLoader(version) }
                        ?: throw java.io.IOException("Fabric не установлен")
                    "fabric-loader-$loader-$version"
                }
                setPhase(Phase.STARTING, "Проверка библиотек")
                installer.ensureRuntime(version, profileId)
                installer.requireVersionReady(version)
                Skins.clearLegacyDelivery(account, AppPaths.game(version))
                setPhase(Phase.STARTING, "Minecraft запускается")
                WorkService.show(app, "Minecraft запускается")
                game.start(account, profileId)
                endBusy()
                CwLog.info("Окно игры открывается на телефоне")
                val cursors = mutableMapOf<String, Long>()
                game.outputLogs().forEach { file ->
                    if (file.isFile) cursors[file.absolutePath] = file.length()
                }
                var lastLine = "Окно игры открывается на телефоне"
                var lastBeat = System.currentTimeMillis()
                val openedAt = System.currentTimeMillis()
                var diedEarly = false
                _state.update { it.copy(detail = lastLine, progressText = lastLine) }
                while (true) {
                    val stage = PhoneVm.stage()
                    val beat = PhoneVm.beat()
                    val stale = beat > 0 && System.currentTimeMillis() - beat > 12_000
                    if (stage == PhoneVm.Stage.EXITED) break
                    if (stage == PhoneVm.Stage.RUNNING && (stale || PhoneVm.gameProcessDead())) break
                    if (stage == PhoneVm.Stage.STARTING && stale && System.currentTimeMillis() - openedAt > 15_000) {
                        diedEarly = true
                        break
                    }
                    if (stage == PhoneVm.Stage.IDLE) break
                    val lines = game.freshGameLines(cursors)
                    for (line in lines) {
                        val short = line.take(220)
                        CwLog.info("Игра: $short")
                        lastLine = short
                        lastBeat = System.currentTimeMillis()
                    }
                    if (lines.isNotEmpty()) {
                        _state.update { it.copy(detail = lastLine, progressText = lastLine) }
                    } else if (System.currentTimeMillis() - lastBeat > 5000) {
                        lastBeat = System.currentTimeMillis()
                        CwLog.info(lastLine)
                    }
                    if (game.windowReady() && _state.value.phase != Phase.RUNNING) {
                        _state.update { it.copy(phase = Phase.RUNNING, detail = "Minecraft запущен", progressText = "Minecraft запущен") }
                        CwLog.info("Окно Minecraft создано")
                    }
                    delay(400)
                }
                val code = PhoneVm.storedCode()
                val storedError = PhoneVm.storedError()
                val launchId = PhoneVm.launchId().ifBlank { "неизвестен" }
                CwLog.info("Запуск $launchId завершился: код ${PhoneVm.formatCode(code)}")
                val showed = game.booted
                game.booted = false
                if (game.stoppedByUser || storedError == "stop") {
                    evaluate()
                } else if (diedEarly || storedError.isNotBlank() || !showed) {
                    delay(300)
                    val androidExit = collectGameExitInfo()
                    val tail = listOf(gameLogsTail(), androidExit).filter { it.isNotBlank() }.joinToString("\n")
                    if (tail.isNotBlank()) CwLog.warn("Игра завершилась с ошибкой:\n$tail")
                    val message = when {
                        storedError.isNotBlank() && storedError != "stop" -> storedError
                        else -> explainCrash(tail, code)
                    }
                    evaluate()
                    _state.update {
                        it.copy(phase = Phase.ERROR, detail = message.lineSequence().first(), info = message)
                    }
                } else {
                    evaluate()
                }
                WorkService.hide(app)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun gameLogsTail(): String {
        val parts = game.outputLogs()
        return parts.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            try {
                file.readText().takeLast(8_000)
            } catch (_: Exception) {
                null
            }
        }.joinToString("\n")
    }

    private fun collectGameExitInfo(): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        return try {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val started = PhoneVm.startedAt()
            val entry = manager.getHistoricalProcessExitReasons(app.packageName, 0, 12)
                .filter { it.processName.endsWith(":game") && (started <= 0 || it.timestamp >= started - 2_000) }
                .maxByOrNull { it.timestamp } ?: return ""
            val reason = when (entry.reason) {
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                else -> "reason-${entry.reason}"
            }
            val statusMeaning = when (entry.reason) {
                ApplicationExitInfo.REASON_EXIT_SELF -> "process exit status"
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android signal/status; not a JVM exit code"
                else -> "Android status; not a JVM exit code"
            }
            val line = "ANDROID_PROCESS_EXIT: process=${entry.processName}, reason=$reason, androidStatusRaw=${entry.status}, statusMeaning=$statusMeaning, importance=${entry.importance}, description=${entry.description.orEmpty()}"
            File(AppPaths.logs, "android-exit.txt").writeText(line + "\n")
            runCatching {
                entry.traceInputStream?.use { input ->
                    File(AppPaths.logs, "android-exit-trace.bin").outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure { CwLog.debug("Trace ApplicationExitInfo недоступен: ${it.message}") }
            CwLog.error(line)
            line
        } catch (e: Exception) {
            CwLog.debug("ApplicationExitInfo недоступен: ${e.stackTraceToString()}")
            ""
        }
    }

    private fun verifyThenRepair() {
        val problems = installer.verifyMods(cfg.minecraftVersion).toMutableList()
        if (!installer.versionReady(cfg.minecraftVersion, full = true)) problems.add("Файлы Minecraft не готовы")
        if (problems.isEmpty()) {
            evaluate()
            return
        }
        _state.update {
            it.copy(dialog = DialogModel(
                problems.joinToString("\n") + "\nВосстановить сборку?",
                "Восстановить", "Закрыть", "repair"
            ))
        }
    }

    private fun begin(name: String): Boolean {
        if (_state.value.busy) {
            CwLog.warn("Операция «$name» отклонена: уже выполняется")
            return false
        }
        cancel.set(false)
        deletePartialOnCancel = false
        _state.update {
            it.copy(
                busy = true,
                progress = 0f,
                progressText = name,
                xferTitle = "",
                xferDone = 0,
                xferTotal = 0,
                xferSpeed = 0,
                xferExtra = ""
            )
        }
        WorkService.show(app, name)
        return true
    }

    private fun deleteCancelledDownloads() {
        fun parts(dir: File) {
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (file.name == "jre") return@forEach
                    parts(file)
                } else if (file.name.endsWith(".part") || file.name.endsWith(".drv")) {
                    file.delete()
                }
            }
        }
        parts(AppPaths.root)
        val version = cfg.minecraftVersion
        if (!installer.versionReady(version)) deleteTree(AppPaths.game(version))
    }

    private fun endBusy() {
        _state.update {
            it.copy(busy = false, progress = 0f, xferTitle = "", xferDone = 0, xferTotal = 0, xferSpeed = 0, xferExtra = "")
        }
        WorkService.hide(app)
    }

    private fun fail(e: Exception) {
        var cur: Throwable = e
        while (cur.cause != null && cur !is java.io.IOException) {
            cur = cur.cause!!
        }
        var message = cur.message ?: e.message ?: "Ошибка"
        if (message.contains("Pointer tag", ignoreCase = true)
            || message.contains("failed to connect", ignoreCase = true)
            || message.contains("timed out", ignoreCase = true)
            || message.contains("timeout", ignoreCase = true)
            || message.contains("unable to resolve", ignoreCase = true)
            || message.contains("no address associated", ignoreCase = true)
            || message.contains("sakura.sld.tw", ignoreCase = true)
        ) {
            message = "Нет связи с серверами Minecraft. Уже скачанное сохранено, нажмите ещё раз."
        }
        val cancelled = cancel.get() || message == "Отменено"
        val wipe = cancelled && deletePartialOnCancel
        deletePartialOnCancel = false
        if (cancelled) CwLog.warn(if (wipe) "Загрузка отменена, файлы удалены" else "Загрузка остановлена, скачанное сохранено")
        else CwLog.warn("Ошибка: $message\n${e.stackTraceToString().take(2000)}")
        endBusy()
        if (wipe) deleteCancelledDownloads()
        if (cancelled) {
            evaluate()
        } else {
            _state.update { it.copy(phase = Phase.ERROR, detail = message, info = message) }
        }
    }

    private fun explainCrash(log: String, code: Int): String {
        val androidExit = log.lineSequence().firstOrNull { it.contains("ANDROID_PROCESS_EXIT:") }
        if (!androidExit.isNullOrBlank()) {
            val reason = androidExit.substringAfter("ANDROID_PROCESS_EXIT:").trim()
            return "Игровой процесс CWLauncher завершён Android.\n\n$reason\n\nПоследний этап: ${PhoneVm.stage()}\nСтатус JVM: ${PhoneVm.formatCode(code)}\n\nНажмите «Экспорт логов» и приложите ZIP. Для native tombstone дополнительно может потребоваться ADB Logcat."
        }
        if (log.contains("ASM not detected") || log.contains("ClassReader.class was renamed")) {
            return "Не удалось запустить Minecraft.\n\nПричина:\nFabric Loader не нашёл библиотеку ASM.\n\nФайл:\norg.objectweb.asm.ClassReader\n\nНажмите «Повторить» — недостающая библиотека будет скачана заново. Подробности записаны в лог."
        }
        if (log.contains("UnsupportedClassVersionError")) {
            return "Не удалось запустить Minecraft.\n\nПричина:\nНужна Java 17.\n\nФайл:\nJava runtime"
        }
        val line = log.lineSequence().map { it.trim().trimStart('#', ' ') }.firstOrNull {
            val text = it.trim()
            if (text.isEmpty() || text.startsWith("A fatal error has been detected", true)) false
            else text.contains("Exception") || text.startsWith("Caused by:") || text.contains("dlopen failed")
                || text.contains("CANNOT LINK EXECUTABLE")
                || text.contains("SIGSEGV") || text.contains("SIGABRT") || text.contains("Internal Error")
                || text.contains("Problematic frame") || text.contains("Could not reserve")
                || text.contains("Unrecognized VM option") || text.contains("libc.so.6")
                || (text.contains("UnsatisfiedLinkError") && !text.contains("already", true))
                || text.contains("OutOfMemory")
                || text.contains("Failed to initialize GLFW") || text.contains("Failed to create the GLFW window")
                || text.contains("couldn't locate the game") || text.contains("Failed to attach")
                || text.contains("окно Minecraft не создано") || text.contains("завершился с ошибкой")
        }
        val reported = line ?: crashReportLine()
        val tailLine = log.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList().takeLast(8)
            .joinToString("\n")
        if (!reported.isNullOrBlank()) {
            return "Не удалось запустить Minecraft.\n\nПричина:\n${reported.take(220)}\n\nПодробности записаны в лог."
        }
        if (tailLine.isNotBlank()) {
            return "Minecraft закрылся, не открыв окно.\n\nПоследние строки:\n${tailLine.take(700)}"
        }
        if (code == 134) {
            return "Игра закрылась с кодом 134. Java была остановлена при запуске. Подробности записаны в лог."
        }
        if (code == PhoneVm.UNKNOWN_EXIT_CODE) {
            return "Причина завершения пока не установлена.\n\nПоследний этап: ${PhoneVm.stage()}\nСтатус JVM: не получен\nЖурнал: ${PhoneVm.traceFile().name}\n\nНажмите «Экспорт логов». Если Android зарегистрировал native-crash, его причина будет в diagnostic-summary и launcher.log; полный tombstone доступен только через ADB Logcat."
        }
        return "Minecraft закрылся внутри графической библиотеки, окно не создано. Код $code."
    }

    private fun crashReportLine(): String? {
        val files = mutableListOf(File(AppPaths.logs, "hs_err.log"))
        AppPaths.logs.listFiles()?.filterTo(files) { it.name.startsWith("hs_err") && it.isFile }
        AppPaths.game(cfg.minecraftVersion).listFiles()?.filterTo(files) { it.name.startsWith("hs_err") && it.isFile }
        val text = files.filter { it.isFile && it.length() > 0 }.maxByOrNull { it.lastModified() }?.readText()?.take(12_000)
            ?: return null
        val lines = text.lineSequence().map { it.trim().trimStart('#', ' ') }.filter { it.isNotBlank() }.toList()
        return lines.firstOrNull { it.contains("Problematic frame") || it.contains("Internal Error") || it.contains("SIGSEGV") || it.contains("SIGABRT") }
            ?: lines.firstOrNull { it.startsWith("C ") || it.startsWith("V ") || it.startsWith("j ") }
    }

    private fun setPhase(phase: Phase, detail: String) {
        _state.update { it.copy(phase = phase, detail = detail, progressText = detail) }
        WorkService.show(app, detail)
    }

    private fun publishAccount() {
        _state.update {
            it.copy(
                accountName = accounts.active()?.username?.ifBlank { "без имени" } ?: "Нет аккаунта",
                accountTick = it.accountTick + 1
            )
        }
    }

    private fun reloadFiles(screen: Screen) {
        val root = if (screen == Screen.MODS) AppPaths.mods(cfg.minecraftVersion) else AppPaths.game(cfg.minecraftVersion)
        val list = root.listFiles()?.sortedBy { it.name }?.map { file ->
            if (file.isDirectory) file.name + "/" else file.name
        }.orEmpty()
        _state.update { it.copy(files = list, browserPath = root.absolutePath) }
    }
}
