package ru.cw.launcher.engine

import java.io.File
import java.io.IOException

/**
 * Developer mode: own builds of the current Minecraft version. Each one is a folder with its own
 * mods, worlds, configs and downloads; libraries and assets stay shared with the main folder.
 */
object Instances {
    private val subfolders = listOf("mods", "saves", "config", "resourcepacks", "shaderpacks", "downloads")

    /** Folder name, or an empty string if the name does not fit. */
    fun safeName(raw: String?): String {
        var s = raw.orEmpty().trim().filterNot { it in "<>:\"/\\|?*" || it.code < 32 }
        s = s.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        if (s.length > 40) s = s.substring(0, 40).trim()
        if (s.isBlank() || s == "." || s == "..") return ""
        if (s.equals("instances", true) || s.equals("natives", true)) return ""
        return s
    }

    fun root(version: String): File = File(AppPaths.game(version), "instances")

    fun dir(version: String, name: String): File = File(root(version), safeName(name))

    fun list(version: String): List<String> =
        root(version).listFiles()?.filter { it.isDirectory }?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()

    /** Creates the build folder; [copyMain] fills it with the mods and configs of the main folder. */
    fun create(version: String, raw: String, copyMain: Boolean): String {
        val name = safeName(raw)
        if (name.isEmpty()) throw IOException("Введите имя сборки")
        val target = dir(version, name)
        if (target.exists()) throw IOException("Сборка «$name» уже есть")
        subfolders.forEach { File(target, it).mkdirs() }
        if (copyMain) {
            val main = AppPaths.game(version)
            for (part in listOf("mods", "config")) {
                val from = File(main, part)
                if (from.isDirectory) from.copyRecursively(File(target, part), overwrite = true)
            }
        }
        return name
    }

    fun delete(version: String, name: String) {
        val target = dir(version, name)
        if (safeName(name).isNotEmpty() && target.isDirectory) target.deleteRecursively()
    }

    fun sizeMb(version: String, name: String): Long =
        dir(version, name).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
}
