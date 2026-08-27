#!/bin/sh
# Terminate the app's RTMP push and play it with ffplay.
#
# H.264 arrives as legacy FLV and the system ffplay plays it in one process. H.265 arrives as
# Enhanced FLV, and no single program here handles that:
#
#   the system ffmpeg is 5.1, which predates Enhanced FLV. It does not reject the stream, it
#   reports the HEVC track as `vp6f` and then fails to decode, which reads as a broken app.
#   Debian bookworm has nothing newer, backports included.
#
#   ffmpeg 7.0.3 in the flatpak freedesktop runtime parses it correctly, but that runtime ships
#   ffmpeg without the HEVC and AAC decoders; they live in an ffmpeg-full extension that is not
#   installed for 24.08. So it can remux and not play.
#
# For H.265 the runtime therefore does the RTMP handshake and the FLV parse, remuxes to MPEG-TS
# on loopback UDP with `-c copy`, and the system ffplay decodes and displays. No frame is
# re-encoded; the MPEG-TS muxer converts the HEVC NALs from length-prefixed to Annex B, which the
# system ffplay does read. The hop is UDP rather than a pipe so that both halves have a pid this
# script can signal: an ffmpeg blocked in accept() never takes SIGPIPE from a closed pipe, so a
# pipeline leaves it holding the port.
#
# The reader re-arms after each disconnect, so an app that drops and reconnects gets a fresh
# listener. Ctrl-C ends it.
#
# Usage:
#     ml-rtmp-receive.sh                          # rtmp://<this-host>:1935/live/test
#     ml-rtmp-receive.sh --port 11935
#     ml-rtmp-receive.sh --native                 # system ffplay alone, H.264 only

set -eu

rtmp_port=1935
rtmp_path=live/test
udp_port=5000
is_native_only=false
runtime=org.freedesktop.Platform//24.08

while [ $# -gt 0 ]; do
    case "$1" in
    --port) rtmp_port=$2; shift 2 ;;
    --path) rtmp_path=$2; shift 2 ;;
    --udp-port) udp_port=$2; shift 2 ;;
    --native) is_native_only=true; shift ;;
    -h|--help) sed -n '2,29p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Enhanced FLV became readable in ffmpeg 6.1. Version strings run "ffplay version 5.1.9-0+deb12u1"
# and "ffplay version n7.1", so the leading letter and everything after the minor are dropped.
is_enhanced_flv_reader() {
    version=$("$1" -version 2>/dev/null | head -1 | sed -n 's/^ffplay version n\?\([0-9]*\)\.\([0-9]*\).*/\1 \2/p')
    [ -n "$version" ] || return 1
    major=${version% *}
    minor=${version#* }
    [ "$major" -gt 6 ] || { [ "$major" -eq 6 ] && [ "$minor" -ge 1 ]; }
}

url="rtmp://0.0.0.0:$rtmp_port/$rtmp_path"
host_ffplay=$(command -v ffplay || true)
[ -n "$host_ffplay" ] || { echo "no ffplay on PATH" >&2; exit 1; }

needs_front_end=true
if [ "$is_native_only" = true ]; then
    needs_front_end=false
    echo "reader: $host_ffplay alone (H.264 only)"
elif is_enhanced_flv_reader "$host_ffplay"; then
    needs_front_end=false
    echo "reader: $host_ffplay"
elif flatpak info "$runtime" >/dev/null 2>&1; then
    echo "reader: ffmpeg in $runtime feeding $host_ffplay (the host one predates Enhanced FLV)"
else
    echo "$runtime is not installed, so H.265 will not play; --native runs H.264 anyway" >&2
    exit 1
fi

front_end_pid=
player_pid=
# The front end goes first, because that is what holds the listen socket. `wait` names both pids
# rather than running bare: bare `wait` blocks on any child still running, and a front end parked
# in accept() with no client is exactly that.
cleanup() {
    if [ -n "$front_end_pid" ]; then
        kill "$front_end_pid" 2>/dev/null || true
        wait "$front_end_pid" 2>/dev/null || true
    fi
    if [ -n "$player_pid" ]; then
        kill "$player_pid" 2>/dev/null || true
        wait "$player_pid" 2>/dev/null || true
    fi
    exit 0
}
trap cleanup INT TERM

echo "listening on $url"

# The player starts once and outlives every front end, so a reconnecting app costs the picture
# only the gap itself. Closing its window ends the script.
if [ "$needs_front_end" = true ]; then
    "$host_ffplay" -hide_banner -nostats -loglevel warning \
        -fflags nobuffer -framedrop -window_title "$url" \
        -i "udp://127.0.0.1:$udp_port?fifo_size=1000000&overrun_nonfatal=1&timeout=0" &
    player_pid=$!
fi

while true; do
    if [ "$needs_front_end" = true ]; then
        kill -0 "$player_pid" 2>/dev/null || break
        # --die-with-parent, or the sandboxed ffmpeg outlives its `flatpak run` and keeps the
        # listen socket, and the next run cannot bind the port.
        flatpak run --die-with-parent --share=network --command=ffmpeg "$runtime" \
            -hide_banner -nostats -loglevel info -listen 1 -f flv -i "$url" \
            -c copy -f mpegts "udp://127.0.0.1:$udp_port?pkt_size=1316" &
        front_end_pid=$!
        wait "$front_end_pid" 2>/dev/null || true
        front_end_pid=
    else
        "$host_ffplay" -hide_banner -nostats -loglevel info -autoexit \
            -fflags nobuffer -framedrop -window_title "$url" -listen 1 -f flv -i "$url" &
        player_pid=$!
        wait "$player_pid" 2>/dev/null || true
        player_pid=
    fi
    sleep 1
done

cleanup
