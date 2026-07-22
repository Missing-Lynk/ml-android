/**
 * Copy this file into your Android project and call init(). If your project
 * contains fonts and/or certificates in assets, add the copyFonts()/
 * copyCaCertificates() helpers from the GStreamer SDK template back in.
 */
package org.freedesktop.gstreamer;

import android.content.Context;

public class GStreamer {
    private static native void nativeInit(Context context) throws Exception;

    public static void init(Context context) throws Exception {
        nativeInit(context);
    }
}
