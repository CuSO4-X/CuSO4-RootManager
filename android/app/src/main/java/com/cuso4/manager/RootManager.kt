package com.cuso4.manager

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class RootManager(private val bridge: CuSO4Bridge) {

    private fun evaluateJs(s: String) = bridge.activity.evaluateJs(s)

    private fun detectHookType(manager: String): String {
        return try {
            if (manager == "KernelSU") {
                val out = RootShell.execRaw("cat /proc/version 2>/dev/null")
                if (out.contains("KernelSU", ignoreCase = true)) "KernelSU" else "未知钩子"
            } else {
                val out = RootShell.execRaw("cat /proc/mounts 2>/dev/null | grep zygisk")
                if (out.isNotBlank()) "Zygisk" else "Riru"
            }
        } catch (_: Exception) { "未知" }
    }

    @JavascriptInterface
    fun getRootState(): String {
        val json = JSONObject()
        return try {
            val manager = RootShell.detectRootManager()
            val managerVersion = when (manager) {
                "KernelSU" -> RootShell.kernelSuVersion()
                "Magisk" -> RootShell.magiskVersion()
                else -> "未知"
            }
            val kernelVersion = RootShell.execRaw("uname -r 2>/dev/null").ifBlank { "未知" }
            val selinuxMode = RootShell.execRaw("getenforce 2>/dev/null").ifBlank { "未知" }
            val fingerprint = RootShell.execRaw("getprop ro.build.fingerprint 2>/dev/null").ifBlank { "未知" }
            val hookType = detectHookType(manager)
            json.put("ok", true)
            json.put("magiskVersion", RootShell.magiskVersion())
            json.put("managerVersion", managerVersion)
            json.put("suBinary", if (RootShell.hasRoot()) "已授权" else "未授权")
            json.put("backend", "CuSO4 Android")
            json.put("manager", manager)
            json.put("kernelVersion", kernelVersion)
            json.put("hookType", hookType)
            json.put("selinuxMode", selinuxMode)
            json.put("systemFingerprint", fingerprint)
            json.put("zygisk", hookType == "Zygisk")
            json.put("ramdisk", manager == "Magisk" && RootShell.execRaw("test -d /data/adb/magisk && echo yes || echo no").trim() == "yes")
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false)
            json.put("message", e.message ?: "unknown")
            json.toString()
        }
    }

    @JavascriptInterface
    fun uninstallMagisk(): String {
        val json = JSONObject()
        return try {
            val result = RootShell.exec("magisk --uninstall 2>/dev/null")
            json.put("ok", result.ok)
            json.put("message", if (result.ok) "Magisk 卸载完成，请重启" else result.error.ifBlank { "卸载失败" })
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false)
            json.put("message", e.message ?: "unknown")
            json.toString()
        }
    }

    @JavascriptInterface
    fun rebootDevice() {
        RootShell.reboot()
    }

    @JavascriptInterface
    fun getSuperuserApps(): String {
        val json = JSONObject()
        return try {
            val manager = RootShell.detectRootManager()
            val policyMap = if (manager == "KernelSU") readKernelSuPolicies() else readMagiskPolicies()
            val apps = JSONArray()
            for ((pkg, pair) in policyMap) {
                val uid = pair.first
                val policy = pair.second
                val entry = JSONObject()
                entry.put("packageName", pkg)
                entry.put("appName", pkg)
                entry.put("uid", uid)
                entry.put("policy", policy)
                apps.put(entry)
            }
            json.put("ok", true)
            json.put("apps", apps)
            json.put("manager", manager)
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false)
            json.put("message", e.message ?: "unknown")
            json.toString()
        }
    }

    @JavascriptInterface
    fun setSuperuserPolicy(packageName: String, policy: String): String {
        val json = JSONObject()
        return try {
            val manager = RootShell.detectRootManager()
            val policyCode = when (policy) {
                "allow" -> 1
                "deny" -> 0
                "ask" -> 2
                else -> 2
            }
            if (manager == "KernelSU") {
                setKernelSuPolicyFallback(packageName, policyCode, json)
            } else {
                val result = RootShell.exec("magiskpolicy --magiskpolicy $packageName $policyCode")
                if (!result.ok) {
                    setPolicyFallback(packageName, policyCode, json)
                } else {
                    json.put("ok", true)
                    json.put("message", "策略已更新")
                }
            }
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false)
            json.put("message", e.message ?: "unknown")
            json.toString()
        }
    }

    @JavascriptInterface
    fun revokeSuperuserPolicy(packageName: String): String {
        val json = JSONObject()
        return try {
            val manager = RootShell.detectRootManager()
            val dbCandidates = if (manager == "KernelSU") {
                listOf(
                    "/data/adb/ksud/ksud.db",
                    "/data/adb/ksud.db",
                    "/data/adb/ksu/ksud.db",
                    "/data/adb/KernelSU/ksud.db"
                )
            } else {
                listOf(
                    "/data/adb/magisk.db",
                    "/data/adb/magisk/magisk.db",
                    "/data/unencrypted/magisk.db"
                )
            }
            revokePolicyInDatabases(dbCandidates, packageName)
            json.put("ok", true)
            json.put("message", "授权已撤销")
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false)
            json.put("message", e.message ?: "unknown")
            json.toString()
        }
    }

    private fun readMagiskPolicies(): Map<String, Pair<Int, Int>> {
        val dbCandidates = listOf(
            "/data/adb/magisk.db",
            "/data/adb/magisk/magisk.db",
            "/data/unencrypted/magisk.db"
        )
        val sqlCandidates = listOf(
            "SELECT uid, package_name, policy FROM policies;",
            "SELECT uid, package, policy FROM policies;",
            "SELECT uid, package_name, policy FROM policy;",
            "SELECT uid, package, policy FROM policy;"
        )

        for (dbPath in dbCandidates) {
            for (sql in sqlCandidates) {
                val rows = queryPolicyRowsBySqlite3(dbPath, sql)
                if (rows.isNotEmpty()) return rows
            }
        }

        for (sql in sqlCandidates) {
            val rows = queryPolicyRowsByMagiskSql(sql)
            if (rows.isNotEmpty()) return rows
        }

        return emptyMap()
    }

    private fun readKernelSuPolicies(): Map<String, Pair<Int, Int>> {
        val dbCandidates = listOf(
            "/data/adb/ksud/ksud.db",
            "/data/adb/ksud.db",
            "/data/adb/ksu/ksud.db",
            "/data/adb/KernelSU/ksud.db"
        )
        val sqlCandidates = listOf(
            "SELECT uid, package_name, policy FROM policies;",
            "SELECT uid, package, policy FROM policies;",
            "SELECT uid, package_name, policy FROM policy;",
            "SELECT uid, package, policy FROM policy;"
        )

        for (dbPath in dbCandidates) {
            for (sql in sqlCandidates) {
                val rows = queryPolicyRowsBySqlite3(dbPath, sql)
                if (rows.isNotEmpty()) return rows
            }
        }

        return emptyMap()
    }

    private fun queryPolicyRowsBySqlite3(dbPath: String, sql: String): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        try {
            val out = RootShell.execRaw("sqlite3 \"$dbPath\" \"$sql\" 2>/dev/null")
            for (line in out.lines()) {
                val parts = line.split('|')
                if (parts.size < 3) continue
                val uid = parts[0].trim().toIntOrNull() ?: continue
                val pkg = parts[1].trim()
                val policy = parts[2].trim().toIntOrNull() ?: continue
                if (pkg.isNotBlank()) result[pkg] = uid to policy
            }
        } catch (_: Exception) {}
        return result
    }

    private fun queryPolicyRowsByMagiskSql(sql: String): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        try {
            val out = RootShell.execRaw("magisk --sqlite \"$sql\" 2>/dev/null")
            for (line in out.lines()) {
                val parts = line.split('|')
                if (parts.size < 3) continue
                val uid = parts[0].trim().toIntOrNull() ?: continue
                val pkg = parts[1].trim()
                val policy = parts[2].trim().toIntOrNull() ?: continue
                if (pkg.isNotBlank()) result[pkg] = uid to policy
            }
        } catch (_: Exception) {}
        return result
    }

    private fun setKernelSuPolicyFallback(packageName: String, policyCode: Int, json: JSONObject) {
        val uid = try {
            RootShell.execRaw("dumpsys package $packageName 2>/dev/null | grep userId= | head -1")
                .substringAfter("userId=").substringBefore(' ').substringBefore(',')
                .trim().toIntOrNull() ?: 0
        } catch (_: Exception) { 0 }
        if (uid > 0) {
            RootShell.exec("ksud policy add $uid $policyCode 2>/dev/null")
            json.put("ok", true)
            json.put("message", "KernelSU 策略已通过 ksud 设置")
        } else {
            json.put("ok", false)
            json.put("message", "无法获取应用 UID")
        }
    }

    private fun setPolicyFallback(packageName: String, policyCode: Int, json: JSONObject) {
        val dbCandidates = listOf(
            "/data/adb/magisk.db",
            "/data/adb/magisk/magisk.db",
            "/data/unencrypted/magisk.db"
        )
        val uid = try {
            RootShell.execRaw("dumpsys package $packageName 2>/dev/null | grep userId= | head -1")
                .substringAfter("userId=").substringBefore(' ').substringBefore(',')
                .trim().toIntOrNull() ?: 0
        } catch (_: Exception) { 0 }
        if (uid > 0) {
            for (db in dbCandidates) {
                val r = RootShell.exec("sqlite3 \"$db\" \"INSERT OR REPLACE INTO policies (uid, package_name, policy) VALUES ($uid, '$packageName', $policyCode);\" 2>/dev/null")
                if (r.ok) {
                    json.put("ok", true)
                    json.put("message", "策略已通过 sqlite3 写入")
                    return
                }
            }
            json.put("ok", false)
            json.put("message", "无法写入数据库")
        } else {
            json.put("ok", false)
            json.put("message", "无法获取应用 UID")
        }
    }

    private fun revokePolicyInDatabases(dbCandidates: List<String>, packageName: String) {
        for (db in dbCandidates) {
            try {
                RootShell.exec("sqlite3 \"$db\" \"DELETE FROM policies WHERE package_name='$packageName';\" 2>/dev/null")
            } catch (_: Exception) {}
        }
    }
}
