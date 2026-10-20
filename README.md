# LG TV Remote

Control an LG webOS TV from your PC or Android phone over home Wi-Fi.

## Android app (`app/`)

Standalone phone remote. No PC needed. Phone and TV must be on the same Wi-Fi.

Build (needs Node and Android Studio):

```bash
cd app
npm install
npm run sync
cd android && gradlew assembleDebug
```

APK lands in `app/android/app/build/outputs/apk/debug/app-debug.apk`.

First launch: open **Setup**, tap **Find TV on Wi-Fi** (or type the IP), then accept the prompt on the TV.
For power-on, enable *Settings → General → Devices → TV Management → Turn on via Wi-Fi* on the TV.

## PC dashboard

```bash
node find-tv.js            # find the TV's IP
node tv-test.js <tv-ip>    # pair once, creates tv-key.json
node server.js             # open http://localhost:8787
```

Add `"mac": "AA:BB:CC:DD:EE:FF"` to `tv-key.json` to enable the Wake button.

`tv-key.json` holds the pairing key and is git-ignored.
