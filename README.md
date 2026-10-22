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

### Features

- **Remote:** glass UI with the D-pad in thumb reach, Swipe and Mouse (touchpad pointer) modes, hold-to-repeat,
  phone volume keys, favorite apps row (up to 5), apps list, inputs
- **Power:** ON/OFF switch with on / screen-off / off / unreachable detection; Wake-on-LAN
- **Night:** sleep timer, daily auto turn-off, bedtime reminder, night volume limit
- **Sound & picture:** sound output (TV speakers, ARC, optical, Bluetooth headphones with one-tap connect),
  picture modes and Eye comfort
- **Keyboard:** live typing into the TV's own text boxes; YouTube search via a search link
  (Netflix/Crunchyroll use their own keyboards and can't be typed into remotely)
- **Outside the app:** home-screen widget, Quick Settings tile, lock-screen mini remote (opens the full remote
  over the lock screen), app-icon shortcuts, pause/mute during phone calls
- **TV Home tab:** turns off LG home ads, suggestions and channels, removes junk apps — only after you confirm
- **App:** light/dark/auto theme, update banner from GitHub Releases

Releases: every push to `main` that changes the app builds the APK on GitHub Actions and publishes the next version (v1.13.0 → v1.14.0) as a GitHub Release. A `v*` tag still sets a version by hand.
For power-on, enable *Settings → General → Devices → TV Management → Turn on via Wi-Fi* on the TV.

## PC dashboard (basic)

The original browser remote: arrows, volume, apps, inputs, power. The phone app has far more.

```bash
node find-tv.js            # find the TV's IP
node tv-test.js <tv-ip>    # pair once, creates tv-key.json
node server.js             # open http://localhost:8787
```

Add `"mac": "AA:BB:CC:DD:EE:FF"` to `tv-key.json` to enable the Wake button.

`tv-key.json` holds the pairing key and is git-ignored.
