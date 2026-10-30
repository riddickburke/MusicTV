# Music TV

Minimal Android TV wrapper around music.apple.com for personal use.
D-pad moves an on-screen pointer, OK taps, edges scroll, Back = browser back,
media keys control MusicKit playback.

## Build
Open this folder in Android Studio (it will sync Gradle), then Build > Build APK(s).

## Install on the box
1. Settings > System > About: tap the build number 7 times.
2. Settings > System > Developer options: enable USB debugging / wireless debugging.
3. `adb connect <box-ip>:5555`
4. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Known unknowns
- Widevine playback in WebView depends on the box; if you get a DRM error, try a newer WebView or a different browser engine.
- Apple may change the site/sign-in flow and break things.
- Not compiled or tested yet: expect to fix small things.

## Get an APK without Android Studio
Push this folder to a public GitHub repo (branch `main`). The Actions workflow builds it and
publishes `app-debug.apk` to a release. On the TV, open the Downloader app and enter:
`https://github.com/<user>/<repo>/releases/latest/download/app-debug.apk`
