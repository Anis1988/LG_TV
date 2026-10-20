// LG webOS client that runs inside the phone app.
// The TV only speaks secure WebSocket with a self-signed cert, so the actual
// sockets live in the native TvSocket plugin; this file speaks the webOS protocol.
const Native = window.capacitorExports.registerPlugin("TvSocket");

const MANIFEST = {
  manifestVersion: 1,
  appVersion: "1.0",
  signed: {
    appId: "com.claude.tvremote",
    vendorId: "com.claude",
    created: "20261020",
    localizedAppNames: { "": "TV Remote" },
    localizedVendorNames: { "": "Claude" },
    permissions: ["CONTROL_POWER", "READ_INSTALLED_APPS", "LAUNCH", "CONTROL_AUDIO",
      "CONTROL_DISPLAY", "CONTROL_INPUT_TV", "READ_TV_CURRENT_TIME", "READ_CURRENT_CHANNEL"],
    serial: "tvremote0001",
  },
  permissions: ["LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_DISPLAY",
    "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER",
    "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS",
    "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "READ_POWER_STATE", "READ_COUNTRY_INFO",
    "WRITE_NOTIFICATION_TOAST", "READ_NETWORK_STATE", "READ_UPDATE_INFO",
    "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_TV_SCREEN", "READ_NOTIFICATIONS",
    "WRITE_NOTIFICATION_ALERT", "CONTROL_INPUT_TEXT"],
  signatures: [{ signatureVersion: 1, signature: "none" }],
};

const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch {} },
};

const TV = {
  ip: store.get("tvIp") || "",
  ready: false,
  pairing: false,
  // "unknown" | "on" | "screen_off" | "off" (standby) | "unreachable"
  power: "unknown",
  onChange: () => {},
  onPrompt: () => {},
  onPairFail: () => {},
  _pending: new Map(),
  _next: 0,
  _pointerOpen: false,
  _retry: null,

  async start(ip) {
    if (ip) { this.ip = ip; store.set("tvIp", ip); }
    clearTimeout(this._retry);
    if (!this.ip) return;
    this.ready = false;
    this.onChange();
    try {
      await Native.open({ id: "main", url: `wss://${this.ip}:3001` });
      Native.send({ id: "main", data: JSON.stringify({
        id: "register",
        type: "register",
        payload: { forcePairing: false, pairingType: "PROMPT", "client-key": store.get("tvKey") || undefined, manifest: MANIFEST },
      }) });
      // A TV in standby accepts the socket but never answers registration: that means "off".
      clearTimeout(this._regTimer);
      this._regTimer = setTimeout(() => {
        if (this.ready || this.pairing) return;
        this.setPower("off");
        this.onChange();
        Native.close({ id: "main" }).catch(() => {});
        this._retry = setTimeout(() => this.start(), 5000);
      }, 3500);
    } catch {
      this.setPower("unreachable");
      this.onChange();
      this._retry = setTimeout(() => this.start(), 4000);
    }
  },

  setPower(p) {
    if (p === this.power) return;
    this.power = p;
    Native.updateWidget({ state: p }).catch(() => {});
  },

  _onMessage({ id, data }) {
    if (id !== "main") return;
    const msg = JSON.parse(data);
    if (msg.id === "register") {
      if (msg.payload?.pairingType === "PROMPT" && msg.type === "response") {
        this.pairing = true;
        this.onPrompt();
      }
      if (msg.type === "error") {
        this.pairing = false;
        this.onPairFail(msg.error);
      }
      if (msg.type === "registered") {
        store.set("tvKey", msg.payload["client-key"]);
        clearTimeout(this._regTimer);
        this.pairing = false;
        this.ready = true;
        this.setPower("on");
        this._syncNative();
        this.onChange();
        this._rememberMac();
      }
      return;
    }
    const p = this._pending.get(msg.id);
    if (!p) return;
    this._pending.delete(msg.id);
    if (msg.type === "error") p.reject(new Error(msg.error));
    else p.resolve(msg.payload || {});
  },

  _onClosed({ id }) {
    if (id === "pointer") { this._pointerOpen = false; return; }
    if (id !== "main") return;
    this.ready = false;
    this._pointerOpen = false;
    this.onChange();
    clearTimeout(this._retry);
    this._retry = setTimeout(() => this.start(), 3000);
  },

  request(uri, payload = {}) {
    if (!this.ready) return Promise.reject(new Error("TV is not connected"));
    return new Promise((resolve, reject) => {
      const id = `req_${this._next++}`;
      this._pending.set(id, { resolve, reject });
      Native.send({ id: "main", data: JSON.stringify({ id, type: "request", uri, payload }) }).catch(reject);
      setTimeout(() => { if (this._pending.delete(id)) reject(new Error("TV did not answer")); }, 6000);
    });
  },

  // Remote buttons go through a separate pointer socket.
  async button(name) {
    if (!this._pointerOpen) {
      // Share one open attempt between quick repeated taps.
      this._pointerOpening ??= (async () => {
        const { socketPath } = await this.request("ssap://com.webos.service.networkinput/getPointerInputSocket");
        await Native.open({ id: "pointer", url: socketPath });
        this._pointerOpen = true;
      })().finally(() => { this._pointerOpening = null; });
      await this._pointerOpening;
    }
    await Native.send({ id: "pointer", data: `type:button\nname:${name}\n\n` });
  },

  // Save the TV's MAC while it's on, so Wake-on-LAN works when it's off.
  async _rememberMac() {
    try {
      const info = await this.request("ssap://com.webos.service.connectionmanager/getinfo");
      const mac = info.wiredInfo?.macAddress || info.wifiInfo?.macAddress;
      if (mac) { store.set("tvMac", mac); this._syncNative(); }
    } catch {}
  },

  // Status/navigation bar colors follow the app theme, not just the phone's.
  setSystemBars(dark) {
    Native.setSystemBars({ dark: !!dark }).catch(() => {});
  },

  // Phone volume keys go to the TV only while connected.
  setVolumeKeys(enabled) {
    Native.setVolumeKeys({ enabled: !!enabled }).catch(() => {});
  },

  // The home-screen widget reads the TV address and key from native storage.
  _syncNative() {
    Native.saveConfig({
      ip: this.ip,
      key: store.get("tvKey") || "",
      mac: store.get("tvMac") || "",
      manifest: JSON.stringify(MANIFEST),
    }).catch(() => {});
  },

  wake() {
    const mac = store.get("tvMac");
    if (!mac) return Promise.reject(new Error("Connect once while the TV is on first"));
    return Native.wake({ mac, broadcast: this.ip.replace(/\.\d+$/, ".255") });
  },

  async status() {
    if (!this.ready) return { connected: false, power: this.power };
    const [sys, vol, app, pwr] = await Promise.allSettled([
      this.request("ssap://system/getSystemInfo"),
      this.request("ssap://audio/getVolume"),
      this.request("ssap://com.webos.applicationManager/getForegroundAppInfo"),
      this.request("ssap://com.webos.service.tvpower/power/getPowerState"),
    ]);
    const v = vol.value || {};
    const ps = pwr.value?.state || "";
    this.setPower(/screen ?off|active standby/i.test(ps) ? "screen_off" : /suspend|power ?off|standby/i.test(ps) ? "off" : "on");
    return {
      connected: true,
      power: this.power,
      model: sys.value?.modelName,
      volume: v.volumeStatus?.volume ?? v.volume,
      muted: v.volumeStatus?.muteStatus ?? v.muted,
      appId: app.value?.appId,
    };
  },

  async apps() {
    const { launchPoints = [] } = await this.request("ssap://com.webos.applicationManager/listLaunchPoints");
    return launchPoints.map((a) => ({ id: a.id, title: a.title, icon: a.largeIcon || a.icon, bgColor: a.bgColor }));
  },

  async inputs() {
    const { devices = [] } = await this.request("ssap://tv/getExternalInputList");
    return devices.map((d) => ({ id: d.id, label: d.label, connected: d.connected, appId: d.appId }));
  },

  // The TV chokes on many parallel downloads, so fetch icons a few at a time.
  _iconQueue: [],
  _iconActive: 0,
  icon(url) {
    return new Promise((resolve, reject) => {
      this._iconQueue.push({ url, resolve, reject, tries: 0 });
      this._pumpIcons();
    });
  },
  _pumpIcons() {
    while (this._iconActive < 4 && this._iconQueue.length) {
      const job = this._iconQueue.shift();
      this._iconActive++;
      Native.fetchImage({ url: job.url })
        .then(({ dataUrl }) => job.resolve(dataUrl))
        .catch((e) => {
          if (job.tries++ < 1) this._iconQueue.push(job);
          else job.reject(e);
        })
        .finally(() => { this._iconActive--; this._pumpIcons(); });
    }
  },

  async scan() {
    const { prefix } = await Native.localPrefix();
    const { hosts } = await Native.scan({ prefix, port: 3001 });
    return hosts;
  },
};

const SIMPLE = {
  volumeUp: "ssap://audio/volumeUp",
  volumeDown: "ssap://audio/volumeDown",
  channelUp: "ssap://tv/channelUp",
  channelDown: "ssap://tv/channelDown",
  play: "ssap://media.controls/play",
  pause: "ssap://media.controls/pause",
  stop: "ssap://media.controls/stop",
  rewind: "ssap://media.controls/rewind",
  fastForward: "ssap://media.controls/fastForward",
  powerOff: "ssap://system/turnOff",
  screenOff: "ssap://com.webos.service.tvpower/power/turnOffScreen",
};

TV.run = function (action, body = {}) {
  if (SIMPLE[action]) return this.request(SIMPLE[action], action === "screenOff" ? { standbyMode: "active" } : {});
  switch (action) {
    case "powerOn": return this.wake();
    case "mute": return this.request("ssap://audio/setMute", { mute: !!body.mute });
    case "setVolume": return this.request("ssap://audio/setVolume", { volume: Math.max(0, Math.min(100, +body.volume)) });
    case "button": return this.button(String(body.name).toUpperCase());
    case "toast": return this.request("ssap://system.notifications/createToast", { message: String(body.message).slice(0, 200) });
    case "launch": return this.request("ssap://system.launcher/launch", { id: body.id });
    case "switchInput": return this.request("ssap://tv/switchInput", { inputId: body.inputId });
    default: return Promise.reject(new Error(`Unknown action: ${action}`));
  }
};

TV.onHwKey = () => {};
Native.addListener("message", (e) => TV._onMessage(e));
Native.addListener("closed", (e) => TV._onClosed(e));
Native.addListener("hwkey", (e) => TV.onHwKey(e.key));
window.TV = TV;
