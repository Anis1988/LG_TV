// Local web remote for an LG webOS TV.
// Keeps one connection to the TV and serves the dashboard at http://localhost:8787
// Usage: node server.js
const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const dgram = require("dgram");
const manifest = require("./manifest");

// webOS uses a self-signed certificate on its secure port.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const PORT = 8787;
const keyFile = path.join(__dirname, "tv-key.json");
const config = JSON.parse(fs.readFileSync(keyFile, "utf8"));
const TV_IP = config.ip;
const TV_MAC = config.mac; // for Wake-on-LAN

let ws = null;
let ready = false;
let pointer = null;
let nextId = 0;
const pending = new Map();

function connectTV() {
  ready = false;
  ws = new WebSocket(`wss://${TV_IP}:3001`);
  ws.onopen = () => ws.send(JSON.stringify({
    id: "register",
    type: "register",
    payload: { forcePairing: false, pairingType: "PROMPT", "client-key": config.key, manifest },
  }));
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id === "register") {
      if (msg.payload?.pairingType === "PROMPT" && msg.type === "response") {
        console.log("Check your TV: accept the connection prompt with your remote...");
      }
      if (msg.type === "registered") {
        ready = true;
        console.log("TV connected.");
        if (msg.payload["client-key"] !== config.key) {
          config.key = msg.payload["client-key"];
          fs.writeFileSync(keyFile, JSON.stringify(config, null, 2));
        }
      }
      return;
    }
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    if (msg.type === "error") p.reject(new Error(msg.error));
    else p.resolve(msg.payload || {});
  };
  ws.onerror = () => {};
  ws.onclose = () => {
    if (ready) console.log("TV disconnected. Retrying...");
    ready = false;
    pointer = null;
    setTimeout(connectTV, 3000);
  };
}

function request(uri, payload = {}) {
  if (!ready) return Promise.reject(new Error("TV is not connected"));
  return new Promise((resolve, reject) => {
    const id = `req_${nextId++}`;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, type: "request", uri, payload }));
    setTimeout(() => {
      if (pending.delete(id)) reject(new Error("TV did not answer"));
    }, 6000);
  });
}

// Remote buttons (arrows, OK, back, home...) go through a separate pointer socket.
async function pressButton(name) {
  if (!pointer || pointer.readyState !== WebSocket.OPEN) {
    const { socketPath } = await request("ssap://com.webos.service.networkinput/getPointerInputSocket");
    pointer = await new Promise((resolve, reject) => {
      const s = new WebSocket(socketPath);
      s.onopen = () => resolve(s);
      s.onerror = () => reject(new Error("Could not open button channel"));
    });
  }
  pointer.send(`type:button\nname:${name}\n\n`);
}

function wakeOnLan() {
  if (!TV_MAC) throw new Error('Add "mac" to tv-key.json to use Wake');
  const mac = Buffer.from(TV_MAC.replace(/[^0-9a-f]/gi, ""), "hex");
  const packet = Buffer.concat([Buffer.alloc(6, 0xff), ...Array(16).fill(mac)]);
  const sock = dgram.createSocket("udp4");
  sock.bind(() => {
    sock.setBroadcast(true);
    const subnetBroadcast = TV_IP.replace(/\.\d+$/, ".255");
    sock.send(packet, 9, subnetBroadcast, () => sock.send(packet, 9, "255.255.255.255", () => sock.close()));
  });
}

async function status() {
  if (!ready) return { connected: false };
  const [sys, vol, app] = await Promise.allSettled([
    request("ssap://system/getSystemInfo"),
    request("ssap://audio/getVolume"),
    request("ssap://com.webos.applicationManager/getForegroundAppInfo"),
  ]);
  const v = vol.value || {};
  return {
    connected: true,
    model: sys.value?.modelName,
    volume: v.volumeStatus?.volume ?? v.volume,
    muted: v.volumeStatus?.muteStatus ?? v.muted,
    appId: app.value?.appId,
  };
}

const simple = {
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
  screenOn: "ssap://com.webos.service.tvpower/power/turnOnScreen",
};

async function runAction(action, body) {
  if (simple[action]) return request(simple[action], action.startsWith("screen") ? { standbyMode: "active" } : {});
  switch (action) {
    case "status": return status();
    case "powerOn": wakeOnLan(); return { sent: true };
    case "mute": return request("ssap://audio/setMute", { mute: !!body.mute });
    case "setVolume": return request("ssap://audio/setVolume", { volume: Math.max(0, Math.min(100, +body.volume)) });
    case "button": return pressButton(String(body.name).toUpperCase()).then(() => ({}));
    case "toast": return request("ssap://system.notifications/createToast", { message: String(body.message).slice(0, 200) });
    case "launch": return request("ssap://system.launcher/launch", { id: body.id });
    case "switchInput": return request("ssap://tv/switchInput", { inputId: body.inputId });
    case "apps": {
      const { launchPoints = [] } = await request("ssap://com.webos.applicationManager/listLaunchPoints");
      return launchPoints.map((a) => ({ id: a.id, title: a.title, icon: a.largeIcon || a.icon, bgColor: a.bgColor }));
    }
    case "inputs": {
      const { devices = [] } = await request("ssap://tv/getExternalInputList");
      return devices.map((d) => ({ id: d.id, label: d.label, connected: d.connected }));
    }
    default: throw new Error(`Unknown action: ${action}`);
  }
}

// App icons live on the TV; only proxy URLs that point at the TV itself.
function proxyIcon(url, res) {
  let target;
  try { target = new URL(url); } catch { res.writeHead(400).end(); return; }
  if (target.hostname !== TV_IP || !["http:", "https:"].includes(target.protocol)) { res.writeHead(403).end(); return; }
  (target.protocol === "https:" ? https : http).get(target, (up) => {
    res.writeHead(up.statusCode, { "Content-Type": up.headers["content-type"] || "image/png", "Cache-Control": "max-age=86400" });
    up.pipe(res);
  }).on("error", () => res.writeHead(502).end());
}

http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (req.method === "GET" && url.pathname === "/") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    fs.createReadStream(path.join(__dirname, "public", "index.html")).pipe(res);
    return;
  }
  if (req.method === "GET" && url.pathname === "/icon") return proxyIcon(url.searchParams.get("url"), res);
  if (req.method === "POST" && url.pathname.startsWith("/api/")) {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", async () => {
      let body = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch {}
      try {
        const result = await runAction(url.pathname.slice(5), body);
        res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: true, result: result ?? {} }));
      } catch (e) {
        res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: false, error: e.message }));
      }
    });
    return;
  }
  res.writeHead(404).end();
}).listen(PORT, "127.0.0.1", () => console.log(`Remote ready: http://localhost:${PORT}`));

connectTV();
