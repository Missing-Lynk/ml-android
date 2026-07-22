/*
 * JNI bridge between the Kotlin GStreamerPlayer and a GStreamer pipeline, adapted from
 * the GStreamer Android "tutorial 5". A GLib main loop runs on its own thread and owns the
 * pipeline; play/stop from the app are dispatched onto that loop thread (via
 * g_main_context_invoke) so all pipeline lifecycle happens on one thread (no races).
 *
 * Pipeline: rtspsrc latency=100 ! rtph265depay ! h265parse ! decodebin ! glimagesink
 * sync=false (renders frames as they arrive, which the goggle's clockless stream needs).
 * Renders into an ANativeWindow from the app's TextureView Surface; the pipeline keeps
 * running when the surface goes away (app backgrounded) so the feed stays live on return.
 * Player state + a frame counter are reported back to Kotlin.
 */
#include <string.h>
#include <stdint.h>
#include <jni.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>

#define TAG "GstPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* state codes shared with Kotlin (mapped to PlayerState there) */
#define ST_CONNECTING 0
#define ST_PLAYING    1
#define ST_ERROR      2
#define ST_ENDED      3

typedef struct _CustomData
{
    jobject app;               /* global ref to the Kotlin GStreamerPlayer */
    GstElement *pipeline;
    GstElement *video_sink;    /* the GstVideoOverlay (glimagesink) */
    GMainContext *context;
    GMainLoop *main_loop;
    GSource *bus_source;       /* bus watch source, removed on teardown */
    ANativeWindow *native_window;
    gchar *uri;
    gint frame_count;          /* buffers reaching the sink (g_atomic); 0 => no media flowing */
    gint rebuild_count;        /* how many times the pipeline has been (re)built this session */
} CustomData;

static pthread_t gst_app_thread;
static pthread_key_t current_jni_env;
static JavaVM *java_vm;
static jfieldID custom_data_field_id;
static jmethodID on_state_method_id;
static jmethodID on_log_method_id;

/* ---- JNI thread plumbing ---- */

static JNIEnv *attach_current_thread(void)
{
    JNIEnv *env;
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_4;
    args.name = "GstThread";
    args.group = NULL;
    if ((*java_vm)->AttachCurrentThread(java_vm, &env, &args) < 0) {
        LOGE("failed to attach current thread");
        return NULL;
    }
    return env;
}

static void detach_current_thread(void *env)
{
    (void) env;
    (*java_vm)->DetachCurrentThread(java_vm);
}

static JNIEnv *get_jni_env(void)
{
    JNIEnv *env = (JNIEnv *) pthread_getspecific(current_jni_env);
    if (env == NULL) {
        env = attach_current_thread();
        pthread_setspecific(current_jni_env, env);
    }
    return env;
}

static void notify_state(CustomData *data, int state)
{
    JNIEnv *env = get_jni_env();
    if (data->app == NULL || on_state_method_id == NULL) {
        return;
    }
    (*env)->CallVoidMethod(env, data->app, on_state_method_id, state);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

/* mirror an important diagnostic line to Kotlin (which persists it to a file the user can
 * read on the phone), in addition to logcat. */
static void notify_log(CustomData *data, const char *msg)
{
    LOGI("%s", msg);
    JNIEnv *env = get_jni_env();
    if (data->app == NULL || on_log_method_id == NULL) {
        return;
    }
    jstring s = (*env)->NewStringUTF(env, msg);
    (*env)->CallVoidMethod(env, data->app, on_log_method_id, s);
    (*env)->DeleteLocalRef(env, s);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

/* ---- bus callbacks (run on the loop thread) ---- */

static void error_cb(GstBus *bus, GstMessage *msg, CustomData *data)
{
    GError *err;
    gchar *debug;
    gst_message_parse_error(msg, &err, &debug);
    LOGE("error from %s: %s", GST_OBJECT_NAME(msg->src), err->message);
    g_clear_error(&err);
    g_free(debug);
    /* do NOT touch the pipeline here; the app drives restarts via play() */
    notify_state(data, ST_ERROR);
}

static void eos_cb(GstBus *bus, GstMessage *msg, CustomData *data)
{
    LOGI("end of stream");
    notify_state(data, ST_ENDED);
}

static void state_changed_cb(GstBus *bus, GstMessage *msg, CustomData *data)
{
    GstState old_state, new_state, pending_state;
    if (GST_MESSAGE_SRC(msg) != GST_OBJECT(data->pipeline)) {
        return;
    }
    gst_message_parse_state_changed(msg, &old_state, &new_state, &pending_state);
    if (new_state == GST_STATE_PLAYING) {
        notify_state(data, ST_PLAYING);
    }
}

static GstPadProbeReturn frame_probe_cb(GstPad *pad, GstPadProbeInfo *info, gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;
    g_atomic_int_inc(&data->frame_count);
    return GST_PAD_PROBE_OK;
}

static void apply_overlay(CustomData *data)
{
    if (data->video_sink == NULL || data->native_window == NULL) {
        return;
    }
    gst_video_overlay_set_window_handle(
        GST_VIDEO_OVERLAY(data->video_sink), (guintptr) data->native_window);
    gst_video_overlay_expose(GST_VIDEO_OVERLAY(data->video_sink));
}

/* decodebin adds the actual decoder dynamically; log which one it picked so we can tell a
 * hardware MediaCodec decoder (amc*, smooth) from a software fallback (avdec*, slideshow). */
static void deep_element_added_cb(GstBin *pipeline, GstBin *sub_bin, GstElement *element, gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;
    GstElementFactory *factory = gst_element_get_factory(element);
    if (factory == NULL) {
        return;
    }
    const gchar *klass = gst_element_factory_get_klass(factory);
    if (klass != NULL && strstr(klass, "Decoder") != NULL && strstr(klass, "Video") != NULL) {
        const gchar *name = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
        gboolean hardware = (name != NULL && strncmp(name, "amc", 3) == 0);
        gchar *msg = g_strdup_printf("video decoder: %s (%s)", name ? name : "?",
                                     hardware ? "hardware" : "SOFTWARE - expect slideshow");
        notify_log(data, msg);
        g_free(msg);
    }
}

/* ---- pipeline lifecycle (all on the loop thread) ---- */

static void teardown_pipeline(CustomData *data)
{
    if (data->bus_source != NULL) {
        g_source_destroy(data->bus_source);
        g_source_unref(data->bus_source);
        data->bus_source = NULL;
    }
    if (data->video_sink != NULL) {
        gst_object_unref(data->video_sink);
        data->video_sink = NULL;
    }
    if (data->pipeline != NULL) {
        gst_element_set_state(data->pipeline, GST_STATE_NULL);
        gst_object_unref(data->pipeline);
        data->pipeline = NULL;
    }
    g_atomic_int_set(&data->frame_count, 0);
}

static void build_pipeline(CustomData *data)
{
    if (data->uri == NULL) {
        return;
    }
    gchar *desc = g_strdup_printf(
        "rtspsrc location=%s latency=100 ! rtph265depay ! h265parse ! "
        "decodebin ! glimagesink name=vsink sync=false",
        data->uri);

    GError *error = NULL;
    data->pipeline = gst_parse_launch(desc, &error);
    g_free(desc);
    if (error != NULL) {
        LOGE("pipeline parse error: %s", error->message);
        g_clear_error(&error);
        notify_state(data, ST_ERROR);
        return;
    }

    g_signal_connect(data->pipeline, "deep-element-added", (GCallback) deep_element_added_cb, data);

    data->video_sink = gst_bin_get_by_name(GST_BIN(data->pipeline), "vsink");
    apply_overlay(data);

    if (data->video_sink != NULL) {
        GstPad *sinkpad = gst_element_get_static_pad(data->video_sink, "sink");
        if (sinkpad != NULL) {
            gst_pad_add_probe(sinkpad, GST_PAD_PROBE_TYPE_BUFFER, frame_probe_cb, data, NULL);
            gst_object_unref(sinkpad);
        }
    }

    GstBus *bus = gst_element_get_bus(data->pipeline);
    data->bus_source = gst_bus_create_watch(bus);
    g_source_set_callback(data->bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach(data->bus_source, data->context);
    g_signal_connect(G_OBJECT(bus), "message::error", (GCallback) error_cb, data);
    g_signal_connect(G_OBJECT(bus), "message::eos", (GCallback) eos_cb, data);
    g_signal_connect(G_OBJECT(bus), "message::state-changed", (GCallback) state_changed_cb, data);
    gst_object_unref(bus);
}

/* (re)build and play; runs on the loop thread */
static gboolean do_rebuild(gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;
    teardown_pipeline(data);
    build_pipeline(data);
    if (data->pipeline != NULL) {
        data->rebuild_count++;
        gchar *msg = g_strdup_printf("pipeline (re)build #%d", data->rebuild_count);
        notify_log(data, msg);
        g_free(msg);
        gst_element_set_state(data->pipeline, GST_STATE_PLAYING);
    }
    return G_SOURCE_REMOVE;
}

/* ---- GLib main loop thread ---- */

static void *app_function(void *userdata)
{
    CustomData *data = (CustomData *) userdata;
    g_main_context_push_thread_default(data->context);
    LOGI("gst main loop running");
    g_main_loop_run(data->main_loop);
    LOGI("gst main loop exiting");
    teardown_pipeline(data);
    g_main_context_pop_thread_default(data->context);
    return NULL;
}

/* ---- JNI entry points ---- */

static void gst_native_init(JNIEnv *env, jobject thiz)
{
    CustomData *data = g_new0(CustomData, 1);
    (*env)->SetLongField(env, thiz, custom_data_field_id, (jlong) (gsize) data);
    data->app = (*env)->NewGlobalRef(env, thiz);
    data->context = g_main_context_new();
    data->main_loop = g_main_loop_new(data->context, FALSE);
    pthread_create(&gst_app_thread, NULL, &app_function, data);
}

static void gst_native_finalize(JNIEnv *env, jobject thiz)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return;
    }
    if (data->main_loop != NULL) {
        g_main_loop_quit(data->main_loop);
    }
    pthread_join(gst_app_thread, NULL);
    if (data->main_loop != NULL) {
        g_main_loop_unref(data->main_loop);
    }
    if (data->context != NULL) {
        g_main_context_unref(data->context);
    }
    if (data->native_window != NULL) {
        ANativeWindow_release(data->native_window);
    }
    (*env)->DeleteGlobalRef(env, data->app);
    g_free(data->uri);
    g_free(data);
    (*env)->SetLongField(env, thiz, custom_data_field_id, 0);
}

static void gst_native_set_uri(JNIEnv *env, jobject thiz, jstring uri)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return;
    }
    const gchar *s = (*env)->GetStringUTFChars(env, uri, NULL);
    g_free(data->uri);
    data->uri = g_strdup(s);
    (*env)->ReleaseStringUTFChars(env, uri, s);
}

static void gst_native_play(JNIEnv *env, jobject thiz)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return;
    }
    notify_state(data, ST_CONNECTING);
    g_main_context_invoke(data->context, do_rebuild, data);
}

/* surface arrives/changes: attach the window (overlay calls are thread-safe). The
 * pipeline keeps running when the surface goes away, so the feed stays live. */
static void gst_native_surface_init(JNIEnv *env, jobject thiz, jobject surface)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return;
    }
    ANativeWindow *new_window = ANativeWindow_fromSurface(env, surface);
    if (data->native_window != NULL) {
        ANativeWindow_release(data->native_window);
    }
    data->native_window = new_window;
    apply_overlay(data);
}

static void gst_native_surface_finalize(JNIEnv *env, jobject thiz)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return;
    }
    if (data->video_sink != NULL) {
        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(data->video_sink), (guintptr) NULL);
    }
    if (data->native_window != NULL) {
        ANativeWindow_release(data->native_window);
        data->native_window = NULL;
    }
}

static jint gst_native_frame_count(JNIEnv *env, jobject thiz)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return 0;
    }
    return (jint) g_atomic_int_get(&data->frame_count);
}

static jboolean gst_native_class_init(JNIEnv *env, jclass klass)
{
    custom_data_field_id = (*env)->GetFieldID(env, klass, "nativeCustomData", "J");
    on_state_method_id = (*env)->GetMethodID(env, klass, "onNativeState", "(I)V");
    on_log_method_id = (*env)->GetMethodID(env, klass, "onNativeLog", "(Ljava/lang/String;)V");
    if (custom_data_field_id == NULL || on_state_method_id == NULL || on_log_method_id == NULL) {
        LOGE("GStreamerPlayer is missing nativeCustomData / onNativeState / onNativeLog");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static JNINativeMethod native_methods[] = {
    {"nativeInit", "()V", (void *) gst_native_init},
    {"nativeFinalize", "()V", (void *) gst_native_finalize},
    {"nativeSetUri", "(Ljava/lang/String;)V", (void *) gst_native_set_uri},
    {"nativePlay", "()V", (void *) gst_native_play},
    {"nativeSurfaceInit", "(Landroid/view/Surface;)V", (void *) gst_native_surface_init},
    {"nativeSurfaceFinalize", "()V", (void *) gst_native_surface_finalize},
    {"nativeFrameCount", "()I", (void *) gst_native_frame_count},
    {"nativeClassInit", "()Z", (void *) gst_native_class_init},
};

jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    java_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("could not retrieve JNIEnv");
        return 0;
    }
    jclass klass = (*env)->FindClass(env, "com/brushlesswhoop/missinglynk/GStreamerPlayer");
    (*env)->RegisterNatives(env, klass, native_methods, G_N_ELEMENTS(native_methods));
    pthread_key_create(&current_jni_env, detach_current_thread);
    return JNI_VERSION_1_4;
}
