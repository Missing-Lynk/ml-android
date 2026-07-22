# ndk-build app config for the GStreamer player. We ship arm64 only (matches the app's
# abiFilters and keeps the GStreamer static-plugin payload sane).
APP_ABI := arm64-v8a
APP_STL := c++_shared
APP_PLATFORM := android-24
