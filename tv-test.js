// Connects to an LG webOS TV, pairs (first run shows a prompt on the TV),
// then shows a pop-up message and prints basic TV info.
// Usage: node tv-test.js [tv-ip] ["message"]
const fs = require("fs");
const path = require("path");

// webOS uses a self-signed certificate on its secure port.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const ip = process.argv[2] || "192.168.50.96";
const message = process.argv[3] || "Hello from Claude 👋";
const keyFile = path.join(__dirname, "tv-key.json");
const savedKey = fs.existsSync(keyFile) ? JSON.parse(fs.readFileSync(keyFile, "utf8")).key : undefined;

const manifest = require("./manifest");

function connect(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const timer = setTimeout(() => { ws.close(); reject(new Error("timeout")); }, 5000);
    ws.onopen = () => { clearTimeout(timer); resolve(ws); };
    ws.onerror = () => { clearTimeout(timer); reject(new Error("connection failed")); };
  });
}

(async () => {
  let ws;
  for (const url of [`wss://${ip}:3001`, `ws://${ip}:3000`]) {
    try { ws = await connect(url); console.log(`Connected to ${url}`); break; }
    catch (e) { console.log(`${url}: ${e.message}`); }
  }
  if (!ws) { console.log("Could not reach the TV. Is it switched on?"); process.exit(1); }

  let nextId = 0;
  const pending = new Map();
  const request = (uri, payload = {}) => new Promise((resolve) => {
    const id = `req_${nextId++}`;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, type: "request", uri, payload }));
  });

  ws.onmessage = async (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id === "register") {
      if (msg.payload?.pairingType === "PROMPT" && msg.type === "response") {
        console.log("Check your TV: accept the connection prompt with your remote...");
        return;
      }
      if (msg.type === "registered") {
        fs.writeFileSync(keyFile, JSON.stringify({ ip, key: msg.payload["client-key"] }, null, 2));
        console.log("Paired.");
        const info = await request("ssap://com.webos.service.update/getCurrentSWInformation");
        console.log(`Model: ${info.payload.model_name || "?"}  webOS firmware: ${info.payload.product_name || "?"} ${info.payload.major_ver || ""}.${info.payload.minor_ver || ""}`);
        const app = await request("ssap://com.webos.applicationManager/getForegroundAppInfo");
        console.log(`Currently open app: ${app.payload.appId}`);
        await request("ssap://system.notifications/createToast", { message });
        console.log(`Showed message on TV: "${message}"`);
        ws.close();
        return;
      }
      if (msg.type === "error") { console.log(`Pairing failed: ${msg.error}`); ws.close(); return; }
    }
    const resolve = pending.get(msg.id);
    if (resolve) { pending.delete(msg.id); resolve(msg); }
  };

  ws.send(JSON.stringify({
    id: "register",
    type: "register",
    payload: { forcePairing: false, pairingType: "PROMPT", "client-key": savedKey, manifest },
  }));
})();
