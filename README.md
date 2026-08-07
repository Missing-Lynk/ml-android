# ml-android

Android companion app for the missinglynk goggle: plug the phone into the goggle over USB-C and watch the live FPV feed. The app plays the goggle's RTSP restream (`rtsp://192.168.3.100:554/venc8/stream`, H.265), served by ml-pipeline's built-in RTSP server and enabled via the goggle menu **DVR > RTSP Stream**.

## How it connects

The goggle presents a USB-ethernet gadget (CDC-ECM) and hands the phone a DHCP lease on the gadget subnet (192.168.3.123/24 on the goggle profile). The app scans the phone's networks for an interface on the stream URL's /24, binds its sockets to that network (so the phone's WiFi/mobile data stay untouched for everything else), probes the RTSP port, and starts playing when the goggle answers. No pairing, no configuration: plug in, open the app.

The stream URL is a preference (Settings), defaulting to the goggle's address. Playback is a GStreamer pipeline in native code (`rtspsrc ! rtph265depay ! h265parse ! decodebin ! glimagesink`), so the feed is H.265 end to end with no transcoding.

States: waiting for the goggle (no matching network or RTSP not up yet), playing (fullscreen landscape), reconnecting (frames stalled; auto-retry). Short feed dropouts are ridden out without a reconnect: the RF link resets Tx-side for a few seconds routinely and the stream resumes on its own.

## Requirements

- The goggle connected over USB-C, with the air unit powered and transmitting (the RTSP server has no media to describe without a live feed).
- **DVR > RTSP Stream** enabled in the goggle menu.
- Android 7.0+ (minSdk 24), arm64 device.

## Building

1. Install Android Studio with an SDK and NDK 25.2.9519653 (or adjust `ndkVersion` in `app/build.gradle.kts`).
2. Download the GStreamer prebuilt binaries for Android (arm64) from [gstreamer.freedesktop.org](https://gstreamer.freedesktop.org/download/) and unpack them.
3. Point the build at them in `local.properties`:

   ```properties
   gst.dir=/path/to/gstreamer-android
   ```

4. Build and install:

   ```sh
   ./gradlew installDebug
   ```

The native player (`app/src/main/jni/gstplayer.c`) is built by ndk-build against the GStreamer prebuilts; only `arm64-v8a` is shipped to keep the static-plugin payload sane.

## Support

Everything here is free and open. The work behind it is unpaid nights and weekends: reverse engineering, bricked and recovered hardware, and a lot of time on a serial console. If it saved you some of your own, you can [buy me a coffee](https://buymeacoffee.com/stylesuxx).

Not bought the hardware yet? The [project README](https://github.com/Missing-Lynk/MissingLynk#support-this-project) has affiliate links that support the work at no extra cost to you.
