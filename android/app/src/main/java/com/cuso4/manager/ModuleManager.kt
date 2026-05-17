package com.cuso4.manager

data class ModuleInfo(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val enabled: Boolean,
    val icon: String
)

object ModuleManager {

    private val modulesDir = "/data/adb/modules"
    private const val CACHE_TTL_MS = 8000L
    @Volatile
    private var cachedModules: List<ModuleInfo>? = null
    @Volatile
    private var cacheTimestamp = 0L

    fun listModules(forceRefresh: Boolean = false): List<ModuleInfo> {
        val now = System.currentTimeMillis()
        val cached = cachedModules
        if (!forceRefresh && cached != null && now - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }

        val result = mutableListOf<ModuleInfo>()
        val lines = RootShell.execLines(
            """
            for dir in $modulesDir/*; do
              [ -d "${'$'}dir" ] || continue
              id=$(basename "${'$'}dir")
              [ "${'$'}id" = "modules_update" ] && continue
              [ -f "${'$'}dir/module.prop" ] || continue
              if [ -f "${'$'}dir/disable" ]; then disabled=1; else disabled=0; fi
              printf '__MODULE__|%s|%s\n' "${'$'}id" "${'$'}disabled"
              cat "${'$'}dir/module.prop" 2>/dev/null
              printf '\n__END__\n'
            done
            """.trimIndent()
        )

        var currentId: String? = null
        var currentEnabled = true
        val propLines = mutableListOf<String>()

        fun flushCurrent() {
            val id = currentId ?: return
            val props = parseModuleProp(propLines.joinToString("\n"))
            result.add(
                ModuleInfo(
                    id = id,
                    name = props["name"] ?: id,
                    version = props["version"] ?: "?",
                    author = props["author"] ?: "未知",
                    description = props["description"] ?: "",
                    enabled = currentEnabled,
                    icon = props["icon"] ?: "📦"
                )
            )
            currentId = null
            currentEnabled = true
            propLines.clear()
        }

        for (line in lines) {
            when {
                line.startsWith("__MODULE__|") -> {
                    flushCurrent()
                    val parts = line.split('|')
                    if (parts.size >= 3) {
                        currentId = parts[1]
                        currentEnabled = parts[2] != "1"
                    }
                }
                line == "__END__" -> flushCurrent()
                currentId != null -> propLines.add(line)
            }
        }

        flushCurrent()
        cachedModules = result
        cacheTimestamp = now
        return result
    }

    fun toggleModule(id: String, enable: Boolean): Boolean {
        val path = "$modulesDir/$id"
        if (!RootShell.exec("test -d $path").ok) return false
        val ok = if (enable) {
            RootShell.exec("rm -f $path/disable 2>/dev/null").ok
        } else {
            RootShell.exec("touch $path/disable 2>/dev/null").ok
        }
        if (ok) invalidateCache()
        return ok
    }

    fun deleteModule(id: String): Boolean {
        val path = "$modulesDir/$id"
        if (!RootShell.exec("test -d $path").ok) return false
        val ok = RootShell.exec("rm -rf $path 2>/dev/null").ok
        if (ok) invalidateCache()
        return ok
    }

    fun invalidateCache() {
        cachedModules = null
        cacheTimestamp = 0L
    }

    private fun parseModuleProp(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in content.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().removeSuffix("__END__").trim()
            if (key.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }
}
