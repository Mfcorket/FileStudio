package com.filestudio.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 配置的读写。
 *
 * 落盘为可手工编辑的 JSON：
 * - Windows：`%APPDATA%\FileStudio\settings.json`
 * - macOS：`~/Library/Application Support/FileStudio/settings.json`
 * - Linux/其它：`$XDG_CONFIG_HOME/FileStudio/settings.json`，否则 `~/.config/...`
 *
 * 读取失败（文件不存在、被截断、含非法内容）一律回落到默认配置，
 * 绝不因为配置问题让应用起不来。
 */
class SettingsStore(
    val file: Path,
    /** 为 true 时完全不碰磁盘，仅在内存中保存。 */
    private val memoryOnly: Boolean = false,
) {

    /** 内存模式下保存的值；落盘模式为 null。 */
    private var cached: Settings? = if (memoryOnly) Settings.DEFAULT else null

    /**
     * 读取配置。
     *
     * @return 已保存的配置；任何异常情况下都返回 [Settings.DEFAULT]
     */
    fun load(): Settings {
        if (memoryOnly) {
            return cached ?: Settings.DEFAULT
        }
        return try {
            if (!Files.isRegularFile(file)) {
                Settings.DEFAULT
            } else {
                SettingsJson.decode(Files.readString(file)) ?: Settings.DEFAULT
            }
        } catch (e: IOException) {
            Settings.DEFAULT
        } catch (e: RuntimeException) {
            Settings.DEFAULT
        }
    }

    /**
     * 写入配置。
     *
     * @return 是否成功
     */
    fun save(settings: Settings): Boolean {
        if (memoryOnly) {
            cached = settings
            return true
        }
        return try {
            file.parent?.let { Files.createDirectories(it) }
            // 先写临时文件再替换，避免写入中途失败留下半个配置
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, SettingsJson.encode(settings))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * 以当前配置为基准做一次变换并保存。
     *
     * 用于「读取—修改—写回」这类不想自己管 load/save 的场合，
     * 例如退出时把窗口尺寸记回配置。
     *
     * @return 变换后的配置
     */
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(load()).sanitized()
        save(next)
        return next
    }

    /** 配置是否可用；内存模式恒为 true（始终有一份配置）。 */
    fun exists(): Boolean =
        if (memoryOnly) {
            cached != null
        } else {
            try {
                Files.isRegularFile(file)
            } catch (e: RuntimeException) {
                false
            }
        }

    /** 删除配置；内存模式则是清空为默认值。 */
    fun delete(): Boolean =
        if (memoryOnly) {
            cached = Settings.DEFAULT
            true
        } else {
            try {
                Files.deleteIfExists(file)
                true
            } catch (e: IOException) {
                false
            }
        }

    companion object {
        /**
         * 仅在内存中的存储。
         *
         * 单元测试用它，保证不会读到开发者本机的真实配置；
         * 也用于「明确不落盘」的运行模式。
         */
        fun inMemory(): SettingsStore =
            SettingsStore(Paths.get("filestudio-inmemory-settings.json"), memoryOnly = true)

        /**
         * 平台相关的默认配置路径。
         */
        fun defaultPath(): Path {
            val os = System.getProperty("os.name", "").lowercase()
            val home = Paths.get(System.getProperty("user.home", "."))
            return when {
                os.contains("win") -> {
                    val appData = System.getenv("APPDATA")
                        ?: home.resolve("AppData").resolve("Roaming").toString()
                    Paths.get(appData, "FileStudio", "settings.json")
                }
                os.contains("mac") || os.contains("darwin") ->
                    home.resolve("Library").resolve("Application Support")
                        .resolve("FileStudio").resolve("settings.json")
                else -> {
                    val xdg = System.getenv("XDG_CONFIG_HOME")
                    val base = if (xdg.isNullOrBlank()) {
                        home.resolve(".config")
                    } else {
                        Paths.get(xdg)
                    }
                    base.resolve("FileStudio").resolve("settings.json")
                }
            }
        }
    }
}