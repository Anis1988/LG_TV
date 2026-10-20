// LG webOS client that runs inside the phone app.
// The TV only speaks secure WebSocket with a self-signed cert, so the actual
// sockets live in the native TvSocket plugin; this file speaks the webOS protocol.
const Native = window.capacitorExports.registerPlugin("TvSocket");

// No "signed" block: with one, the TV only grants the few permissions listed inside it
// (that's why typing and alerts were refused). This is the list open-source LG remotes use.
const MANIFEST = {
  manifestVersion: 1,
  appVersion: "1.1",
  permissions: ["APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
    "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV",
    "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN", "LAUNCH", "LAUNCH_WEBAPP",
    "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
    "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS", "READ_NETWORK_STATE",
    "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS", "READ_SETTINGS", "READ_TV_CHANNEL_LIST",
    "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO", "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE",
    "UPDATE_FROM_REMOTE_APP", "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS"],
};
// Bump when MANIFEST permissions change: a key from an older pairing keeps its old
// permissions, so the app pairs again once (one Accept on the TV).
const PAIR_VERSION = "2";

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
        payload: {
          forcePairing: false,
          pairingType: "PROMPT",
          "client-key": store.get("pairVersion") === PAIR_VERSION ? store.get("tvKey") || undefined : undefined,
          manifest: MANIFEST,
        },
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
    this.pushWidget();
  },

  // Send the current state to the home-screen widget and Quick Settings tile.
  pushWidget() {
    if (this.power === "unknown") return;
    Native.updateWidget({ state: this.power }).catch(() => {});
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
        store.set("pairVersion", PAIR_VERSION);
        clearTimeout(this._regTimer);
        this.pairing = false;
        this.ready = true;
        this.setPower("on");
        this._registerKeyboard();
        this._syncNative();
        this.onChange();
        this._rememberMac();
      }
      return;
    }
    const sub = this._subs.get(msg.id);
    if (sub) { if (msg.type !== "error") sub(msg.payload || {}); return; }
    const p = this._pending.get(msg.id);
    if (!p) return;
    this._pending.delete(msg.id);
    if (msg.type === "error") p.reject(new Error(msg.error));
    else p.resolve(msg.payload || {});
  },

  // Subscriptions: the TV keeps sending updates on the same id.
  _subs: new Map(),
  subscribe(uri, onUpdate) {
    const id = `sub_${this._next++}`;
    this._subs.set(id, onUpdate);
    Native.send({ id: "main", data: JSON.stringify({ id, type: "subscribe", uri }) }).catch(() => {});
  },

  // Registering as the TV's remote keyboard is what lets typed text through, and the TV
  // then tells us whenever a text box gets or loses focus.
  onKeyboardFocus: () => {},
  _registerKeyboard() {
    this._subs.clear();
    this.subscribe("ssap://com.webos.service.ime/registerRemoteKeyboard", (p) => {
      const w = p.currentWidget;
      if (w && typeof w.focus === "boolean") this.onKeyboardFocus(w.focus);
    });
  },

  // Undocumented but widely used: an alert whose close action is an internal (luna) call,
  // closed right away. Gives access to TV services that have no public remote command.
  async luna(uri, params = {}) {
    const alert = await this.request("ssap://system.notifications/createAlert", {
      message: " ",
      buttons: [{ label: "", onClick: uri, params }],
      onclose: { uri, params },
      onfail: { uri, params },
    });
    await this.request("ssap://system.notifications/closeAlert", { alertId: alert.alertId });
  },

  // Connect the TV straight to a Bluetooth audio device it has paired with before.
  async connectBluetooth(address) {
    const a = address.toLowerCase();
    await this.luna("luna://com.webos.service.bluetooth2/a2dp/connect", { address: a });
    await new Promise((r) => setTimeout(r, 2500));
    await this.setSoundOutput("bt_soundbar").catch(() => {});
    // Turning the headphones on makes the TV show its own "connect?" pop-up. We've already
    // connected, so close it with one Back press.
    await new Promise((r) => setTimeout(r, 1200));
    await this.button("BACK").catch(() => {});
  },

  phoneBluetoothDevices() {
    return Native.bluetoothDevices().then((r) => r.devices || []);
  },

  // The TV can keep the socket open after turning off, so don't wait for it to close:
  // drop the connection ourselves and let start() re-detect the state.
  markOff() {
    clearTimeout(this._retry);
    clearTimeout(this._regTimer);
    this.ready = false;
    this._pointerOpen = false;
    this.setPower("off");
    Native.close({ id: "main" }).catch(() => {});
    Native.close({ id: "pointer" }).catch(() => {});
    this.onChange();
    this._retry = setTimeout(() => this.start(), 4000);
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

  // Keyboard: types into whatever text box is focused on the TV.
  insertText(text) {
    return this.request("ssap://com.webos.service.ime/insertText", { text, replace: 0 });
  },
  deleteChars(count) {
    return this.request("ssap://com.webos.service.ime/deleteCharacters", { count });
  },
  sendEnter() {
    return this.request("ssap://com.webos.service.ime/sendEnterKey");
  },

  async foregroundApp() {
    const r = await this.request("ssap://com.webos.applicationManager/getForegroundAppInfo");
    return r.appId || "";
  },

  // YouTube has its own on-screen keyboard that ignores remote typing, but it accepts a
  // search link when started fresh (tested on an OLED C3: a running YouTube ignores it).
  async searchYouTube(query) {
    const id = "youtube.leanback.v4";
    await this.request("ssap://system.launcher/close", { id }).catch(() => {});
    await new Promise((r) => setTimeout(r, 2000));
    return this.request("ssap://system.launcher/launch", {
      id,
      params: { contentTarget: `https://www.youtube.com/tv#/search?q=${encodeURIComponent(query)}` },
    });
  },

  // Sound output: TV speakers, soundbar (ARC/optical), Bluetooth...
  async soundOutput() {
    const r = await this.request("ssap://audio/getSoundOutput");
    return r.soundOutput || "";
  },
  setSoundOutput(output) {
    return this.request("ssap://audio/changeSoundOutput", { output });
  },

  // Sleep timer lives in native code so it fires even with the app closed.
  async sleepEndsAt() {
    const { endsAt } = await Native.getSleepTimer();
    return endsAt || 0;
  },
  async setSleep(minutes) {
    const { endsAt } = await Native.setSleepTimer({ minutes });
    return endsAt;
  },
  cancelSleep() {
    return Native.cancelSleepTimer();
  },

  async appVersion() {
    const { version } = await Native.appInfo();
    return version;
  },

  openUrl(url) {
    return Native.openUrl({ url });
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
    const noAnswer = [sys, vol, app, pwr].every((r) => r.status === "rejected");
    const state = /screen ?off|active standby/i.test(ps) ? "screen_off" : /suspend|power ?off|standby/i.test(ps) ? "off" : "on";
    // Turned off with the real remote: the socket may linger, so check what the TV says.
    if (noAnswer || state === "off") {
      this.markOff();
      return { connected: false, power: this.power };
    }
    this.setPower(state);
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
  screenOff: "ssap://com.webos.service.tvpower/power/turnOffScreen",
};

TV.run = function (action, body = {}) {
  if (SIMPLE[action]) return this.request(SIMPLE[action], action === "screenOff" ? { standbyMode: "active" } : {});
  switch (action) {
    case "powerOff": return this.request("ssap://system/turnOff").then(() => this.markOff());
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
