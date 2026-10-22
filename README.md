# BBW Browser

Kotlin Android browser built on Android WebView, with a "Display over other apps"
session mode that keeps the web page running while you use other apps.

## Build (Codemagic)
1. Push this folder to a Git repo and add it to Codemagic.
2. Use the included `codemagic.yaml` (workflow: `bbw-android`).
3. The APK appears under `app/build/outputs/apk/release/` (signed with the debug key, installable directly).

## Build (local)
Open in Android Studio (JDK 17) and run `app`, or: `gradle wrapper --gradle-version 8.7 && ./gradlew assembleRelease`

## Background session
- Menu > "Keep session alive in background": grant "Display over other apps" once.
- When you leave the app, the active tab moves into an invisible overlay window hosted by a
  foreground service, so the page, cookies and JS keep running.
- Menu > "Floating window": shows the active tab in a small draggable window on top of other apps
  (buttons: back, open app, hide to background).
- While the background session runs, a partial wake lock and Wi-Fi lock keep CPU/network awake with the screen off (released when you press "Stop" in the notification).
- Notification "Stop" ends the background session.

## Structure
- `TabManager.kt`: tabs, WebView setup, downloads, tab persistence
- `OverlayManager.kt`: overlay windows (background + floating)
- `KeepAliveService.kt`: foreground service
- `MainActivity.kt`: browser UI
