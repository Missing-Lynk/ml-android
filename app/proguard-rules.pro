# R8 rules.
#
# Everything here exists because native code resolves these names at runtime, which R8 cannot
# see. A missing rule does not fail the build: it fails on the device, at load or at first
# play, which is why each rule below names what resolves it.

# --- our JNI peer ---------------------------------------------------------------------------
# jni/gstplayer.c reaches into this class by name:
#   JNI_OnLoad          FindClass("at/websium/ml/GStreamerPlayer") + RegisterNatives
#   gst_native_class_init  GetFieldID(nativeCustomData), GetMethodID(onNativeState/onNativeLog)
# onNativeState and onNativeLog are private and called only from C, so without a keep rule R8
# reads them as dead code and deletes them. The class is small; keeping it whole costs nothing
# and removes a whole class of runtime surprise.
-keep class at.websium.ml.GStreamerPlayer { *; }
-keep class at.websium.ml.GStreamerPlayer$Companion { *; }

# --- the GStreamer SDK's Java side ----------------------------------------------------------
# GStreamer.nativeInit is bound by the JNI name-mangling rules, so the package, class and method
# names must survive verbatim. The androidmedia callbacks are instantiated from C by the
# androidmedia plugin (hardware MediaCodec decode), again by name.
-keep class org.freedesktop.gstreamer.** { *; }

# --- readable crash reports -----------------------------------------------------------------
# Keep line numbers so Play's crash reports deobfuscate against the uploaded mapping file, and
# hide the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
