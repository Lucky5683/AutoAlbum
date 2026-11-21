# AutoAlbum (Android / Kotlin)

Private Android app scaffold for user-controlled video navigation in Google Photos.

## Current alpha contents
- Kotlin + Jetpack Compose starter screen
- Runtime permission request for local video access
- MediaStore local-video listing model
- Accessibility service declaration and safe integration placeholder
- Draggable floating overlay shell with START / STOP / EXIT menu
- Double-tap-on-handle stop gesture

## Important: not a finished automation APK
The Google Photos adapter, reliable playback-completion detection, next-video navigation, pause-state detection, call interruption handling, lock-screen stop handling, and complete album-selection UI are not implemented yet. They require on-device testing because Google Photos does not provide a public API for controlling its viewer and UI behaviour can vary by app version.

## Build
1. Install Android Studio with JDK 17 and Android SDK Platform 35.
2. Open this folder in Android Studio.
3. Let Gradle sync.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).

This source uses a conservative starter toolchain (AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21). Android Studio may recommend a newer compatible toolchain and SDK; update versions together if prompted.

## Permissions
The app requests local video access and the user must separately enable the accessibility service and overlay permission in Android Settings. Accessibility is only intended to interact with the supported Google Photos viewer after the user starts automation.
