package com.cuso4.manager

import android.content.SharedPreferences
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CuSO4Bridge(val activity: MainActivity) {

    internal val backendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CuSO4-Backend").apply { isDaemon = true }
    }

    internal val backupRunning = AtomicBoolean(false)
    internal var backupIsRestore = false

    internal val prefs: SharedPreferences by lazy {
        activity.getSharedPreferences("cuso4_market", android.content.Context.MODE_PRIVATE)
    }

    internal val rootManager = RootManager(this)
    internal val moduleManager = ModuleInstallManager(this)

    internal fun runOnBackend(task: () -> Unit) = backendExecutor.execute(task)

    internal fun okJson(message: String = "操作成功"): String {
        return JSONObject().apply { put("ok", true); put("message", message) }.toString()
    }
    internal fun errorJson(message: String): String {
        return JSONObject().apply { put("ok", false); put("message", message) }.toString()
    }

    private fun postInvokeResult(callbackId: String, resultJson: String) {
        val safeCallbackId = JSONObject.quote(callbackId)
        activity.evaluateJs("if(window.__CuSO4InvokeCB) window.__CuSO4InvokeCB($safeCallbackId, $resultJson);")
    }

    @JavascriptInterface
    fun invokeAsync(methodName: String, argsJson: String, callbackId: String) {
        runOnBackend {
            val result: String = try {
                val args = JSONArray(argsJson)
                when (methodName) {
                    "clearMarketAuth" -> okJson("认证已清除")
                    "setServerUrl" -> {
                        prefs.edit().putString("server_url", args.optString(0, "")).apply()
                        okJson("服务器地址已保存")
                    }
                    "getMarketAuth" -> {
                        val json = JSONObject()
                        val deviceId = prefs.getString("device_id", null)
                        val accesskey = prefs.getString("accesskey", null)
                        if (deviceId != null && accesskey != null) {
                            json.put("ok", true)
                            json.put("deviceid", deviceId)
                            json.put("accesskey", accesskey)
                            json.put("serverUrl", getServerUrl())
                        } else {
                            json.put("ok", false)
                            json.put("message", "未注册")
                        }
                        json.toString()
                    }
                    "getRootState" -> rootManager.getRootState()
                    "uninstallMagisk" -> rootManager.uninstallMagisk()
                    "getSuperuserApps" -> rootManager.getSuperuserApps()
                    "setSuperuserPolicy" -> rootManager.setSuperuserPolicy(
                        args.optString(0, ""), args.optString(1, "ask"))
                    "revokeSuperuserPolicy" -> rootManager.revokeSuperuserPolicy(args.optString(0, ""))
                    "toggleModule" -> toggleModule(
                        args.optString(0, ""), args.opt(1)?.toString() ?: "false")
                    "deleteModule" -> deleteModule(args.optString(0, ""))
                    "refreshInstalledModules" -> { ModuleManager.invalidateCache(); okJson("模块缓存已刷新") }
                    "getInstalledModulesAsync" -> {
                        getInstalledModulesAsync(args.optString(0, "false"))
                        okJson("")
                    }
                    "pickModuleFile" -> moduleManager.pickModuleFile()
                    "rebootDevice" -> { rootManager.rebootDevice(); okJson("正在重启设备") }
                    else -> errorJson("未知方法: $methodName")
                }
            } catch (e: Exception) {
                errorJson("调用异常: ${e.message}")
            }
            postInvokeResult(callbackId, result)
        }
    }

    fun shutdown() { backendExecutor.shutdownNow() }

    fun installModuleFromZip(zipPath: String) { moduleManager.installModuleFromZip(zipPath) }

    internal fun getServerUrl(): String = prefs.getString("server_url", "") ?: ""

    internal fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (!id.isNullOrBlank()) return id
        id = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)
        if (id.isNullOrBlank()) id = "android-" + System.currentTimeMillis().toString(36)
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    @JavascriptInterface
    fun registerDevice() {
        runOnBackend {
            val json = JSONObject()
            try {
                val deviceId = getDeviceId()
                val serverUrl = getServerUrl()
                if (serverUrl.isBlank()) {
                    json.put("ok", false); json.put("message", "请先设置服务器地址")
                    activity.evaluateJs("if(window.onDeviceRegistered) window.onDeviceRegistered($json);")
                    return@runOnBackend
                }
                val url = java.net.URL("$serverUrl/register/?deviceid=$deviceId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val resp = JSONObject(body)
                if (resp.optBoolean("ok", false)) {
                    prefs.edit().putString("device_id", deviceId).putString("accesskey", resp.getString("accesskey")).apply()
                    json.put("ok", true); json.put("deviceid", deviceId)
                    json.put("accesskey", resp.getString("accesskey")); json.put("serverUrl", serverUrl)
                } else {
                    json.put("ok", false); json.put("message", resp.optString("message", "注册失败"))
                }
            } catch (e: Exception) {
                json.put("ok", false); json.put("message", "无法连接服务器: ${e.message}")
            }
            activity.evaluateJs("if(window.onDeviceRegistered) window.onDeviceRegistered($json);")
        }
    }

    @JavascriptInterface
    fun getInstalledModulesAsync(forceRefresh: String = "false") {
        runOnBackend {
            val json = JSONObject()
            try {
                val force = forceRefresh == "true" || forceRefresh == "1"
                val modules = ModuleManager.listModules(force)
                val arr = JSONArray()
                for (m in modules) {
                    val obj = JSONObject()
                    obj.put("id", m.id); obj.put("name", m.name)
                    obj.put("version", m.version); obj.put("author", m.author)
                    obj.put("description", m.description); obj.put("enabled", m.enabled)
                    obj.put("icon", m.icon)
                    arr.put(obj)
                }
                json.put("ok", true); json.put("modules", arr)
            } catch (e: Exception) {
                json.put("ok", false); json.put("message", e.message ?: "unknown")
            }
            activity.evaluateJs("window.onModulesLoaded($json);")
        }
    }

    @JavascriptInterface
    fun toggleModule(id: String, enable: String): String {
        val json = JSONObject()
        return try {
            val enabled = enable.toBooleanStrictOrNull() ?: (enable == "true" || enable == "1")
            val ok = ModuleManager.toggleModule(id, enabled)
            json.put("ok", ok); json.put("message", if (ok) {
                if (enabled) "模块 $id 已启用，重启后生效" else "模块 $id 已禁用，重启后生效"
            } else { "操作失败" })
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false); json.put("message", e.message ?: "unknown"); json.toString()
        }
    }

    @JavascriptInterface
    fun deleteModule(id: String): String {
        val json = JSONObject()
        return try {
            val ok = ModuleManager.deleteModule(id)
            json.put("ok", ok); json.put("message", if (ok) "模块 $id 已删除，重启后生效" else "删除失败")
            json.toString()
        } catch (e: Exception) {
            json.put("ok", false); json.put("message", e.message ?: "unknown"); json.toString()
        }
    }
}
