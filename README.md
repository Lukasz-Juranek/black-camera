# Black Camera

A minimal black Android app that turns the phone into an IP camera on your local Wi-Fi.

- Tap **Start**. The app shows the address, e.g. `http://192.168.1.23:8080`. Tap the address to copy it
- On the PC (same network), open that address in a browser
- `/stream` is a plain MJPEG stream that works in VLC (*Media → Open Network Stream*), OBS (*Media Source* or *Browser*),
  Home Assistant, ffmpeg and others. `/video` and `/mjpeg` work too
- `/snapshot` returns a single JPEG. `/shot.jpg` works too
- Back or front camera, 480p / 720p / 1080p, landscape or portrait, flashlight toggle, and the port can be changed (default 8080)
- Keeps streaming with the screen locked. It runs as a foreground service with a notification that has a **Stop** button
- Frames are only encoded while someone is watching, to save battery

It needs the camera permission. On Android 13+ it also asks to show the notification.

There is no password, so anyone on the same network can open the stream. Use it on networks you trust.

Built with Kotlin, Jetpack Compose and CameraX. It needs Android 8.0 or newer.

## Building

The Android SDK is not needed locally. Every push to `main` builds a signed APK on GitHub Actions and
publishes it on the **Releases** page. Open that page on your phone and install `BlackCamera.apk`
(allow "install unknown apps" for your browser).

The signing key is stored in the `KEYSTORE_B64` / `KEYSTORE_PASSWORD` repo secrets. It's the same key as
Black Player's and Black Files'. Keep a backup of `keystore/release.p12`. It is gitignored.

### Optional local build (Docker)

```sh
docker run --rm -v "$PWD":/project -w /project mingc/android-build-box gradle assembleDebug
```
