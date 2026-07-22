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
GSTREAMER_PLUGINS := coreelements videoconvertscale opengl androidmedia \
                     rtsp rtp rtpmanager udp tcp videoparsersbad libav playback
GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0 gstreamer-gl-1.0 gstreamer-rtp-1.0

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk

include $(CLEAR_VARS)

LOCAL_MODULE    := gstplayer
LOCAL_SRC_FILES := gstplayer.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)
