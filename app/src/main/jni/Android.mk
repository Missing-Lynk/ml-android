LOCAL_PATH := $(call my-dir)

ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined! Set gst.dir in local.properties)
endif

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/armv7
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm64
else ifeq ($(TARGET_ARCH_ABI),x86)
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86
else ifeq ($(TARGET_ARCH_ABI),x86_64)
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86_64
else
$(error Target arch ABI $(TARGET_ARCH_ABI) not supported)
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build/

include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk

# Plugins for the goggle's RTSP HEVC pipeline:
#   rtspsrc(rtsp) ! rtph265depay(rtp) ! h265parse(videoparsersbad) ! decodebin(playback)
#   ! glimagesink(opengl)
# rtpmanager = jitterbuffer used by rtspsrc; udp/tcp = rtsp transports;
# androidmedia = HW HEVC decode; libav = avdec_h265 SW fallback;
# videoconvertscale + coreelements = glue.
#   flv + rtmp2 = the restream egress (flvmux ! rtmp2sink), rtmp2 rather than the librtmp-based
# rtmpsink because only rtmp2 speaks rtmps, which YouTube requires.
#   voaacenc + audiotestsrc + audioconvert + audioresample = the silent AAC track. RTMP ingests
# behave badly with video-only, and because the audio is decoupled from the video it keeps
# flowing through an RF dropout, which holds the session open instead of ending the broadcast.
#   gio = the TLS transport rtmps rides on.
#   app = the appsrc heading the egress pipeline. The egress is a pipeline of its own, fed by a
# pad probe on the player's tee, so a destination that fails returns its error to its own bus
# and its own flow path instead of upstream into the leg carrying the picture.
GSTREAMER_PLUGINS := coreelements videoconvertscale opengl androidmedia \
                     rtsp rtp rtpmanager udp tcp videoparsersbad libav playback \
                     flv rtmp2 voaacenc audiotestsrc audioconvert audioresample gio app
# openssl is here for the GIO TLS module below: G_IO_MODULES links the module itself but not the
# libssl/libcrypto it calls into, which otherwise fails as a wall of undefined X509_* symbols.
GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0 gstreamer-gl-1.0 gstreamer-rtp-1.0 \
                        gstreamer-app-1.0 openssl

# The gio plugin supplies the transport; the TLS backend behind it is a separate GIO module that
# has to be linked and registered by name. Without this line rtmps fails at the handshake with a
# plain connection error and nothing mentioning TLS, however many CA certificates are packaged.
G_IO_MODULES := openssl

# Fonts stay off: no plugin here needs pango, and the 348 KB face would be copied into
# src/main/assets on every build. CA certificates are on because rtmps validates the ingest's
# certificate against them, which costs about 220 KB.
GSTREAMER_INCLUDE_FONTS := no
GSTREAMER_INCLUDE_CA_CERTIFICATES := yes

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk

include $(CLEAR_VARS)

LOCAL_MODULE    := gstplayer
LOCAL_SRC_FILES := gstplayer.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)
