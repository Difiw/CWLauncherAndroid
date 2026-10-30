package ru.cw.launcher.engine

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object BundledJava {
    private const val ASSET = "jre17-arm64.zip"
    private const val STAMP = "jre17-17.0.20d-cw5-font-jni"
    private const val ABI = "arm64-v8a"
    private const val JRE_AWT_HEADLESS_SHA256 = "c7c752714b4f859ea709476b7818ff5dc8a38855d825ef75b4e0e0b4c497975b"

    fun home(): File = File(AppPaths.root, "jre/17")

    fun supported(): Boolean = Build.SUPPORTED_ABIS.contains(ABI)

    fun launcher(context: Context): File? {
        if (!supported()) return null
        val bin = File(context.applicationInfo.nativeLibraryDir, "libcwjava.so")
        return bin.takeIf { it.isFile }
    }

    fun ensure(context: Context, onProgress: ((String) -> Unit)? = null): File {
        if (!supported()) {
            throw java.io.IOException(
                "Встроенная Java 17 собрана для arm64. Этот процессор: ${Build.SUPPORTED_ABIS.joinToString()}"
            )
        }
        val dir = home()
        val marker = File(dir, ".cw-jre")
        val modules = File(dir, "lib/modules")
        // The key identifies the runtime archive itself (bump STAMP when jre17-arm64.zip changes), so
        // an ordinary APK update does not delete and unpack the 53 MB runtime again. Libraries copied
        // from the APK are tracked separately by nativeSignature() in prepareLibraries().
        val key = STAMP
        val ready = marker.isFile && marker.readText() == key && runtimeValid(dir)
        if (!ready) {
            onProgress?.invoke("Распаковка Java 17")
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            var count = 0
            context.assets.open(ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (name.isBlank() || name.startsWith("/") || name.contains("..") || name.contains(':')) {
                            zip.closeEntry()
                            continue
                        }
                        val out = File(dir, name)
                        val rootPath = dir.canonicalPath + File.separator
                        if (!out.canonicalPath.startsWith(rootPath)) {
                            zip.closeEntry()
                            continue
                        }
                        if (entry.isDirectory || name.endsWith("/")) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                            if (name.startsWith("bin/") || name.endsWith(".so") || name.endsWith("jspawnhelper")) {
                                out.setExecutable(true, false)
                            }
                        }
                        count++
                        if (count % 40 == 0) onProgress?.invoke("Распаковка Java 17 · $count")
                        zip.closeEntry()
                    }
                }
            }
            validateRuntime(dir)
            marker.writeText(key)
            CwLog.info("Встроенная Java 17 распакована в ${dir.absolutePath}")
        }
        validateRuntime(dir)
        linkExecutables(context, dir)
        prepareLibraries(context, dir)
        validatePreparedRuntime(dir)
        hidePackagedShmem(context)
        return dir
    }

    /** Sizes of the APK-provided libraries copied into the runtime; changes when an APK update replaces them. */
    private fun nativeSignature(context: Context): String {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val sizes = listOf("libawt_xawt.so", "libfontmanager.so", "libfreetype.so")
            .joinToString(",") { File(dir, it).let { f -> "${f.length()}@${f.lastModified()}" } }
        return "7:$sizes"
    }

    private fun runtimeValid(dir: File): Boolean = try {
        validateRuntime(dir)
        true
    } catch (_: Exception) {
        false
    }

    private fun validateRuntime(dir: File) {
        val required = listOf(
            File(dir, "lib/modules") to 1_000_000L,
            File(dir, "lib/server/libjvm.so") to 1_000_000L,
            File(dir, "lib/libjava.so") to 50_000L,
            File(dir, "lib/libnio.so") to 20_000L
        )
        required.forEach { (file, minimum) ->
            if (!file.isFile || file.length() < minimum) {
                throw java.io.IOException("В Java 17 отсутствует или повреждён ${file.relativeTo(dir).path}")
            }
        }
        val release = File(dir, "release")
        if (!release.isFile) throw java.io.IOException("В Java 17 отсутствует файл release")
        val properties = release.readLines().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1).trim().trim('"')
        }.toMap()
        val version = properties["JAVA_VERSION"].orEmpty()
        val arch = properties["OS_ARCH"].orEmpty()
        if (!version.startsWith("17.")) throw java.io.IOException("Minecraft 1.20.1 требует Java 17, в runtime указана $version")
        if (arch != "aarch64") throw java.io.IOException("Runtime Java имеет архитектуру $arch вместо aarch64")
        CwLog.info("Runtime проверен: Java $version, $arch")
    }

    fun libraryPath(context: Context, home: File): String {
        return listOf(
            File(home, "lib/server").absolutePath,
            File(home, "lib").absolutePath
        ).joinToString(":")
    }

    private fun prepareLibraries(context: Context, home: File) {
        val marker = File(home, ".cw-libs")
        val cxx = File(home, "lib/libc++_shared.so")
        val awtXawt = File(home, "lib/libawt_xawt.so")
        val fontManager = File(home, "lib/libfontmanager.so")
        val freeType = File(home, "lib/libfreetype.so")
        val signature = nativeSignature(context)
        if (marker.isFile && marker.readText() == signature && cxx.isFile && cxx.length() > 100_000
            && awtXawt.isFile && awtXawt.length() > 10_000
            && fontManager.isFile && fontManager.length() > 100_000
            && freeType.isFile && freeType.length() > 100_000) return
        val oldName = "libandroid-shmem.so".toByteArray(Charsets.US_ASCII)
        val newName = "libcwshmem-arm64.so".toByteArray(Charsets.US_ASCII)
        check(oldName.size == newName.size)
        home.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { file ->
            val data = file.readBytes()
            val replaced = replaceBytes(data, oldName, newName) ?: return@forEach
            file.writeBytes(replaced)
        }
        for (rel in listOf("lib", "lib/server")) {
            val dir = File(home, rel)
            renameIfExists(File(dir, "libandroid-shmem.so"), File(dir, "libcwshmem-arm64.so"))
            restoreZlib(dir)
        }
        cxx.parentFile?.mkdirs()
        context.assets.open("libc++_shared.so").use { input ->
            cxx.outputStream().use { input.copyTo(it) }
        }
        cxx.setExecutable(true, false)
        // Keep libawt.so and libawt_headless.so from the bundled JRE as one matched
        // OpenJDK build. In particular, that libawt_headless exports
        // Java_java_awt_Font_initIDs. The small Cacio linker shim with the same
        // SONAME must never overwrite it. Only install components omitted by the
        // runtime archive.
        listOf("libawt_xawt.so", "libfontmanager.so", "libfreetype.so").forEach { name ->
            val source = File(context.applicationInfo.nativeLibraryDir, name)
            val target = File(home, "lib/$name")
            if (!source.isFile || source.length() < 1_000) {
                throw java.io.IOException("В APK отсутствует совместимая AWT-библиотека $name")
            }
            source.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
        }
        copyAsset(context, "jre-extra/fontconfig.bfc", File(home, "lib/fontconfig.bfc"))
        copyAsset(context, "jre-extra/fontconfig.properties", File(home, "lib/fontconfig.properties"))
        val extraLibs = listOf(
            "libjpeg.so.8",
            "libspeexdsp.so",
            "libpulse.so",
            "libpulsecommon-17.0.so",
            "libdbus-1.so",
            "libandroid-execinfo.so",
            "libsndfile.so",
            "libFLAC.so",
            "libogg.so",
            "libopus.so",
            "libvorbis.so",
            "libvorbisenc.so",
            "libmp3lame.so",
            "libmpg123.so"
        )
        var libsReady = true
        for (name in extraLibs) {
            val out = File(home, "lib/$name")
            copyAsset(context, "jre-extra/$name", out)
            if (!out.isFile || out.length() < 1_000) {
                libsReady = false
                CwLog.warn("Не скопировалась библиотека $name")
            } else {
                out.setExecutable(true, false)
            }
        }
        if (!libsReady) return
        marker.writeText(signature)
        CwLog.info("Библиотеки Java и Cacio AWT подготовлены для ARM64")
    }

    private fun validatePreparedRuntime(home: File) {
        val required = linkedMapOf(
            "lib/libawt.so" to 100_000L,
            "lib/libawt_headless.so" to 20_000L,
            "lib/libawt_xawt.so" to 10_000L,
            "lib/libfontmanager.so" to 100_000L,
            "lib/libfreetype.so" to 100_000L,
            "lib/libinstrument.so" to 20_000L,
            "lib/server/libjvm.so" to 1_000_000L
        )
        required.forEach { (relative, minimum) ->
            val file = File(home, relative)
            if (!file.isFile || file.length() < minimum) {
                throw java.io.IOException("Java/Cacio preflight: отсутствует или повреждён $relative")
            }
            if (file.extension == "so") {
                val magic = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
                if (!magic.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
                    throw java.io.IOException("Java/Cacio preflight: $relative не является ELF-библиотекой")
                }
            }
        }
        val headless = File(home, "lib/libawt_headless.so")
        val headlessHash = sha256(headless)
        if (headlessHash != JRE_AWT_HEADLESS_SHA256) {
            throw java.io.IOException(
                "Java/Cacio preflight: libawt_headless.so не совпадает с Java 17.0.20 " +
                    "(Font.initIDs недоступен, sha256=$headlessHash)"
            )
        }
        CwLog.info(
            "Java/Cacio preflight пройден: AWT, Font.initIDs provider, fontmanager, " +
                "FreeType, instrument и JVM готовы"
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hidePackagedShmem(context: Context) {
        val leaked = File(context.applicationInfo.nativeLibraryDir, "libandroid-shmem.so")
        if (leaked.isFile && !leaked.delete()) {
            CwLog.warn("Системный каталог всё ещё содержит libandroid-shmem.so")
        }
    }

    private fun restoreZlib(dir: File) {
        val zlib = File(dir, "libz.so")
        val renamed = File(dir, "libcwzlib.so")
        if (!zlib.isFile && renamed.isFile) {
            if (!renamed.renameTo(zlib)) renamed.copyTo(zlib, overwrite = true)
        }
        if (!zlib.isFile) return
        val soname = File(dir, "libz.so.1")
        if (!soname.isFile || soname.length() != zlib.length()) {
            zlib.copyTo(soname, overwrite = true)
            soname.setExecutable(true, false)
        }
    }

    private fun copyAsset(context: Context, asset: String, out: File) {
        out.parentFile?.mkdirs()
        try {
            context.assets.open(asset).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun renameIfExists(from: File, to: File) {
        if (!from.isFile) return
        if (to.exists() && !to.delete()) return
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun replaceBytes(data: ByteArray, old: ByteArray, new: ByteArray): ByteArray? {
        var changed = false
        val out = data.copyOf()
        var i = 0
        while (i <= out.size - old.size) {
            var same = true
            for (j in old.indices) {
                if (out[i + j] != old[j]) {
                    same = false
                    break
                }
            }
            if (same) {
                new.copyInto(out, i)
                changed = true
                i += old.size
            } else {
                i++
            }
        }
        return if (changed) out else null
    }

    private fun linkExecutables(context: Context, dir: File) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        linkOver(File(nativeDir, "libcwjava.so"), File(dir, "bin/java"))
        linkOver(File(nativeDir, "libjspawnhelper.so"), File(dir, "lib/jspawnhelper"))
    }

    private fun linkOver(target: File, link: File) {
        if (!target.isFile) return
        val backup = File(link.parentFile, link.name + ".real")
        try {
            if (link.exists() && !isSymlink(link)) {
                if (backup.exists()) backup.delete()
                if (!link.renameTo(backup)) return
            } else if (link.exists()) {
                link.delete()
            }
            link.parentFile?.mkdirs()
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (e: Exception) {
            CwLog.warn("Ссылка ${link.name}: ${e.message}")
            if (!link.exists() && backup.exists()) backup.renameTo(link)
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            Os.readlink(file.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
    }
}
