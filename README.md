# ml-android

Android companion app for the MissingLynk goggle: plug the phone into the goggle over USB-C and watch the live FPV feed. The app plays the goggle's RTSP restream (`rtsp://192.168.3.101:554/venc8/stream`, H.265), served by ml-pipeline's built-in RTSP server and enabled via the goggle menu **DVR > RTSP Stream**.

## How it connects

The goggle presents a USB-ethernet gadget (CDC-ECM) and hands the phone a DHCP lease on the gadget subnet (192.168.3.123/24 on the goggle profile). The app looks for the gadget among the phone's networks, matching both the stream URL's /24 and an ethernet transport or a `usb`/`eth`/`rndis`/`ncm` interface name, so a home WiFi network on the same 192.168.x range is passed over. It binds its sockets to the gadget alone, leaving WiFi and mobile data to serve everything else on the phone, probes the RTSP port, and starts playing when the goggle answers. No pairing, no configuration: plug in, open the app.

The stream URL is a preference (Settings), defaulting to the goggle's address. Playback is a GStreamer pipeline in native code: `rtspsrc` feeds a depayloader and parser chosen from the codec the SDP names, then a tee, and one leg of the tee decodes to the screen. The goggle's encoded video is carried as it arrives, never transcoded, which is also what lets the other leg of the tee be pushed to an ingest byte for byte. Tapping the video shows which codec the session negotiated.

States: waiting for the goggle (no matching network, or the RTSP server not up yet), connecting, no video (connected for seven seconds with nothing arriving, so the air unit is the thing to check), playing (fullscreen landscape), and reconnecting (auto-retry). When an attempt fails outright, the screen carries the player's own reason underneath, which separates a refused connection from a missing decoder.

A feed that stops is told apart from a goggle that goes away, because they want different things. While the goggle is still serving, the picture is held on its last frame under a "No video from the air unit" notice and nothing is torn down, so the video resumes where it left off with no reconnect; that is what a battery swap looks like. A goggle that stops answering returns to the waiting screen. Momentary dropouts show nothing at all: the RF link resets Tx-side for a second or two routinely and the picture catches up on its own.

Leaving a session (back, or the on-video back control) parks the app on a Connect button, so a deliberate disconnect sticks, and ends a restream with it. Unplugging the goggle re-arms auto-connect for the next plug-in.

**Diagnostics** in the menu holds an on-device log of the session: the decoder that was chosen, pipeline rebuilds, stream errors and state transitions. Share exports it, which is the useful thing to attach to a bug report.

## Restreaming

Tap the video while playing to reveal the controls, and the second one arms a restream: the same encoded video the phone is decoding is pushed to whichever destination is selected in **Settings > Streaming destination**. Destinations are saved with a name, so a "Twitch Inspector" entry for testing and a "Twitch live" entry for flying sit side by side and the toggle stays one tap. Each takes the server URL with the stream key on the end as one pasted string; the key is masked wherever the URL is shown, and everywhere else names the destination instead, including the notification and the diagnostics log.

The restream is a pipeline of its own beside the player, so arming and disarming never interrupt the picture, and an ingest that refuses the stream costs the broadcast rather than the flight view. A badge in the bottom-left names the destination, says whether the broadcast is carrying or between reconnect attempts, and names what its audio track is carrying. It keeps running with the app off screen or the phone locked, and neither a stopped feed nor an unplugged goggle ends it: the broadcast holds its connection open and picks the video back up with the next session, without touching the toggle. Stop in the notification ends it without opening the app.

The broadcast always carries an audio track, because it is what holds the connection open while the video drops, which is what a battery swap looks like from the ingest's side. **Settings > Audio** chooses what that track carries: a generated silence, or the microphone, which picks up the room including anything playing on a speaker. Choosing the microphone asks for its permission there; refusing leaves the track silent, and so does a microphone that will not open, rather than costing the broadcast.

Twitch and Kick ingest H.264 only, so arming either of them against a goggle sending H.265 is refused with the menu path to change it. YouTube and self-hosted servers take both.

A broadcast that will not start says what the destination objected to: a stream key it rejected, a key something else is already broadcasting to, an incomplete URL, a server it could not reach, or a certificate it refused. Anything else reads as a plain failure and points at Diagnostics, which carries the streaming library's own wording.

## Requirements

- The goggle connected with a USB-A to USB-C OTG adapter. A plain USB-C to USB-C cable does not work, since the phone has to act as the USB host.
- The air unit powered and transmitting (the RTSP server has no media to describe without a live feed).
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

The native player (`app/src/main/jni/gstplayer.c`) is built by ndk-build against the GStreamer prebuilts. Only `arm64-v8a` is shipped, since the statically linked plugins cost several megabytes per ABI; the release bundle lands around 14 MB.

## Support

Everything here is free and open. The work behind it is unpaid nights and weekends: reverse engineering, bricked and recovered hardware, and a lot of time on a serial console. If it saved you some of your own, you can [buy me a coffee](https://buymeacoffee.com/stylesuxx).

Not bought the hardware yet? The [project README](https://github.com/Missing-Lynk/MissingLynk#support-this-project) has affiliate links that support the work at no extra cost to you.
