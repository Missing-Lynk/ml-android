#!/usr/bin/env python3
"""Serve a goggle-shaped RTSP restream from the host, so the app can be tested with no goggle.

Reproduces ml-pipeline's DVR mount, rtsp://<goggle>:554/venc8/stream: same path, video-only, a
bounded GOP, and parameter sets ahead of every IDR so a client joining mid-stream decodes at the
next key frame.

    /venc8/stream   the codec --codec names (default h265, the goggle's default)
    /venc8/h264     always H.264
    /venc8/h265     always H.265

The encoder runs from process start to process exit whether or not anything is watching, which is
what the goggle does, so a client that leaves and comes back rejoins a feed already in progress
rather than one restarted at zero. It therefore lives in a pipeline this script owns and each
client's media is fed by an appsrc; gst-rtsp-server never owns it and cannot restart it. Mounts
sharing a codec share its encoder, started with the process for --codec and on first use for the
other. The burned-in left counter is that encoder's running time, and the right is wall clock.

x265 reaches about 40 fps at 1080p on a 16-core desktop, so the H.264 mount is the one that
reproduces the goggle's real geometry and rate.

Run under /usr/bin/python3: the bindings are distribution packages, invisible to a venv.
Needs gir1.2-gst-rtsp-server-1.0 and python3-gi.

Usage:
    ml-fake-goggle.py                                       # 1080p60, both codecs, port 554
    ml-fake-goggle.py --port 8554 --width 1280 --height 720 --fps 30 --codec h264
    ml-fake-goggle.py --drop-after 20 --drop-for 4          # rehearse a dropout, repeating
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import threading
from typing import Any

GOP_FRAMES: int = 60            # matches mlp-record.c's ML_DVR_GOP default
MOUNT_STREAM: str = "/venc8/stream"
MOUNT_H264: str = "/venc8/h264"
MOUNT_H265: str = "/venc8/h265"
VALVE_NAME: str = "dropvalve"
# How old a cached key frame may be and still open a client on. One GOP at the lowest frame rate
# the tool serves, which is the longest a client legitimately waits for the next one.
GOP_STALE_NS: int = 3 * 1000 * 1000 * 1000
APPSINK_NAME: str = "encoded"
APPSRC_NAME: str = "feed"
DEFAULT_PORT: int = 554
DEFAULT_WIDTH: int = 1920
DEFAULT_HEIGHT: int = 1080
DEFAULT_FPS: int = 60
DEFAULT_BITRATE_KBPS: int = 6000

# What one client may leave unread before it is dropped back to the next key frame, so a client
# that stops reading neither stalls the shared encoder nor grows this process without bound.
CLIENT_BACKLOG_BYTES: int = 4 * 1024 * 1024


def require_bindings() -> tuple[Any, Any, Any, Any]:
    """Import the GStreamer and RTSP-server bindings, or explain how to install them."""
    try:
        import gi

        gi.require_version("Gst", "1.0")
        gi.require_version("GstRtsp", "1.0")
        gi.require_version("GstRtspServer", "1.0")
        from gi.repository import GLib, Gst, GstRtsp, GstRtspServer
    except (ImportError, ValueError) as exc:
        print(f"gst-rtsp-server bindings are missing: {exc}", file=sys.stderr)
        print(f"this interpreter is {sys.executable}", file=sys.stderr)
        print("the bindings are distribution packages and are visible only to the system "
              "interpreter, so run this under /usr/bin/python3 if the shebang picked up a "
              "venv, pyenv or uv python", file=sys.stderr)
        print("if they are genuinely absent:", file=sys.stderr)
        print("    sudo apt install gir1.2-gst-rtsp-server-1.0 python3-gi", file=sys.stderr)
        raise SystemExit(2) from exc

    return GLib, Gst, GstRtsp, GstRtspServer


def warn_missing_encoder(codec: str) -> None:
    """Name the missing encoder up front, rather than letting a mount fail at DESCRIBE."""
    element = "x264enc" if codec == "h264" else "x265enc"
    try:
        found = subprocess.run(["gst-inspect-1.0", element],
                               capture_output=True, text=True, check=False)
    except FileNotFoundError:
        return

    if found.returncode != 0:
        package = "gstreamer1.0-plugins-ugly" if codec == "h264" else "gstreamer1.0-plugins-bad"
        print(f"{element} is missing, so the {codec} mounts will fail. "
              f"Try: sudo apt install {package}", file=sys.stderr)


def encoder_launch_line(codec: str, width: int, height: int, fps: int, bitrate: int,
                        dropping: bool) -> str:
    """The always-on encoder for one codec, ending in the appsink every client is fed from.

    The valve sits after the parser so a rehearsed dropout withholds finished access units while
    the encoder keeps running, which is what a real feed interruption looks like. The parser is
    what makes the fan-out possible: whole access units, parameter sets ahead of every IDR.
    """
    encoder = "x264enc" if codec == "h264" else "x265enc"
    parser = "h264parse" if codec == "h264" else "h265parse"
    media = "video/x-h264" if codec == "h264" else "video/x-h265"
    valve = f"valve name={VALVE_NAME} drop=false ! " if dropping else ""

    return (
        f"videotestsrc is-live=true pattern=smpte "
        f"! video/x-raw,width={width},height={height},framerate={fps}/1,format=I420 "
        f"! timeoverlay halignment=left valignment=top font-desc=\"Sans 28\" "
        f"! clockoverlay halignment=right valignment=top font-desc=\"Sans 28\" "
        f"! {encoder} tune=zerolatency speed-preset=ultrafast "
        f"bitrate={bitrate} key-int-max={GOP_FRAMES} "
        f"! {media},profile=main "
        f"! {parser} config-interval=-1 "
        f"! {media},stream-format=byte-stream,alignment=au "
        f"! {valve}"
        f"appsink name={APPSINK_NAME} emit-signals=true sync=false max-buffers=4 drop=false"
    )


def media_launch_line(codec: str) -> str:
    """One client's RTSP media: the appsrc the encoder is fanned out into, and the payloader.

    `do-timestamp=false` is load-bearing. An appsrc stamps against its own base time, which is
    zero until its pipeline reaches PLAYING, so anything handed to it before then is stamped with
    the raw clock and lands days in the future; the fan-out carries the encoder's timestamps
    across instead. The queue is leaky because the encoder behind it is shared.
    """
    payloader = "rtph264pay" if codec == "h264" else "rtph265pay"

    return (
        f"( appsrc name={APPSRC_NAME} is-live=true format=time do-timestamp=false "
        f"max-bytes={CLIENT_BACKLOG_BYTES} block=false "
        f"! queue leaky=downstream max-size-buffers=0 max-size-time=0 "
        f"max-size-bytes={CLIENT_BACKLOG_BYTES} "
        f"! {payloader} name=pay0 pt=96 config-interval=-1 )"
    )


class Client:
    """One attached RTSP media, as the encoder's fan-out sees it."""

    def __init__(self, appsrc: Any) -> None:
        self.appsrc: Any = appsrc
        self.has_caps: bool = False
        self.is_feeding: bool = False       # set once this client's pipeline is running
        self.base_pts: int = 0              # encoder timestamp its timeline starts from
        self.is_resyncing: bool = False     # dropped behind; waiting for a key frame


class Encoder:
    """One always-running encoder, fanned out to every client attached to its mounts.

    Started once and never stopped, so the running time burned into the picture is continuous
    across every client's connect and disconnect.
    """

    def __init__(self, glib: Any, gst: Any, codec: str, launch: str) -> None:
        self.glib: Any = glib
        self.gst: Any = gst
        self.codec: str = codec
        self.launch: str = launch
        self.pipeline: Any = None
        self.lock: threading.Lock = threading.Lock()
        self.clients: list[Client] = []
        self.caps: Any = None
        self.last_key_sample: Any = None
        self.has_reported_error: bool = False

    def start(self) -> bool:
        """Bring the encoder up. Idempotent, so a mount can start its own encoder on first use."""
        with self.lock:
            if self.pipeline is not None:
                return True

            try:
                pipeline = self.gst.parse_launch(self.launch)
            except self.glib.Error as exc:
                print(f"the {self.codec} encoder would not build: {exc}", file=sys.stderr)
                return False

            appsink = pipeline.get_by_name(APPSINK_NAME)
            if appsink is None:
                print(f"the {self.codec} encoder has no {APPSINK_NAME} sink", file=sys.stderr)
                return False

            appsink.connect("new-sample", self._on_sample)
            bus = pipeline.get_bus()
            bus.add_signal_watch()
            bus.connect("message::error", self._on_bus_error)
            self.pipeline = pipeline

        if pipeline.set_state(self.gst.State.PLAYING) == self.gst.StateChangeReturn.FAILURE:
            print(f"the {self.codec} encoder would not start", file=sys.stderr)
            return False
        return True

    def set_dropping(self, dropping: bool) -> None:
        if self.pipeline is None:
            return

        valve = self.pipeline.get_by_name(VALVE_NAME)
        if valve is not None:
            valve.set_property("drop", dropping)

    def attach(self, appsrc: Any) -> None:
        """Add a client. Feeding starts once its own pipeline is running; see _push."""
        client = Client(appsrc)
        with self.lock:
            if self.caps is not None:
                appsrc.set_property("caps", self.caps)
                client.has_caps = True
            self.clients.append(client)

    def detach(self, appsrc: Any) -> None:
        with self.lock:
            self.clients = [c for c in self.clients if c.appsrc is not appsrc]

    def _on_sample(self, appsink: Any) -> Any:
        sample = appsink.emit("pull-sample")
        if sample is None:
            return self.gst.FlowReturn.OK

        buffer = sample.get_buffer()
        caps = sample.get_caps()
        is_key = not buffer.has_flags(self.gst.BufferFlags.DELTA_UNIT)

        with self.lock:
            self.caps = caps
            if is_key:
                self.last_key_sample = sample
            clients = list(self.clients)

        gone = [c for c in clients if not self._push(c, caps, buffer, is_key)]
        if gone:
            with self.lock:
                self.clients = [c for c in self.clients if c not in gone]
        return self.gst.FlowReturn.OK

    def _push(self, client: Client, caps: Any, buffer: Any, is_key: bool) -> bool:
        """Feed one client. False once its appsrc has stopped accepting, so it can be dropped:
        a media that goes away without reporting itself unprepared would otherwise be fed
        forever."""
        if not client.has_caps:
            client.appsrc.set_property("caps", caps)
            client.has_caps = True

        if not client.is_feeding:
            # gst-rtsp-server leaves the media in NULL until the client asks for it, and
            # anything pushed before then queues ahead of the first buffer that matters
            state = client.appsrc.get_state(0)[1]
            if state != self.gst.State.PAUSED and state != self.gst.State.PLAYING:
                return True

            # open on a key frame so the payloader publishes parameter sets at once
            if not is_key:
                with self.lock:
                    opening = self.last_key_sample
                # A cached key frame older than a GOP predates a rehearsed dropout, and opening
                # on it would set this client's timeline back to before the gap: its first
                # access unit would arrive at zero and its second a gap-length into the future,
                # which a receiver's jitter buffer holds rather than plays. Waiting costs at
                # most one GOP, which is what a client joining a live feed pays anyway.
                if opening is None or (
                    buffer.pts != self.gst.CLOCK_TIME_NONE
                    and opening.get_buffer().pts != self.gst.CLOCK_TIME_NONE
                    and buffer.pts - opening.get_buffer().pts > GOP_STALE_NS
                ):
                    return True
                client.base_pts = opening.get_buffer().pts
                client.is_feeding = True
                if not self._send(client, opening.get_buffer()):
                    return False
            else:
                client.base_pts = buffer.pts
                client.is_feeding = True

        if client.is_resyncing:
            if not is_key:
                return True
            client.is_resyncing = False
        elif client.appsrc.get_property("current-level-bytes") > CLIENT_BACKLOG_BYTES:
            # stopped reading; hold off rather than feed it undecodable access units
            client.is_resyncing = True
            return True

        return self._send(client, buffer)

    def _send(self, client: Client, buffer: Any) -> bool:
        """Hand one access unit over, on the client's own timeline. The copy is metadata only;
        the encoded bytes stay shared."""
        out = buffer.copy()
        if buffer.pts != self.gst.CLOCK_TIME_NONE:
            out.pts = max(0, buffer.pts - client.base_pts)
        if buffer.dts != self.gst.CLOCK_TIME_NONE:
            out.dts = max(0, buffer.dts - client.base_pts)

        return client.appsrc.emit("push-buffer", out) == self.gst.FlowReturn.OK

    def _on_bus_error(self, _bus: Any, message: Any) -> None:
        error, _debug = message.parse_error()
        if self.has_reported_error:
            return

        self.has_reported_error = True
        print(f"the {self.codec} encoder failed: {error.message}", file=sys.stderr)


def arm_dropouts(glib: Any, encoders: list[Encoder], drop_after: int, drop_for: int) -> None:
    """Withhold every encoder's output on a repeating cycle, so a rehearsed dropout is one feed
    interruption that every attached client sees."""
    state: dict[str, Any] = {"open": True}

    def flip() -> bool:
        state["open"] = not state["open"]
        for encoder in encoders:
            encoder.set_dropping(not state["open"])
        print("feed resumed" if state["open"] else "feed stalled")
        glib.timeout_add_seconds(drop_after if state["open"] else drop_for, flip)
        return glib.SOURCE_REMOVE

    glib.timeout_add_seconds(drop_after, flip)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Serve a goggle-shaped RTSP restream from the host.")
    parser.add_argument("--codec", choices=("h264", "h265"), default="h265",
                        help="codec carried by /venc8/stream (default h265, the goggle's default)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"listen port (default {DEFAULT_PORT}; under 1024 needs root)")
    parser.add_argument("--address", default="0.0.0.0",
                        help="bind address (default every interface)")
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    parser.add_argument("--fps", type=int, default=DEFAULT_FPS)
    parser.add_argument("--bitrate", type=int, default=DEFAULT_BITRATE_KBPS, metavar="KBPS")
    parser.add_argument("--udp", action="store_true",
                        help="offer UDP as well, which is what the goggle serves. Measured over "
                             "WiFi it drops a 1080p60 feed to about 6 fps, because a keyframe "
                             "burst is ~70 back-to-back packets and losing any one discards the "
                             "frame; the default is TCP-interleaved so a run measures the app "
                             "rather than the wireless link")
    parser.add_argument("--drop-after", type=int, default=0, metavar="SEC",
                        help="rehearse a dropout: stream this long, then withhold frames")
    parser.add_argument("--drop-for", type=int, default=0, metavar="SEC",
                        help="how long each rehearsed dropout lasts")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    glib, gst, rtsp, rtsp_server = require_bindings()

    if (args.drop_after > 0) != (args.drop_for > 0):
        print("--drop-after and --drop-for are set together", file=sys.stderr)
        return 2

    dropping = args.drop_after > 0
    warn_missing_encoder("h264")
    warn_missing_encoder("h265")

    gst.init(None)

    encoders: dict[str, Encoder] = {
        codec: Encoder(glib, gst, codec,
                       encoder_launch_line(codec, args.width, args.height, args.fps,
                                           args.bitrate, dropping))
        for codec in ("h264", "h265")
    }

    # live from the moment the process is, so a client always finds a feed already running
    if not encoders[args.codec].start():
        return 1

    server = rtsp_server.RTSPServer()
    server.set_service(str(args.port))
    server.set_address(args.address)
    mounts = server.get_mount_points()

    wanted: dict[str, str] = {
        MOUNT_STREAM: args.codec,
        MOUNT_H264: "h264",
        MOUNT_H265: "h265",
    }
    for path, codec in wanted.items():
        encoder = encoders[codec]
        factory = rtsp_server.RTSPMediaFactory()
        factory.set_launch(media_launch_line(codec))
        if not args.udp:
            factory.set_protocols(rtsp.RTSPLowerTrans.TCP)

        def on_media_configure(_factory: Any, media: Any, encoder: Encoder = encoder) -> None:
            if not encoder.start():
                return

            appsrc = media.get_element().get_by_name(APPSRC_NAME)
            if appsrc is None:
                return

            encoder.attach(appsrc)
            media.connect("unprepared", lambda _media: encoder.detach(appsrc))

        factory.connect("media-configure", on_media_configure)
        mounts.add_factory(path, factory)

    if server.attach(None) == 0:
        print(f"could not listen on {args.address}:{args.port}", file=sys.stderr)
        if args.port < 1024:
            print("ports below 1024 need root; try --port 8554", file=sys.stderr)
        return 1

    print(f"serving {args.width}x{args.height}@{args.fps}, GOP {GOP_FRAMES}, no audio")
    print(f"the {args.codec} encoder is running; it keeps running when a client leaves")
    for path, codec in wanted.items():
        print(f"  rtsp://<this-host>:{args.port}{path}  ({codec})")
    if dropping:
        print(f"rehearsing a dropout every {args.drop_after}s, lasting {args.drop_for}s")

    if dropping:
        arm_dropouts(glib, list(encoders.values()), args.drop_after, args.drop_for)

    glib.MainLoop().run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
