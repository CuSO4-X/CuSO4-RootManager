const statusTextEl = document.getElementById("statusText");
const runLogEl = document.getElementById("runLog");
const btnUninstallMagisk = document.getElementById("btnUninstallMagisk");
const btnOverviewSettings = document.getElementById("btnOverviewSettings");
const btnInstallLocal = document.getElementById("btnInstallLocal");
const moduleFileInput = document.getElementById("moduleFileInput");
const modulesContent = document.getElementById("modulesContent");
const suAppsContent = document.getElementById("suAppsContent");
const btnRefreshSuApps = document.getElementById("btnRefreshSuApps");
const suHint = document.getElementById("suHint");
const appRoot = document.querySelector(".app");
const tabIndicator = document.getElementById("tabIndicator");
const tabs = Array.from(document.querySelectorAll(".tab"));
const LOW_PERF_MODE = true;
const prefersReducedMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const PAGE_ANIM_MS = prefersReducedMotion ? 0 : (LOW_PERF_MODE ? 120 : 260);
const MAX_DEBUG_LOGS = 120;
const MAX_FLASH_LOGS = 240;
const MODULE_CACHE_MS = 10000;

const flashScreen = document.getElementById("flashScreen");
const flashTitle = document.getElementById("flashTitle");
const flashConsole = document.getElementById("flashConsole");
const flashBtn = document.getElementById("flashBtn");
const flashBackBtn = document.getElementById("flashBackBtn");

const confirmOverlay = document.getElementById("confirmOverlay");
const confirmTitle = document.getElementById("confirmTitle");
const confirmMessage = document.getElementById("confirmMessage");
const confirmCancel = document.getElementById("confirmCancel");
const confirmConfirm = document.getElementById("confirmConfirm");

const noticeOverlay = document.getElementById("noticeOverlay");
const noticeTitle = document.getElementById("noticeTitle");
const noticeMessage = document.getElementById("noticeMessage");
const noticeOkBtn = document.getElementById("noticeOkBtn");

let confirmCallback = null;
let modules = [];
let modulesLoaded = false;
let modulesLoading = false;
let modulesLastLoadedAt = 0;
let suApps = [];
let currentPageId = document.querySelector(".tab.active")?.dataset.page || "overview";
let uninstallLabel = "卸载 Magisk";

const debugLogs = [];
let flashLogs = [];
const pendingNativeCalls = new Map();
if (LOW_PERF_MODE) {
  document.documentElement.classList.add("lite-ui");
}
window.__CuSO4InvokeCB = (callbackId, result) => {
  const entry = pendingNativeCalls.get(callbackId);
  if (!entry) return;
  pendingNativeCalls.delete(callbackId);
  clearTimeout(entry.timer);
  entry.resolve(result);
};

function showFlashScreen(title) {
  flashTitle.textContent = title;
  flashConsole.textContent = "";
  flashLogs = [];
  flashBtn.disabled = true;
  flashBtn.textContent = "重启";
  flashBackBtn.disabled = true;
  flashScreen.classList.add("active");
}

function addFlashLog(message) {
  flashLogs.push(message);
  if (flashLogs.length > MAX_FLASH_LOGS) {
    flashLogs.splice(0, flashLogs.length - MAX_FLASH_LOGS);
  }
  flashConsole.textContent = flashLogs.join("\n");
  flashConsole.scrollTop = flashConsole.scrollHeight;
}

function finishFlash(success) {
  flashBtn.disabled = false;
  flashBackBtn.disabled = false;
  if (!success) {
    flashTitle.textContent = "操作失败";
  } else {
    flashTitle.textContent = "操作完成";
  }
}

function hideFlashScreen() {
  flashScreen.classList.remove("active");
  flashTitle.textContent = "正在刷入...";
  flashLogs = [];
  flashConsole.textContent = "";
  flashBtn.disabled = true;
  flashBtn.textContent = "重启";
}

function showConfirm(title, message, cb) {
  confirmTitle.textContent = title;
  confirmMessage.textContent = message;
  confirmCallback = cb;
  confirmOverlay.classList.add("active");
}

function hideConfirm() {
  confirmOverlay.classList.remove("active");
  confirmCallback = null;
}

function showNotice(message, title) {
  noticeTitle.textContent = title || "提示";
  noticeMessage.textContent = message;
  noticeOverlay.classList.add("active");
}

function hideNotice() {
  noticeOverlay.classList.remove("active");
}

function esc(str) {
  return String(str || "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function toObject(v) {
  if (v == null) return {};
  if (typeof v === "object") return v;
  try { return JSON.parse(String(v)); } catch (_) { return {}; }
}

async function invoke(methodName, ...args) {
  const bridgeHost = window.CuSO4Bridge;
  if (!bridgeHost) return { ok: false, message: "未连接原生桥接" };
  if (typeof bridgeHost.invokeAsync === "function") {
    const callbackId = `${Date.now()}_${Math.random().toString(16).slice(2)}`;
    try {
      const rawResult = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { pendingNativeCalls.delete(callbackId); reject(new Error("Call timeout")); }, 15000);
        pendingNativeCalls.set(callbackId, { resolve, reject, timer });
        bridgeHost.invokeAsync(methodName, JSON.stringify(args), callbackId);
      });
      return toObject(rawResult);
    } catch (error) { return { ok: false, message: "调用失败: " + error }; }
  }
  if (typeof bridgeHost[methodName] !== "function") return { ok: false, message: "未连接原生桥接" };
  try {
    const timeoutPromise = new Promise((_, reject) => setTimeout(() => reject(new Error("Call timeout")), 15000));
    const callPromise = new Promise((resolve) => resolve(bridgeHost[methodName](...args)));
    const rawResult = await Promise.race([callPromise, timeoutPromise]);
    return toObject(rawResult);
  } catch (error) { return { ok: false, message: "调用失败: " + error }; }
}

function debugLog(msg, type) {
  if (typeof msg === "object") msg = JSON.stringify(msg);
  const ts = new Date().toISOString().slice(11, 19);
  debugLogs.push(`[${ts}] ${msg}`);
  if (debugLogs.length > MAX_DEBUG_LOGS) debugLogs.shift();
  if (runLogEl) runLogEl.textContent = debugLogs.slice(-40).join("\n");
}

async function loadRootState() {
  const result = await invoke("getRootState");
  if (result.ok) {
    statusTextEl.textContent = `${result.manager || "未知"} ${result.magiskVersion || result.managerVersion || ""}`;
    var lines;
    if (result.manager === "Magisk") {
      var kernelVer = result.kernelVersion || "未知";
      var magiskVer = result.managerVersion || result.magiskVersion || "";
      lines = [
        `当前: ${kernelVer}（${magiskVer}）`,
        `Zgisk: ${result.zygisk ? "是" : "否"}`,
        `Ramdisk: ${result.ramdisk ? "是" : "否"}`
      ];
    } else {
      lines = [
        `管理器: ${result.manager || "未知"}  ${result.magiskVersion || result.managerVersion || ""}`,
        `Hook 类型: ${result.hookType || "未知"}`,
        `SELinux: ${result.selinuxMode || "未知"}`,
        `内核: ${result.kernelVersion || "未知"}`,
        `指纹: ${result.systemFingerprint || "未知"}`
      ];
    }
    runLogEl.innerHTML = lines.map(function(l) { return '<div class="ksu-info-item">' + esc(l) + '</div>'; }).join("");
    if (result.manager === "Magisk") uninstallLabel = "卸载 Magisk";
    else uninstallLabel = "卸载 ROOT 管理器";
  } else {
    statusTextEl.textContent = "未授权";
    runLogEl.innerHTML = `<div class="ksu-info-item">ROOT 状态: 未授权</div>`;
  }
}

async function loadModules(forceRefresh) {
  if (modulesLoading) return;
  modulesLoading = true;
  try {
    const result = await invoke("getInstalledModulesAsync", forceRefresh ? "true" : "false");
    if (result.ok && Array.isArray(result.modules)) {
      modules = result.modules;
      modulesLoaded = true;
      modulesLastLoadedAt = Date.now();
      renderModules();
    }
  } catch (_) {} finally { modulesLoading = false; }
}

function renderModules() {
  if (!modulesContent) return;
  if (!modules.length) {
    modulesContent.innerHTML = `<div class="empty-state">暂无模块</div>`;
    return;
  }
  modulesContent.innerHTML = modules.map(m => `
    <div class="module-card anim-item">
      <div class="module-info">
        <div class="module-name">${esc(m.name || m.id)}</div>
        <div class="module-meta">${esc(m.version || "")} · ${esc(m.author || "")}</div>
        ${m.description ? `<div class="module-desc">${esc(m.description)}</div>` : ""}
      </div>
      <div class="module-actions">
        <button class="module-toggle-btn" data-id="${esc(m.id)}" data-enabled="${m.enabled}">${m.enabled ? "禁用" : "启用"}</button>
        <button class="module-delete-btn" data-id="${esc(m.id)}">删除</button>
      </div>
    </div>
  `).join("");

  modulesContent.querySelectorAll(".module-toggle-btn").forEach(btn => {
    btn.addEventListener("click", async () => {
      const id = btn.dataset.id;
      const enabled = btn.dataset.enabled === "true";
      showFlashScreen(enabled ? "禁用模块..." : "启用模块...");
      addFlashLog(`- ${enabled ? "禁用" : "启用"}模块: ${id}`);
      const result = await invoke("toggleModule", id, String(!enabled));
      if (result.ok) {
        addFlashLog(`+ ${result.message}`);
        finishFlash(true);
        modulesLoaded = false;
        loadModules(true);
      } else {
        addFlashLog(`! ${result.message}`);
        finishFlash(false);
      }
    });
  });

  modulesContent.querySelectorAll(".module-delete-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      const id = btn.dataset.id;
      showConfirm("删除模块", `确定要删除模块 ${id} 吗？`, async () => {
        showFlashScreen("删除模块...");
        addFlashLog(`- 删除模块: ${id}`);
        const result = await invoke("deleteModule", id);
        if (result.ok) {
          addFlashLog(`+ ${result.message}`);
          finishFlash(true);
          modulesLoaded = false;
          loadModules(true);
        } else {
          addFlashLog(`! ${result.message}`);
          finishFlash(false);
        }
      });
    });
  });
}

async function loadSuperuserApps() {
  const result = await invoke("getSuperuserApps");
  if (!result.ok || !suAppsContent) return;
  suApps = result.apps || [];
  if (!suApps.length) {
    suAppsContent.innerHTML = `<div class="empty-state">无授权的应用</div>`;
    return;
  }
  suAppsContent.innerHTML = suApps.map(a => `
    <div class="su-app-card">
      <div class="su-app-info">
        <div class="su-app-name">${esc(a.packageName || a.appName)}</div>
        <div class="su-app-policy">${esc(a.policy == 1 ? "允许" : a.policy == 0 ? "拒绝" : "询问")}</div>
      </div>
      <div class="su-app-actions">
        <button class="su-policy-btn" data-pkg="${esc(a.packageName)}" data-policy="allow">允许</button>
        <button class="su-policy-btn" data-pkg="${esc(a.packageName)}" data-policy="deny">拒绝</button>
        <button class="su-policy-btn" data-pkg="${esc(a.packageName)}" data-policy="ask">询问</button>
        <button class="su-revoke-btn" data-pkg="${esc(a.packageName)}">撤销</button>
      </div>
    </div>
  `).join("");

  suAppsContent.querySelectorAll(".su-policy-btn").forEach(btn => {
    btn.addEventListener("click", async () => {
      await invoke("setSuperuserPolicy", btn.dataset.pkg, btn.dataset.policy);
      loadSuperuserApps();
    });
  });
  suAppsContent.querySelectorAll(".su-revoke-btn").forEach(btn => {
    btn.addEventListener("click", async () => {
      await invoke("revokeSuperuserPolicy", btn.dataset.pkg);
      loadSuperuserApps();
    });
  });
}

function applyStaggerDelays(container, selector) {
  if (!container) return;
  container.querySelectorAll(selector).forEach((el, i) => {
    el.style.animationDelay = `${i * 40}ms`;
  });
}

function checkHasPerf(media) {
  try { return window.matchMedia(media).matches; } catch (_) { return false; }
}

function getNavState(overrides) {
  return { pageId: (overrides.pageId || currentPageId), marketOpen: false };
}

function normalizeNavState(state) {
  return { pageId: state?.pageId || "overview", marketOpen: false };
}

function sameNavState(a, b) {
  return a.pageId === b.pageId && a.marketOpen === b.marketOpen;
}

function pushNavState(overrides) {
  const nextState = normalizeNavState(getNavState(overrides));
  const currentState = normalizeNavState(history.state);
  if (sameNavState(nextState, currentState)) return;
  history.pushState(nextState, "");
}

function replaceNavState(overrides) {
  history.replaceState(normalizeNavState(getNavState(overrides)), "");
}

function handlePageActivated(pageId) {
  if (pageId === "modules") loadModules();
}

function switchPage(pageId) {
  if (pageId === currentPageId) return;
  if (pageId !== "overview" && pageId !== "modules") return;
  document.querySelectorAll(".content.page").forEach(el => el.hidden = true);
  const pageEl = document.getElementById("page-" + pageId);
  if (pageEl) pageEl.hidden = false;
  tabs.forEach(t => t.classList.toggle("active", t.dataset.page === pageId));
  updateTabIndicator();
  currentPageId = pageId;
  pushNavState({ pageId });
  handlePageActivated(pageId);
}

function updateTabIndicator() {
  if (!tabIndicator) return;
  const activeTab = document.querySelector(".tab.active");
  if (!activeTab) return;
  const nav = activeTab.closest(".bottom-nav");
  if (!nav) return;
  tabIndicator.style.width = `${activeTab.offsetWidth}px`;
  tabIndicator.style.transform = `translateX(${activeTab.offsetLeft}px)`;
}

window.onModuleInstallProgress = (payload) => {
  const data = toObject(payload);
  addFlashLog(data.message || String(payload));
};

window.onModuleInstallResult = (result) => {
  if (result.ok) {
    addFlashLog("+ " + (result.message || "安装完成"));
    finishFlash(true);
    modulesLoaded = false;
    loadModules(true);
  } else {
    addFlashLog("! " + (result.message || "安装失败"));
    finishFlash(false);
  }
};

window.onModulesLoaded = (payload) => {
  const data = toObject(payload);
  if (data.ok && Array.isArray(data.modules)) {
    modules = data.modules;
    modulesLoaded = true;
    renderModules();
  }
};

window.onDeviceRegistered = (payload) => {
  const data = toObject(payload);
  debugLog("设备注册: " + (data.ok ? "成功" : data.message));
};

function openFilePicker() {
  if (moduleFileInput) moduleFileInput.click();
}

if (moduleFileInput) {
  moduleFileInput.addEventListener("change", (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    showFlashScreen("安装模块");
    addFlashLog("- 文件: " + file.name);
    addFlashLog("- 大小: " + (file.size / 1024).toFixed(1) + " KB");
    invoke("pickModuleFile");
    moduleFileInput.value = "";
  });
}

flashBackBtn.addEventListener("click", () => hideFlashScreen());
flashBtn.addEventListener("click", () => {
  if (flashBtn.textContent === "返回") hideFlashScreen();
  else if (flashBtn.textContent === "重启") invoke("rebootDevice");
});

confirmCancel.addEventListener("click", hideConfirm);
confirmConfirm.addEventListener("click", () => {
  if (confirmCallback) confirmCallback();
  hideConfirm();
});

tabs.forEach(tab => {
  tab.addEventListener("click", () => {
    const pageId = tab.dataset.page;
    if (!pageId) return;
    switchPage(pageId);
  });
});

if (btnRefreshSuApps) btnRefreshSuApps.addEventListener("click", () => loadSuperuserApps());

if (btnUninstallMagisk) {
  btnUninstallMagisk.addEventListener("click", () => {
    showConfirm("卸载 Magisk", "确定要卸载 Magisk 吗？卸载后需要重启设备。", async () => {
      const result = await invoke("uninstallMagisk");
      showNotice(result.message || (result.ok ? "卸载完成" : "卸载失败"), "卸载 Magisk");
    });
  });
}

if (btnInstallLocal) {
  btnInstallLocal.addEventListener("click", () => openFilePicker());
}

if (btnOverviewSettings) {
  btnOverviewSettings.addEventListener("click", () => {
    showNotice("请在模块页面 → 设置中配置服务器地址", "设置");
  });
}

if (noticeOkBtn) {
  noticeOkBtn.addEventListener("click", () => hideNotice());
}

if (noticeOverlay) {
  noticeOverlay.addEventListener("click", (event) => {
    if (event.target === noticeOverlay) hideNotice();
  });
}

window.addEventListener("popstate", (event) => {
  const state = normalizeNavState(event.state);
  if (state.pageId !== currentPageId) {
    switchPage(state.pageId);
  }
});

loadRootState();
loadSuperuserApps();
