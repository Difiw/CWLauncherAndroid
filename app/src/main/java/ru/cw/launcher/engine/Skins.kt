package ru.cw.launcher.engine

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Skins for the game. Minecraft only shows a custom skin for an offline account through a
 * client-side skin mod, so the launcher stores the player's PNG and, at every launch, places it
 * where CustomSkinLoader looks for local skins (CustomSkinLoader/LocalSkin/skins/<nick>.png).
 * CustomSkinLoader itself is downloaded once from Modrinth into the mods folder.
 */
object Skins {
    data class Item(val id: String, val name: String, val file: File, val active: Boolean)

    private fun accountDir(accountId: String) = File(AppPaths.skins, accountId)
    private fun selectedFile(accountId: String) = File(accountDir(accountId), ".selected")

    /** Active skin file. Also migrates the single-file format used by older launcher builds. */
    @Synchronized
    fun skinFile(accountId: String): File {
        migrate(accountId)
        return selected(accountId) ?: File(accountDir(accountId), "__no_skin__.png")
    }

    @Synchronized
    fun list(accountId: String): List<Item> {
        migrate(accountId)
        val chosen = selected(accountId)
        return accountDir(accountId).listFiles()
            ?.filter { it.isFile && image(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                Item(file.name, displayName(file), file, same(file, chosen))
            }?.sortedByDescending { it.active }.orEmpty()
    }

    @Synchronized
    fun add(accountId: String, bytes: ByteArray, displayName: String): Item {
        migrate(accountId)
        val png = gamePng(bytes) ?: throw java.io.IOException("Не удалось подготовить PNG для Minecraft")
        val dir = accountDir(accountId).apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }.take(12)
        val base = safeBase(displayName)
        val file = File(dir, "$base-$hash.png")
        file.writeBytes(png)
        selectedFile(accountId).writeText(file.name)
        return Item(file.name, displayName(file), file, true)
    }

    @Synchronized
    fun select(accountId: String, id: String): Boolean {
        migrate(accountId)
        val dir = accountDir(accountId).canonicalFile
        val file = File(dir, id).canonicalFile
        if (file.parentFile != dir || !file.isFile || !image(file)) return false
        selectedFile(accountId).writeText(file.name)
        return true
    }

    @Synchronized
    fun remove(accountId: String, id: String): Boolean {
        migrate(accountId)
        val dir = accountDir(accountId).canonicalFile
        val file = File(dir, id).canonicalFile
        if (file.parentFile != dir) return false
        val wasActive = same(file, selected(accountId))
        file.delete()
        if (wasActive) {
            val next = newest(dir)
            if (next == null) selectedFile(accountId).delete() else selectedFile(accountId).writeText(next.name)
        }
        return wasActive
    }

    /** Migrates every earlier Android layout without losing the selected skin. */
    private fun migrate(accountId: String) {
        val dir = accountDir(accountId).apply { mkdirs() }
        val oldIndex = File(dir, "index.json")
        val oldItems = File(dir, "items")
        if (oldIndex.isFile || oldItems.isDirectory) {
            val index = runCatching { JSONObject(oldIndex.readText()) }.getOrNull()
            val active = index?.optString("active").orEmpty()
            val names = index?.optJSONObject("names")
            oldItems.listFiles()?.filter { it.isFile && it.extension.equals("png", true) }?.forEach { source ->
                val png = gamePng(source) ?: source.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }.take(12)
                val base = safeBase(names?.optString(source.nameWithoutExtension, "Скин") ?: "Скин")
                val target = File(dir, "$base-$hash.png")
                if (!target.isFile) target.writeBytes(png)
                if (source.nameWithoutExtension == active) selectedFile(accountId).writeText(target.name)
            }
            oldItems.deleteRecursively()
            oldIndex.delete()
        }
        val old = File(dir, "skin.png")
        if (old.isFile) {
            val png = gamePng(old) ?: old.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }.take(12)
            val target = File(dir, "Ранее-загруженный-скин-$hash.png")
            if (!target.isFile) target.writeBytes(png)
            selectedFile(accountId).writeText(target.name)
            old.delete()
        }
    }

    private fun selected(accountId: String): File? {
        val dir = accountDir(accountId)
        val marker = selectedFile(accountId)
        runCatching {
            if (marker.isFile) {
                val candidate = File(dir, marker.readText().trim()).canonicalFile
                if (candidate.parentFile == dir.canonicalFile && candidate.isFile && image(candidate)) return candidate
            }
        }
        return newest(dir)?.also { runCatching { marker.writeText(it.name) } }
    }

    private fun newest(dir: File): File? = dir.listFiles()?.filter { it.isFile && image(it) }?.maxByOrNull { it.lastModified() }
    private fun image(file: File) = file.extension.lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg")
    private fun same(a: File?, b: File?) = a != null && b != null && runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)
    private fun safeBase(name: String): String = name.replace(Regex("(?i)\\.(png|jpe?g)$"), "")
        .replace(Regex("[^A-Za-z0-9А-Яа-яЁё_-]+"), "-").trim('-').ifBlank { "skin" }.take(48)
    private fun displayName(file: File): String = file.name.replace(Regex("(?i)\\.png$"), "").replace(Regex("-[0-9a-f]{12}$"), "")

    fun localSkinTarget(gameDir: File, nick: String): File =
        File(gameDir, "CustomSkinLoader/LocalSkin/skins/${nick.replace(Regex("[^A-Za-z0-9_]"), "_")}.png")

    /** Returns an error text when the file cannot be used as a skin, or null when it is fine. */
    fun validate(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "Это не картинка. Выберите PNG-файл скина."
        val ok = bounds.outWidth >= 64 && bounds.outWidth % 64 == 0 &&
            (bounds.outHeight == bounds.outWidth || bounds.outHeight * 2 == bounds.outWidth)
        if (!ok) return "Размер скина ${bounds.outWidth}×${bounds.outHeight}. Нужен PNG 64×64, 64×32 или кратного размера."
        return null
    }

    /** Normalized 64px PNG accepted by Minecraft and authlib-injector. */
    fun gamePng(file: File): ByteArray? = try {
        gamePng(file.readBytes())
    } catch (e: Exception) {
        CwLog.warn("Скин не подготовлен: ${e.message}")
        null
    }

    fun gamePng(bytes: ByteArray): ByteArray? = try {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val height = if (bitmap.height >= bitmap.width) 64 else 32
        val normalized = if (bitmap.width == 64 && bitmap.height == height) bitmap
            else Bitmap.createScaledBitmap(bitmap, 64, height, false)
        ByteArrayOutputStream().use { out ->
            if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, out)) null else out.toByteArray()
        }.also {
            if (normalized !== bitmap) normalized.recycle()
            bitmap.recycle()
        }
    } catch (e: Exception) {
        CwLog.warn("Скин не подготовлен: ${e.message}")
        null
    }

    /** Transparent marker in the classic arm area means an Alex/slim layout. */
    fun slim(file: File): Boolean = try {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val scale = (bitmap.width / 64).coerceAtLeast(1)
        val result = bitmap.getPixel(54 * scale, 20 * scale).ushr(24) == 0
        bitmap.recycle()
        result
    } catch (_: Exception) { false }

    /** Puts the active account's skin into the game folder, or removes a stale one. */
    fun apply(account: Account, gameDir: File) {
        val target = localSkinTarget(gameDir, account.username)
        val source = skinFile(account.id)
        try {
            if (source.isFile) {
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, "${target.name}.part")
                source.copyTo(part, overwrite = true)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) source.copyTo(target, overwrite = true)
                target.setLastModified(System.currentTimeMillis())
            } else if (target.isFile) {
                target.delete()
            }
            // CustomSkinLoader keeps decoded textures between launches. Clearing its own cache is
            // required after selecting, replacing or deleting a local skin.
            File(gameDir, "CustomSkinLoader/caches").deleteRecursively()
        } catch (e: Exception) {
            CwLog.warn("Скин: ${e.message}")
        }
    }

    /** Removes files created by the obsolete CustomSkinLoader delivery path. */
    fun clearLegacyDelivery(account: Account, gameDir: File) {
        try {
            localSkinTarget(gameDir, account.username).delete()
            File(gameDir, "CustomSkinLoader/caches").deleteRecursively()
        } catch (e: Exception) {
            CwLog.warn("Очистка старого кэша скина: ${e.message}")
        }
    }

    fun loaderPresent(mods: File): Boolean =
        mods.listFiles()?.any { it.isFile && it.name.contains("customskinloader", true) && it.extension.equals("jar", true) } == true

    /** Downloads CustomSkinLoader for [version]. Returns an error text, or null on success. */
    fun ensureLoader(net: Net, version: String, mods: File): String? {
        if (loaderPresent(mods)) return null
        return try {
            mods.mkdirs()
            val query = "game_versions=%5B%22$version%22%5D&loaders=%5B%22fabric%22%5D"
            val body = net.getText("https://api.modrinth.com/v2/project/customskinloader/version?$query")
            val versions = JSONArray(body)
            if (versions.length() == 0) return "Для Minecraft $version нет мода скинов"
            val files = versions.getJSONObject(0).getJSONArray("files")
            var pick = files.getJSONObject(0)
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                if (f.optBoolean("primary")) pick = f
            }
            val name = pick.getString("filename").replace(Regex("[^A-Za-z0-9._+-]"), "_")
            val dest = File(mods, name)
            net.download(pick.getString("url"), dest, null, 0, null, null)
            if (!looksLikeZip(dest)) {
                dest.delete()
                return "Мод скинов скачался повреждённым"
            }
            CwLog.info("Мод скинов установлен: $name")
            null
        } catch (e: Exception) {
            CwLog.warn("Мод скинов: ${e.message}")
            "Не удалось скачать мод скинов (нужен интернет): ${e.message ?: "ошибка"}"
        }
    }
}

/**
 * Local Yggdrasil-compatible API, matching the desktop launcher. authlib-injector requests the
 * active profile here and receives a content-addressed texture URL, so Minecraft cannot reuse the
 * first skin from its URL cache after the player selects another file.
 */
object SkinApi {
    @Volatile private var skinBytes = ByteArray(0)
    @Volatile private var slim = false
    @Volatile private var playerName = "Player"
    @Volatile private var playerUuid = "00000000000000000000000000000000"
    @Volatile private var revision = "0"
    @Volatile private var metaJson: String? = null
    @Volatile private var port = 0
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    fun apiRoot(file: File, account: Account): String {
        skinBytes = Skins.gamePng(file) ?: file.readBytes()
        revision = Integer.toUnsignedString(java.util.Arrays.hashCode(skinBytes), 36)
        slim = Skins.slim(file)
        playerName = account.username.ifBlank { "Player" }
        playerUuid = account.shortUuid().replace("-", "").lowercase(Locale.ROOT)
        if (metaJson == null) metaJson = loadMeta()
        if (server == null || server?.isClosed == true) {
            server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
            port = server!!.localPort
            Thread({ loop() }, "cw-skin-api").apply { isDaemon = true; start() }
            CwLog.info("Скин доступен игре: http://127.0.0.1:$port/skin/$revision.png")
        }
        return "http://127.0.0.1:$port/"
    }

    private fun loop() {
        while (server?.isClosed == false) {
            try { server!!.accept().use(::serve) }
            catch (e: Exception) { if (server?.isClosed == false) CwLog.warn("Скин API: ${e.message}") }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 25_000
        val input = BufferedInputStream(socket.getInputStream())
        val rawHead = ByteArrayOutputStream()
        var tail = 0
        while (rawHead.size() < 16_384) {
            val b = input.read()
            if (b < 0) break
            rawHead.write(b)
            tail = ((tail shl 8) or b) and 0xffffffff.toInt()
            if (tail == 0x0d0a0d0a) break
        }
        val head = rawHead.toString(Charsets.ISO_8859_1.name())
        val first = head.lineSequence().firstOrNull().orEmpty().split(' ')
        val method = first.getOrNull(0) ?: "GET"
        val fullPath = first.getOrNull(1) ?: "/"
        val path = fullPath.substringBefore('?')
        val length = header(head, "content-length")?.toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
        val requestBody = if (length > 0) input.readNBytes(length) else ByteArray(0)
        when {
            path.startsWith("/skin/") -> write(socket, 200, "image/png", skinBytes)
            path == "/" -> write(socket, 200, "application/json; charset=utf-8", metadata().toByteArray())
            isOurProfile(path) -> {
                write(socket, 200, "application/json; charset=utf-8", profile().toByteArray())
                CwLog.info("Игре отдан актуальный скин $playerName ($revision)")
            }
            else -> proxy(socket, method, fullPath, header(head, "content-type"), header(head, "authorization"), requestBody)
        }
    }

    private fun isOurProfile(path: String): Boolean {
        val flat = path.replace("-", "").lowercase(Locale.ROOT)
        return flat.contains("/profile/") && flat.contains(playerUuid)
    }

    private fun loadMeta(): String {
        val local = """{"meta":{"serverName":"CW","implementationName":"CWLauncher Android"},"skinDomains":["127.0.0.1","localhost","ely.by"]}"""
        return try {
            val c = URL(Ely.INJECTOR_API).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            val raw = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            c.disconnect()
            val root = JSONObject(raw)
            val domains = root.optJSONArray("skinDomains") ?: JSONArray().also { root.put("skinDomains", it) }
            val values = (0 until domains.length()).map { domains.optString(it) }.toMutableList()
            if ("localhost" !in values) values.add(0, "localhost")
            if ("127.0.0.1" !in values) values.add(0, "127.0.0.1")
            root.put("skinDomains", JSONArray(values)).toString()
        } catch (e: Exception) {
            CwLog.warn("Метаданные Ely для скина не прочитаны: ${e.message}")
            local
        }
    }

    private fun metadata() = metaJson?.takeIf { it.isNotBlank() } ?: loadMeta()

    private fun profile(): String {
        val url = "http://127.0.0.1:$port/skin/$revision.png"
        val model = if (slim) ",\"metadata\":{\"model\":\"slim\"}" else ""
        val textures = "{\"timestamp\":${System.currentTimeMillis()},\"profileId\":\"$playerUuid\",\"profileName\":\"${json(playerName)}\",\"textures\":{\"SKIN\":{\"url\":\"$url\"$model}}}"
        val value = Base64.getEncoder().encodeToString(textures.toByteArray())
        return "{\"id\":\"$playerUuid\",\"name\":\"${json(playerName)}\",\"properties\":[{\"name\":\"textures\",\"value\":\"$value\",\"signature\":\"authlib-injector-dummy-verify\"}]}"
    }

    private fun proxy(socket: Socket, method: String, path: String, type: String?, auth: String?, body: ByteArray) {
        try {
            val connection = URL(Ely.INJECTOR_API + if (path.startsWith('/')) path else "/$path").openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            type?.let { connection.setRequestProperty("Content-Type", it) }
            auth?.let { connection.setRequestProperty("Authorization", it) }
            if (body.isNotEmpty()) { connection.doOutput = true; connection.outputStream.use { it.write(body) } }
            val code = connection.responseCode
            val data = (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            write(socket, code, connection.contentType ?: "application/json; charset=utf-8", data)
            connection.disconnect()
        } catch (e: Exception) {
            write(socket, 502, "text/plain; charset=utf-8", (e.message ?: "Skin API error").toByteArray())
        }
    }

    private fun write(socket: Socket, code: Int, type: String, body: ByteArray) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val status = if (code == 200) "OK" else "Result"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun header(head: String, name: String): String? = head.lineSequence()
        .firstOrNull { it.substringBefore(':').equals(name, true) }?.substringAfter(':')?.trim()

    /** Uploads to Ely so multiplayer users can see the same selected skin. */
    fun publishToEly(account: Account, png: ByteArray, slimModel: Boolean): Boolean {
        if (account.type != AccountType.ELY || png.isEmpty()) return false
        val token = Ely.token(account)
        val id = account.shortUuid().replace("-", "").lowercase(Locale.ROOT)
        if (token.isBlank() || token == "0" || id.isBlank()) return false
        val endpoints = listOf("${Ely.AUTH_HOST}/api/user/profile/$id/skin", "${Ely.INJECTOR_API}/api/user/profile/$id/skin")
        var last = 0
        for (endpoint in endpoints) {
            last = upload(endpoint, token, png, if (slimModel) "slim" else "default")
            if (last in 200..299) {
                CwLog.info("Скин записан в Ely. Другие игроки увидят его после входа на сервер.")
                return true
            }
            if (last == 400 && !slimModel) {
                last = upload(endpoint, token, png, "")
                if (last in 200..299) {
                    CwLog.info("Скин записан в Ely. Другие игроки увидят его после входа на сервер.")
                    return true
                }
            }
            if (last == 401 || last == 403) break
        }
        CwLog.warn("Ely не сохранил скин (HTTP $last). У себя он виден, другие игроки увидят его, когда Ely примет файл.")
        return false
    }

    private fun upload(url: String, token: String, png: ByteArray, model: String): Int = try {
        val boundary = "cwskin${System.nanoTime()}"
        val raw = ByteArrayOutputStream()
        raw.write("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n".toByteArray())
        raw.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
        raw.write(png)
        raw.write("\r\n--$boundary--\r\n".toByteArray())
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 25_000; c.readTimeout = 25_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { it.write(raw.toByteArray()) }
        val code = c.responseCode
        var response = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            .replace(Regex("\\s+"), " ").trim()
        if (response.length > 160) response = response.take(160)
        c.disconnect()
        if (code in 200..299 && response.lowercase(Locale.ROOT).contains("<html")) {
            CwLog.warn("Ely вернул страницу вместо сохранения скина, HTTP $code")
            0
        } else {
            CwLog.info("Ely skin HTTP $code ${url.substringAfter(Ely.INJECTOR_API, url).substringAfter(Ely.AUTH_HOST, url)}${if (response.isBlank()) "" else " $response"}")
            code
        }
    } catch (e: Exception) { CwLog.warn("Ely не сохранил скин: ${e.message}"); 0 }

    private fun json(value: String) = value.replace("\\", "\\\\").replace("\"", "")
}

/** Shader packs: where they live and whether one is switched on (Iris keeps that in a config file). */
object Shaders {
    /** True when Iris is configured to use a shader pack, i.e. the game will draw with shaders. */
    fun active(gameDir: File): Boolean {
        val file = File(gameDir, "config/iris.properties")
        if (!file.isFile) return false
        return try {
            val lines = file.readLines()
            val pack = lines.firstOrNull { it.startsWith("shaderPack=") }?.substringAfter('=')?.trim().orEmpty()
            val enabled = lines.firstOrNull { it.startsWith("enableShaders=") }?.substringAfter('=')?.trim() != "false"
            enabled && pack.isNotEmpty() && pack != "(internal)"
        } catch (_: Exception) {
            false
        }
    }

    /** Render resolution in percent for the next launch. */
    fun renderScale(cfg: CwSettings, gameDir: File): Int {
        var scale = cfg.renderScale
        if (cfg.shaderAutoScale && active(gameDir)) scale = minOf(scale, cfg.shaderScale)
        // GPU-bound profiles reduce the internal framebuffer while keeping the Android surface
        // full-screen. This is the single most effective knob for phone GPUs and shader packs.
        scale = when (cfg.optimizationMode) {
            3 -> minOf(scale, 75) // GPU / high resolution compromise
            4 -> minOf(scale, 60) // maximum FPS
            5 -> minOf(scale, if (active(gameDir)) 50 else 70) // shaders
            6 -> minOf(scale, 40) // very weak devices
            else -> scale
        }
        return scale.coerceIn(33, 100)
    }
}
