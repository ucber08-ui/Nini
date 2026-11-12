# Ninja Ball Android

Android WebView wrapper for the existing Ninja Ball game. Offline multiplayer uses local Wi-Fi/hotspot TCP WebSocket + UDP room discovery; the app does not need internet access during play.

## Build
Use JDK 17 and Android SDK 35. The GitHub Actions workflow below installs Gradle 8.7 and builds `:app:assembleDebug`.

The HTML game is in `app/src/main/assets/index.html`.
