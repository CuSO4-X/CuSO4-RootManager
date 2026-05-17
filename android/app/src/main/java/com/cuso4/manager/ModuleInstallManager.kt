package com.cuso4.manager

import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ModuleInstallManager(private val bridge: CuSO4Bridge) {

    private val activity get() = bridge.activity
    private val runOnBackend = bridge::runOnBackend
    private fun evaluateJs(s: String) = activity.evaluateJs(s)

    private fun postProgress(message: String) {
        val json = JSONObject().apply { put("message", message) }
        evaluateJs("if(window.onModuleInstallProgress) window.onModuleInstallProgress($json);")
    }

    private fun postResult(ok: Boolean) {
        val json = JSONObject().apply {
            put("ok", ok)
            put("message", if (ok) "安装完成，请重启设备" else "安装失败")
        }
        evaluateJs("if(window.onModuleInstallResult) window.onModuleInstallResult($json);")
    }

    private fun parseModulePropSimple(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx <= 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (key.isNotBlank()) result[key] = value
        }
        return result
    }

    @JavascriptInterface
    fun pickModuleFile(): String {
        val json = JSONObject()
        return try {
            activity.runOnUiThread {
                activity.openFilePicker()
            }
            json.put("ok", true)
            json.put("message", "文件选择器已打开")
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false)
            json.put("message", e.message ?: "unknown")
            json.toString()
        }
    }

    fun installModuleFromZip(zipPath: String) {
        runOnBackend {
            try {
                postProgress("- 打开压缩包: ${File(zipPath).name}")
                val zip = ZipFile(File(zipPath))
                val zipFileCount = zip.entries().asSequence().count { !it.isDirectory }
                postProgress("- 压缩包共 ${zipFileCount} 个文件")

                postProgress("- 查找 module.prop...")
                val propEntry = zip.getEntry("module.prop")
                    ?: zip.getEntry("META-INF/com/google/android/update-binary")
                if (propEntry == null) {
                    postProgress("! 错误: 未找到 module.prop，不是有效的 Magisk 模块")
                    postResult(false)
                    zip.close()
                    return@runOnBackend
                }

                val propBytes = zip.getInputStream(propEntry).readBytes()
                val propContent = String(propBytes)
                val props = parseModulePropSimple(propContent)
                val moduleId = props["id"] ?: File(zipPath).nameWithoutExtension
                val moduleName = props["name"] ?: moduleId
                val moduleVer = props["version"] ?: "未知"
                val moduleAuthor = props["author"] ?: "未知"

                postProgress("- 模块名称: $moduleName")
                postProgress("- 模块 ID: $moduleId")
                postProgress("- 版本: $moduleVer")
                postProgress("- 作者: $moduleAuthor")

                val oldDir = File("/data/adb/modules/$moduleId")
                if (oldDir.exists()) {
                    postProgress("- 检测到旧版本，正在卸载...")
                }

                postProgress("- 正在解压模块文件...")
                val cacheExtractDir = File(activity.cacheDir, "module_extract")
                if (cacheExtractDir.exists()) cacheExtractDir.deleteRecursively()
                cacheExtractDir.mkdirs()

                var extractedCount = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val dest = File(cacheExtractDir, entry.name)
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                    extractedCount++
                }
                zip.close()
                postProgress("- 已解压 $extractedCount 个文件到临时目录")

                postProgress("- 请求 root 权限...")
                postProgress("- 正在部署到 /data/adb/modules/$moduleId")
                val targetDir = "/data/adb/modules/$moduleId"
                val result = RootShell.exec(
                    "rm -rf $targetDir",
                    "mkdir -p $targetDir",
                    "cp -r ${cacheExtractDir.absolutePath}/* $targetDir/",
                    "chmod -R 755 $targetDir",
                    "chown -R 0:0 $targetDir"
                )

                cacheExtractDir.deleteRecursively()
                postProgress("- 清理临时文件完成")

                if (result.ok) {
                    ModuleManager.invalidateCache()
                    postProgress("- 权限设置: chmod 755 / chown 0:0 完成")
                    postProgress("+ 安装成功! 模块已部署到 $targetDir")
                    postProgress("+ 请重启设备以激活模块")
                    postResult(true)
                } else {
                    postProgress("! 部署失败: ${result.error.ifBlank { "未知错误" }}")
                    postResult(false)
                }
            } catch (e: Exception) {
                postProgress("! 安装异常: ${e.message}")
                postResult(false)
            }
        }
    }
}
