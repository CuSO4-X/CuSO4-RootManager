package com.cuso4.manager

object RootShell {

    data class Result(val ok: Boolean, val output: String, val error: String)

    @Volatile
    private var cachedHasRoot: Boolean? = null
    @Volatile
    private var cachedMagiskVersion: String? = null
    @Volatile
    private var cachedKernelSuVersion: String? = null
    @Volatile
    private var cachedRootManager: String? = null

    fun exec(cmd: String): Result = exec(*arrayOf(cmd))

    fun exec(vararg cmds: String): Result {
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(false)
                .start()

            val writer = process.outputStream.bufferedWriter()
            for (c in cmds) {
                writer.write(c)
                writer.newLine()
            }
            writer.write("exit")
            writer.newLine()
            writer.flush()
            writer.close()

            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            process.waitFor()

            Result(ok = process.exitValue() == 0 && err.isEmpty(), output = out, error = err)
        } catch (e: Exception) {
            Result(ok = false, output = "", error = e.message ?: "unknown error")
        }
    }

    fun execLines(cmd: String): List<String> {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readLines()
                .also { process.waitFor() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun execLinesCallback(cmd: String, onLine: (String) -> Unit): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim().orEmpty()
                if (trimmed.isNotBlank()) onLine(trimmed)
            }
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun execRaw(cmd: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText().trim()
                .also { process.waitFor() }
        } catch (e: Exception) {
            ""
        }
    }

    fun hasRoot(): Boolean {
        val cached = cachedHasRoot
        if (cached != null) return cached
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            (output.contains("uid=0") || output.contains("root")).also { cachedHasRoot = it }
        } catch (e: Exception) {
            false.also { cachedHasRoot = false }
        }
    }

    fun magiskVersion(): String {
        val cached = cachedMagiskVersion
        if (!cached.isNullOrBlank()) return cached
        return normalizeMagiskVersion(detectMagiskVersion()).also { cachedMagiskVersion = it }
    }

    fun kernelSuVersion(): String {
        val cached = cachedKernelSuVersion
        if (!cached.isNullOrBlank()) return cached
        val raw = detectKernelSuVersion()
        val version = normalizeManagerVersion(raw)
        cachedKernelSuVersion = version
        return version
    }

    fun detectRootManager(): String {
        val cached = cachedRootManager
        if (!cached.isNullOrBlank()) return cached
        val manager = when {
            detectKernelSuVersion().isNotBlank() -> "KernelSU"
            detectMagiskVersion().isNotBlank() -> "Magisk"
            else -> "Unknown"
        }
        cachedRootManager = manager
        return manager
    }

    private fun detectMagiskVersion(): String {
        val version = execRaw("magisk -v 2>/dev/null || magisk --version 2>/dev/null")
        if (version.isNotBlank()) return version

        val props = execRaw("cat /data/adb/magisk/util_functions.sh 2>/dev/null | grep MAGISK_VER_CODE")
        val code = props.substringAfter("MAGISK_VER_CODE=").trim().trim('"')
        if (code.isNotBlank()) return code

        return execRaw("su -c 'echo \$MAGISK_VER_CODE' 2>/dev/null")
    }

    private fun normalizeMagiskVersion(raw: String): String {
        val firstLine = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isBlank()) return "未知"
        val semantic = Regex("""\d+(?:\.\d+){0,2}""").find(firstLine)?.value
        if (!semantic.isNullOrBlank()) return semantic
        val code = Regex("""\b\d{4,6}\b""").find(firstLine)?.value
        if (!code.isNullOrBlank()) return code
        return firstLine.split(Regex("""\s+""")).firstOrNull().orEmpty().ifBlank { "未知" }
    }

    private fun detectKernelSuVersion(): String {
        val direct = execRaw("ksud -V 2>/dev/null || ksud --version 2>/dev/null || ksu --version 2>/dev/null")
        if (direct.contains("kernelsu", ignoreCase = true) || Regex("""\d+(?:\.\d+){0,2}""").containsMatchIn(direct)) {
            return direct
        }

        val procHint = execRaw("cat /proc/version 2>/dev/null | grep -i kernelsu")
        if (procHint.isNotBlank()) return procHint

        val fileHint = execRaw("[ -f /data/adb/ksud/ksud.db ] && echo KernelSU || true")
        if (fileHint.contains("KernelSU", ignoreCase = true)) return fileHint

        return ""
    }

    private fun normalizeManagerVersion(raw: String): String {
        val firstLine = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isBlank()) return "未知"
        val semantic = Regex("""\d+(?:\.\d+){0,2}""").find(firstLine)?.value
        if (!semantic.isNullOrBlank()) return semantic
        return firstLine.split(Regex("""\s+""")).firstOrNull().orEmpty().ifBlank { "未知" }
    }

    fun reboot() {
        exec("reboot")
    }
}
