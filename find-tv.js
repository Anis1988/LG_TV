// Finds LG webOS TVs on the local network.
// Uses SSDP discovery plus a quick port check (3000/3001 = webOS remote API).
const dgram = require("dgram");
const net = require("net");

const subnet = process.argv[2] || "192.168.50";
const found = new Map();

function ssdp() {
  return new Promise((resolve) => {
    const sock = dgram.createSocket("udp4");
    const msg = Buffer.from(
      "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"
    );
    sock.on("message", (data, rinfo) => {
      const text = data.toString();
      if (/webos|LG/i.test(text)) found.set(rinfo.address, (found.get(rinfo.address) || "") + " ssdp");
    });
    sock.bind(() => sock.send(msg, 1900, "239.255.255.250"));
    setTimeout(() => { sock.close(); resolve(); }, 3000);
  });
}

function probe(host, port) {
  return new Promise((resolve) => {
    const s = net.connect({ host, port, timeout: 800 });
    s.on("connect", () => { s.destroy(); resolve(true); });
    s.on("timeout", () => { s.destroy(); resolve(false); });
    s.on("error", () => resolve(false));
  });
}

async function portScan() {
  const jobs = [];
  for (let i = 1; i < 255; i++) {
    const host = `${subnet}.${i}`;
    for (const port of [3000, 3001]) {
      jobs.push(probe(host, port).then((ok) => {
        if (ok) found.set(host, (found.get(host) || "") + ` port${port}`);
      }));
    }
  }
  await Promise.all(jobs);
}

(async () => {
  console.log(`Searching ${subnet}.0/24 for LG webOS TVs...`);
  await Promise.all([ssdp(), portScan()]);
  if (!found.size) console.log("No webOS TV found (is it on, or set to 'Turn on via Wi-Fi'?).");
  for (const [ip, how] of found) console.log(`Possible TV: ${ip} (${how.trim()})`);
})();
