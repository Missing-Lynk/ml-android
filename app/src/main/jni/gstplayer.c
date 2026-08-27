/*
 * JNI bridge between the Kotlin GStreamerPlayer and a GStreamer pipeline, adapted from
 * the GStreamer Android "tutorial 5". A GLib main loop runs on its own thread and owns the
 * pipeline; play/stop from the app are dispatched onto that loop thread (via
 * g_main_context_invoke) so all pipeline lifecycle happens on one thread (no races).
 *
 * Pipeline: rtspsrc feeds a tee of encoded access units, built in pad_added_cb once the SDP has
 * named the codec (H.265 by default, H.264 when the goggle's DVR codec setting selects it):
 *
 *   rtspsrc ! rtpXdepay ! Xparse ! tee ! queue ! decodebin ! glimagesink sync=false
 *
 * sync=false renders frames as they arrive, which the goggle's clockless stream needs.
 *
 * The restream is a pipeline of its own:
 *
 *   appsrc ! queue(leaky) ! Xparse ! flvmux ! rtmp2sink    [only while streaming]
 *            audiotestsrc(silence) ! voaacenc ! flvmux.
 *
 * A pad probe on the tee's sink pad copies each access unit into that appsrc and drops the push
 * return. The constraint that shape exists to hold: the egress must share no flow-return path
 * and no bus with the leg carrying the picture, because a tee combines its branches' flow
 * returns, and a destination that refuses, times out or dies would otherwise take the picture
 * down with it. The egress forwards the goggle's own encoding, so the phone never re-encodes,
 * and its queue leaks because a stalled uplink must drop access units rather than accumulate.
 *
 * The egress pipeline is slaved to the player's clock and base time, so the silent audio track
 * it generates shares a timeline with video timestamped by the player.
 *
 * Renders into an ANativeWindow from the app's TextureView Surface; the pipeline keeps
 * running when the surface goes away (app backgrounded) so the feed stays live on return.
 * Player state, the negotiated codec and a frame counter are reported back to Kotlin.
 */
#include <string.h>
#include <stdint.h>
#include <jni.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/app/gstappsrc.h>
#include <gst/video/videooverlay.h>

#define TAG "GstPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* state codes shared with Kotlin (mapped to PlayerEvent there) */
#define ST_CONNECTING 0
#define ST_PLAYING    1
#define ST_ERROR      2
#define ST_ENDED      3

typedef struct _CustomData
{
    jobject app;               /* global ref to the Kotlin GStreamerPlayer */
    GstElement *pipeline;
    GstElement *video_sink;    /* the GstVideoOverlay (glimagesink) */
    GstElement *downstream;    /* everything below rtspsrc, built once the codec is known */
    gchar *rtmp_url;           /* restream destination, key included; never logged */
    gboolean restream_armed;   /* whether the egress should be live */
    gboolean restream_use_mic; /* the audio track carries the microphone rather than silence */
    gboolean restream_mic_failed; /* the microphone would not open; latched to silence */
    gboolean codec_is_h264;    /* what the SDP named, so the egress can be built any time */
    GstElement *restream_pipeline; /* the egress pipeline while streaming, else NULL */
    GstElement *restream_src;  /* its appsrc; only ever touched with the feed probe removed */
    GstElement *restream_queue; /* the egress queue, read for its level; same lifetime as _src */
    gint restream_congested;   /* the feed is waiting out a backlog (g_atomic) */
    gint restream_dropped;     /* access units the backlog has cost this session (g_atomic) */
    GSource *restream_bus_source; /* watch on the egress pipeline's own bus */
    GstPad *restream_feed_pad; /* the tee sink pad the feed probe sits on */
    gulong restream_feed_probe; /* that probe's id, 0 when none is installed */
    gint restream_needs_key;   /* feed nothing until a key frame (g_atomic) */
    gint restream_is_live;     /* the egress is carrying, as last reported to the app (g_atomic) */
    guint restream_retry_id;   /* pending reconnect timeout, 0 when none */
    guint restream_backoff_ms; /* delay the next reconnect waits */
    gint64 restream_started_us; /* when the egress was started, for the backoff reset */
    gboolean restream_reported_down; /* the outage has been logged; cleared once it carries */
    gint restream_sink_count;  /* buffers the egress sink has accepted (g_atomic) */
    GMutex restream_feed_lock; /* guards the four fields below, written from two threads */
    GstBuffer *restream_last_key; /* the newest key access unit, re-sent to hold a gap open */
    GstClockTime restream_out_pts; /* PTS of the last access unit handed to the appsrc */
    GstClockTime restream_pts_offset; /* added to incoming PTS; re-derived when the feed resumes */
    gboolean restream_offset_valid; /* whether the offset above describes the current feed */
    gint64 restream_fed_us;    /* when the feed last handed anything over, for the gap detector */
    gboolean restream_h264;    /* the codec the egress was built for */
    guint restream_hold_id;    /* the keep-alive timeout, 0 when the egress is down */
    gint watch_last_sink;      /* the two counts the stall watchdog compared last time, */
    gint watch_last_frames;    /* -1 when it has nothing to compare against yet */
    gint watch_reported_congested; /* the backlog state the watchdog last reported */
    GMainContext *context;
    GMainLoop *main_loop;
    GSource *bus_source;       /* bus watch source, removed on teardown */
    ANativeWindow *native_window;
    gchar *uri;
    gint frame_count;          /* buffers reaching the sink (g_atomic); 0 => no media flowing */
    gint rebuild_count;        /* how many times the pipeline has been (re)built this session */
    gint generation;           /* bumped by play(); see frame_probe_ctx */
    pthread_t thread;          /* the GLib loop thread owning this instance's pipeline */
} CustomData;

/* Buffers from a previous pipeline can still reach its sink after play() has reset the count
 * (the rebuild happens later, on the loop thread), which would let a dead session look like it
 * is playing. Each pipeline's probe carries the generation it was built for and only counts
 * while that is still current. */
typedef struct _FrameProbeCtx
{
    CustomData *data;
    gint generation;
} FrameProbeCtx;

static pthread_key_t current_jni_env;
static JavaVM *java_vm;
static jfieldID custom_data_field_id;
static jmethodID on_state_method_id;
static jmethodID on_log_method_id;
static jmethodID on_codec_method_id;
static jmethodID on_restream_method_id;
static jmethodID on_restream_live_method_id;
static jmethodID on_audio_method_id;

/* JNI thread plumbing */
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

/* the state code alone says only "it failed"; reason carries what identifies the failure
 * (a refused connection, a missing decoder, a broken SDP) and is NULL for every other state. */
static void notify_state(CustomData *data, int state, const char *reason)
{
    JNIEnv *env = get_jni_env();
    if (data->app == NULL || on_state_method_id == NULL) {
        return;
    }

    jstring s = NULL;
    if (reason != NULL) {
        s = (*env)->NewStringUTF(env, reason);
    }

    (*env)->CallVoidMethod(env, data->app, on_state_method_id, state, s);
    if (s != NULL) {
        (*env)->DeleteLocalRef(env, s);
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

/* Report the codec the SDP negotiated ("H264" / "H265"). The app needs it to decide whether a
 * destination will accept the stream: Twitch and Kick take H.264 only. */
static void notify_codec(CustomData *data, const char *codec)
{
    JNIEnv *env = get_jni_env();
    if (data->app == NULL || on_codec_method_id == NULL) {
        return;
    }

    jstring s = (*env)->NewStringUTF(env, codec);
    (*env)->CallVoidMethod(env, data->app, on_codec_method_id, s);
    (*env)->DeleteLocalRef(env, s);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

/* Report what the egress's audio track is actually carrying. The app shows the setting on the
 * broadcast badge, and a microphone that would not open latches this to silence: a badge naming a
 * source that is not being captured is worse than one naming none. */
static void notify_audio(CustomData *data, gboolean using_mic)
{
    JNIEnv *env = get_jni_env();
    if (data->app == NULL || on_audio_method_id == NULL) {
        return;
    }

    (*env)->CallVoidMethod(env, data->app, on_audio_method_id, (jboolean) using_mic);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

/* Report that the egress gave up, carrying the reason. The video is unaffected and
 * keeps running; the app clears its live indicator and shows this text. */
static void notify_restream(CustomData *data, const char *reason)
{
    JNIEnv *env = get_jni_env();
    if (data->app == NULL || on_restream_method_id == NULL) {
        return;
    }

    jstring s = (*env)->NewStringUTF(env, reason);
    (*env)->CallVoidMethod(env, data->app, on_restream_method_id, s);
    (*env)->DeleteLocalRef(env, s);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

/* Report whether the egress is carrying. The app shows this while the restream is armed, so a
 * broadcast that has dropped and is reconnecting is visible without opening anything: the
 * reconnect is silent by design, and a status that only said "armed" would look identical. */
static void notify_restream_live(CustomData *data, gboolean live)
{
    JNIEnv *env = get_jni_env();
    if (data->app == NULL || on_restream_live_method_id == NULL) {
        return;
    }

    (*env)->CallVoidMethod(env, data->app, on_restream_live_method_id, (jboolean) live);
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

static gboolean restream_start(gpointer user_data);
static void restream_stop(CustomData *data, gboolean retry);
static void restream_detach_feed(CustomData *data);
static void restream_cancel_retry(CustomData *data);

/* bus callbacks (run on the loop thread) */
static void error_cb(GstBus *bus, GstMessage *msg, CustomData *data)
{
    GError *err;
    gchar *debug;
    gst_message_parse_error(msg, &err, &debug);

    /* the state code alone says only "it failed"; the message is what identifies a refused
     * connection, a missing decoder or a broken SDP, so it goes to the shared log too */
    gchar *line = g_strdup_printf("error from %s: %s", GST_OBJECT_NAME(msg->src), err->message);
    LOGE("%s", line);
    notify_log(data, line);

    /* This bus carries the player only; the egress has its own. Every message arriving here is
     * therefore a playback failure. */
    /* do NOT touch the pipeline here; the app drives restarts via play() */
    notify_state(data, ST_ERROR, line);
    g_free(line);
    g_clear_error(&err);
    g_free(debug);
}

static void eos_cb(GstBus *bus, GstMessage *msg, CustomData *data)
{
    LOGI("end of stream");
    notify_state(data, ST_ENDED, NULL);
}

static void state_changed_cb(GstBus *bus, GstMessage *msg, CustomData *data)
{
    GstState old_state, new_state, pending_state;
    if (GST_MESSAGE_SRC(msg) != GST_OBJECT(data->pipeline)) {
        return;
    }

    gst_message_parse_state_changed(msg, &old_state, &new_state, &pending_state);
    if (new_state == GST_STATE_PLAYING) {
        notify_state(data, ST_PLAYING, NULL);
    }
}

static GstPadProbeReturn frame_probe_cb(GstPad *pad, GstPadProbeInfo *info, gpointer user_data)
{
    FrameProbeCtx *ctx = (FrameProbeCtx *) user_data;
    if (g_atomic_int_get(&ctx->data->generation) == ctx->generation) {
        g_atomic_int_inc(&ctx->data->frame_count);
    }

    return GST_PAD_PROBE_OK;
}

static void frame_probe_ctx_free(gpointer user_data)
{
    g_free(user_data);
}

/* Count buffers reaching the video sink, so the app can tell a live pipeline from a dead one.
 * The probe carries the generation it was built for; see frame_probe_ctx. */
static void attach_frame_probe(CustomData *data)
{
    if (data->video_sink == NULL) {
        return;
    }

    GstPad *sinkpad = gst_element_get_static_pad(data->video_sink, "sink");
    if (sinkpad == NULL) {
        return;
    }

    FrameProbeCtx *ctx = g_new0(FrameProbeCtx, 1);
    ctx->data = data;
    ctx->generation = g_atomic_int_get(&data->generation);
    gst_pad_add_probe(sinkpad, GST_PAD_PROBE_TYPE_BUFFER, frame_probe_cb, ctx,
                      frame_probe_ctx_free);
    gst_object_unref(sinkpad);
}

static void apply_overlay(CustomData *data)
{
    if (data->video_sink == NULL || data->native_window == NULL) {
        return;
    }

    /* the sink is only an overlay when it renders; a non-rendering sink is legitimate */
    if (!GST_IS_VIDEO_OVERLAY(data->video_sink)) {
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

/* pipeline lifecycle (all on the loop thread) */
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

    /* The egress is a pipeline of its own, and only the feed probe belongs to the one going
     * away, so an armed broadcast rides a rebuild out rather than ending with it. That is what
     * lets the audio track hold the RTMP session open across a battery swap; the hold frames
     * keep the video track advancing for the same reason. The next build re-attaches the feed. */
    if (data->restream_armed && data->restream_pipeline != NULL) {
        restream_detach_feed(data);
    } else {
        restream_stop(data, FALSE);
        restream_cancel_retry(data);
    }

    /* owned by the pipeline, which is about to be unreffed; drop the borrowed handle */
    data->downstream = NULL;

    if (data->pipeline != NULL) {
        gst_element_set_state(data->pipeline, GST_STATE_NULL);
        gst_object_unref(data->pipeline);
        data->pipeline = NULL;
    }

    g_atomic_int_set(&data->frame_count, 0);
}

/* The half of the pipeline below rtspsrc, built once the SDP has named the codec.
 *
 * Written as a parse description rather than assembled element by element, because the shape is
 * the interesting part and reads as a pipeline. The tee carries encoded access units; the egress
 * reads them from its sink pad without taking a leg of its own, so the tee has one branch and
 * exists as the point the egress taps.
 */
static GstElement *build_downstream(CustomData *data, gboolean h264)
{
    const gchar *depay = h264 ? "rtph264depay" : "rtph265depay";
    const gchar *parse = h264 ? "h264parse" : "h265parse";
    const gchar *media = h264 ? "video/x-h264,stream-format=avc,alignment=au"
                              : "video/x-h265,stream-format=hvc1,alignment=au";
    (void) media;
    gchar *desc = g_strdup_printf(
        "%s name=depay ! %s ! tee name=t "
        "t. ! queue ! decodebin ! glimagesink name=vsink sync=false",
        depay, parse);

    GError *error = NULL;
    GstElement *bin = gst_parse_bin_from_description(desc, FALSE, &error);
    g_free(desc);
    if (error != NULL) {
        gchar *line = g_strdup_printf("downstream parse error: %s", error->message);
        notify_log(data, line);
        notify_state(data, ST_ERROR, line);
        g_free(line);
        g_clear_error(&error);
        return NULL;
    }

    /* Ghost the depayloader's sink onto the bin under a known name. Letting
     * gst_parse_bin_from_description ghost it automatically names the pad after whatever it
     * finds, which is not a contract worth linking against. */
    GstElement *depay_element = gst_bin_get_by_name(GST_BIN(bin), "depay");
    if (depay_element == NULL) {
        gst_object_unref(bin);
        return NULL;
    }

    GstPad *depay_sink = gst_element_get_static_pad(depay_element, "sink");
    gst_object_unref(depay_element);
    if (depay_sink == NULL) {
        gst_object_unref(bin);
        return NULL;
    }

    GstPad *ghost = gst_ghost_pad_new("sink", depay_sink);
    gst_object_unref(depay_sink);
    if (ghost == NULL || !gst_element_add_pad(bin, ghost)) {
        gst_object_unref(bin);
        return NULL;
    }

    return bin;
}

/* rtspsrc produces its pads only after the SDP arrives, which is the first moment the codec is
 * known. Everything below the source is therefore built here rather than at parse time. */
static void pad_added_cb(GstElement *src, GstPad *pad, CustomData *data)
{
    GstCaps *caps = gst_pad_get_current_caps(pad);
    if (caps == NULL) {
        caps = gst_pad_query_caps(pad, NULL);
    }
    if (caps == NULL) {
        return;
    }

    const GstStructure *s = gst_caps_get_structure(caps, 0);
    const gchar *encoding = gst_structure_get_string(s, "encoding-name");
    if (encoding == NULL) {
        notify_log(data, "pad has no encoding-name; ignoring it");
        gst_caps_unref(caps);
        return;
    }

    gboolean h264 = g_ascii_strcasecmp(encoding, "H264") == 0;
    gboolean h265 = g_ascii_strcasecmp(encoding, "H265") == 0 ||
                    g_ascii_strcasecmp(encoding, "HEVC") == 0;
    if (!h264 && !h265) {
        /* a non-video pad (rtspsrc exposes one per stream); nothing to attach */
        gst_caps_unref(caps);
        return;
    }

    if (data->downstream != NULL) {
        /* the video stream is already attached; ignore a second video pad */
        gst_caps_unref(caps);
        return;
    }

    gchar *line = g_strdup_printf("stream codec: %s", h264 ? "H.264" : "H.265");
    notify_log(data, line);
    g_free(line);
    data->codec_is_h264 = h264;
    notify_codec(data, h264 ? "H264" : "H265");
    gst_caps_unref(caps);

    GstElement *bin = build_downstream(data, h264);
    if (bin == NULL) {
        return;
    }

    data->downstream = bin;
    gst_bin_add(GST_BIN(data->pipeline), bin);

    data->video_sink = gst_bin_get_by_name(GST_BIN(bin), "vsink");
    apply_overlay(data);
    attach_frame_probe(data);

    GstPad *sinkpad = gst_element_get_static_pad(bin, "sink");
    if (sinkpad != NULL) {
        GstPadLinkReturn linked = gst_pad_link(pad, sinkpad);
        if (linked != GST_PAD_LINK_OK) {
            gchar *why = g_strdup_printf("could not link rtspsrc to the decode chain: %s",
                                         gst_pad_link_get_name(linked));
            notify_log(data, why);
            g_free(why);
        }
        gst_object_unref(sinkpad);
    }

    gst_element_sync_state_with_parent(bin);

    /* A rebuild replaces everything below rtspsrc and the egress goes with it, while the
     * destination stays armed. Start it against the new tee, so a reconnect of the picture
     * carries the broadcast with it. Queued rather than called: this runs on rtspsrc's streaming
     * thread, and rtmp2sink connects synchronously as it comes up, which would hold the thread
     * that carries the picture for as long as the destination took to answer. */
    if (data->restream_armed) {
        g_main_context_invoke(data->context, restream_start, data);
    }
}

/* the egress, a pipeline of its own fed from the player's tee */
/* Longest a reconnect waits. An uplink that comes back does so within seconds, and a wrong
 * stream key is worth retrying at a slow idle rather than giving up on: the pilot is flying and
 * cannot fix it now. */
#define RESTREAM_BACKOFF_CAP_MS 10000
/* A session that ran this long counts as one that worked, so its failure starts the backoff
 * over instead of inheriting the delay from an earlier bad patch. */
#define RESTREAM_GOOD_RUN_US (30 * G_USEC_PER_SEC)
/* How often the stall watchdog samples. A stall is declared after one full quiet interval, so
 * recovery starts between one and two of these. */
#define RESTREAM_WATCHDOG_S 5
/* How much encoded video the egress may leave unsent before the queue starts dropping it. The
 * queue dropping is the backstop; the feed below stops well short of it, because a queue sheds
 * whichever buffers it holds and the middle of a GOP is not a thing a destination can decode. */
#define RESTREAM_QUEUE_BYTES 4194304
/* Backlog at which the feed stops handing over access units, and the backlog it waits to fall
 * back to before resuming. Both sit under the queue's own limit so that the queue never reaches
 * it, and the gap between them keeps a feed that is barely keeping up from stopping and starting
 * once per access unit. */
#define RESTREAM_FEED_HIGH_BYTES (RESTREAM_QUEUE_BYTES / 2)
#define RESTREAM_FEED_LOW_BYTES (RESTREAM_QUEUE_BYTES / 8)
/* How often a gap re-sends the last key access unit, and how quiet the feed must be before that
 * starts. One frame a second is enough to keep the video track advancing, which is what an
 * ingest measures the session by; the threshold sits above a single late access unit so an
 * ordinary jitter does not inject one. */
#define RESTREAM_HOLD_MS 1000
#define RESTREAM_GAP_US (1500 * 1000)

static void restream_cancel_retry(CustomData *data)
{
    if (data->restream_retry_id == 0) {
        return;
    }

    GSource *source = g_main_context_find_source_by_id(data->context, data->restream_retry_id);
    if (source != NULL) {
        g_source_destroy(source);
    }
    data->restream_retry_id = 0;
}

static gboolean restream_retry_cb(gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;

    data->restream_retry_id = 0;
    if (data->restream_armed) {
        restream_start(data);
    }
    return G_SOURCE_REMOVE;
}

/* Queue the next reconnect attempt. Doubling from a second to the cap keeps a destination that
 * refuses every attempt from turning into a connect storm. */
static void restream_schedule_retry(CustomData *data)
{
    if (data->restream_retry_id != 0 || !data->restream_armed) {
        return;
    }

    if (data->restream_backoff_ms == 0) {
        data->restream_backoff_ms = 1000;
    }

    /* Latched: an absent destination fails every cycle, and one line per cycle would bury the
     * log. The recovery is reported when the egress carries again. */
    if (!data->restream_reported_down) {
        gchar *line = g_strdup_printf("restream reconnecting in %u ms", data->restream_backoff_ms);
        notify_log(data, line);
        g_free(line);
        data->restream_reported_down = TRUE;
    }

    GSource *source = g_timeout_source_new(data->restream_backoff_ms);
    g_source_set_callback(source, restream_retry_cb, data, NULL);
    data->restream_retry_id = g_source_attach(source, data->context);
    g_source_unref(source);

    data->restream_backoff_ms = MIN(data->restream_backoff_ms * 2, RESTREAM_BACKOFF_CAP_MS);
}

/* Bytes the egress is holding but has not sent. Zero when there is no egress to ask. */
static guint restream_backlog(CustomData *data)
{
    guint level = 0;

    if (data->restream_queue != NULL) {
        g_object_get(data->restream_queue, "current-level-bytes", &level, NULL);
    }
    return level;
}

/* Hand one access unit to the egress, timestamped onto the egress's own timeline.
 *
 * The offset exists because the two pipelines no longer share a base time. A rebuilt player
 * starts its timestamps over while the egress has been running underneath it and its audio track
 * has not, so the raw values would put the video as far behind the sound as the outage was long.
 * It is re-derived on the first access unit after every resume, against the egress's running
 * time, which is the same clock the audio is being stamped from.
 *
 * `is_key` buffers are kept: a gap re-sends the last one to hold the session open. Runs on the
 * player's streaming thread and on the loop thread, hence the lock.
 */
static void restream_push(CustomData *data, GstBuffer *buffer, gboolean is_key,
                          gboolean from_feed)
{
    g_mutex_lock(&data->restream_feed_lock);
    if (data->restream_src == NULL) {
        g_mutex_unlock(&data->restream_feed_lock);
        return;
    }

    GstClockTime pts = GST_BUFFER_PTS(buffer);

    /* Where the resumed feed picks the timeline up. The hold frames advanced it in real time
     * while the feed was away, so continuing from it puts the video back beside the audio; the
     * first feed of a session has nothing to continue from and needs no offset, which is what
     * two pipelines sharing a base time already gives.
     *
     * Derived from the timeline rather than from the pipeline clock because a rebuild re-attaches
     * the feed before the egress reports a running time, and an offset of zero taken then would
     * leave every access unit behind the timeline and spaced a millisecond apart by the clamp
     * below. */
    if (from_feed && !data->restream_offset_valid && GST_CLOCK_TIME_IS_VALID(pts)) {
        data->restream_pts_offset =
            (GST_CLOCK_TIME_IS_VALID(data->restream_out_pts) &&
             data->restream_out_pts + GST_MSECOND > pts)
                ? data->restream_out_pts + GST_MSECOND - pts
                : 0;
        data->restream_offset_valid = TRUE;
    }

    GstClockTime out;
    if (from_feed && GST_CLOCK_TIME_IS_VALID(pts)) {
        out = pts + data->restream_pts_offset;
    } else {
        /* A hold frame is a copy of an access unit already sent, so the timestamp it carries is
         * the one it went out under. It is stamped where the egress has actually reached
         * instead, which is what keeps the video track advancing in step with the audio track
         * beside it. */
        GstClockTime now = gst_element_get_current_running_time(data->restream_pipeline);
        if (GST_CLOCK_TIME_IS_VALID(now)) {
            out = now;
        } else if (GST_CLOCK_TIME_IS_VALID(data->restream_out_pts)) {
            out = data->restream_out_pts + RESTREAM_HOLD_MS * GST_MSECOND;
        } else {
            out = 0;
        }
    }

    /* A muxer needs its timestamps to keep going forwards, and a resumed feed can hand over an
     * access unit older than a hold frame already sent. */
    if (GST_CLOCK_TIME_IS_VALID(data->restream_out_pts) && out <= data->restream_out_pts) {
        out = data->restream_out_pts + GST_MSECOND;
    }

    /* Both encoders in use are configured for low delay, so the stream carries no B-frames:
     * decode order is presentation order and one timestamp serves both. */
    GstBuffer *copy = gst_buffer_copy(buffer);
    GST_BUFFER_PTS(copy) = out;
    GST_BUFFER_DTS(copy) = out;
    data->restream_out_pts = out;
    if (from_feed) {
        /* Only the feed marks the timeline alive. A hold frame counting as one would push the
         * next hold out by the gap threshold and halve the rate they go out at. */
        data->restream_fed_us = g_get_monotonic_time();
    }

    if (is_key) {
        gst_buffer_replace(&data->restream_last_key, buffer);
    }

    gst_app_src_push_buffer(GST_APP_SRC(data->restream_src), copy);
    g_mutex_unlock(&data->restream_feed_lock);
}

/* Re-send the last key access unit while the feed is quiet, so the video track keeps advancing
 * through a battery swap instead of stopping dead for the length of one.
 *
 * Re-sending a key frame breaks the reference chain the goggle is still building against, so the
 * feed is put back behind its key-frame gate: whatever arrives next is dropped until the goggle's
 * own next one, which with GOP 60 is inside a second. Runs on the loop thread.
 */
static gboolean restream_hold_cb(gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;

    if (data->restream_pipeline == NULL ||
        g_get_monotonic_time() - data->restream_fed_us < RESTREAM_GAP_US) {
        return G_SOURCE_CONTINUE;
    }

    g_mutex_lock(&data->restream_feed_lock);
    GstBuffer *held = data->restream_last_key ? gst_buffer_ref(data->restream_last_key) : NULL;
    g_mutex_unlock(&data->restream_feed_lock);
    if (held == NULL) {
        /* nothing has been carried yet, so there is no picture to hold */
        return G_SOURCE_CONTINUE;
    }

    restream_push(data, held, FALSE, FALSE);
    gst_buffer_unref(held);
    g_atomic_int_set(&data->restream_needs_key, 1);
    return G_SOURCE_CONTINUE;
}

/* Copy each access unit the tee is about to carry into the egress pipeline.
 *
 * Runs on the player's streaming thread, so what it must not do is block or report failure: the
 * push return is dropped on purpose. An egress that cannot keep up is the egress pipeline's own
 * problem, and this pad is the one carrying the picture. The probe is removed before the appsrc
 * it writes to is released, and gst_pad_remove_probe waits for a running callback to return, so
 * restream_src and restream_queue are valid for as long as this can run.
 *
 * An uplink slower than the goggle's bitrate is what makes the dropping rule matter. Dropping is
 * whole GOPs: the feed stops at the access unit that finds the backlog too high, which truncates
 * a GOP the destination has already started and can simply end, and resumes at a key frame,
 * which is the only point a destination can start decoding from. Letting the queue shed instead
 * takes buffers out of the middle of a GOP and leaves the frames around them referring to
 * pictures that never arrived.
 *
 * Nothing here reports: the flags it sets are read by the watchdog on the loop thread, because
 * a log line from here would put a JNI upcall and a file append on the thread carrying the
 * picture.
 */
static GstPadProbeReturn restream_feed_cb(GstPad *pad, GstPadProbeInfo *info, gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;
    GstBuffer *buffer = GST_PAD_PROBE_INFO_BUFFER(info);

    if (buffer == NULL || data->restream_src == NULL) {
        return GST_PAD_PROBE_OK;
    }

    guint backlog = restream_backlog(data);

    /* Open on a key frame: the muxer needs the parameter sets that precede one, and a
     * destination handed the middle of a GOP has nothing it can decode until the next. The same
     * gate carries the wait for a backlog to drain, since both end the same way. */
    if (g_atomic_int_get(&data->restream_needs_key)) {
        if (GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT) ||
            backlog > RESTREAM_FEED_LOW_BYTES) {
            if (g_atomic_int_get(&data->restream_congested)) {
                g_atomic_int_inc(&data->restream_dropped);
            }
            return GST_PAD_PROBE_OK;
        }
        g_atomic_int_set(&data->restream_needs_key, 0);
        g_atomic_int_set(&data->restream_congested, 0);
    } else if (backlog > RESTREAM_FEED_HIGH_BYTES) {
        g_atomic_int_set(&data->restream_needs_key, 1);
        g_atomic_int_set(&data->restream_congested, 1);
        g_atomic_int_inc(&data->restream_dropped);
        return GST_PAD_PROBE_OK;
    }

    restream_push(data, buffer, !GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT),
                  TRUE);
    return GST_PAD_PROBE_OK;
}

/* Every buffer the egress sink accepts. The count is what the stall watchdog reads, and the
 * first one also clears the outage latch: reaching PLAYING is not enough, because the sink does
 * that before the connection is proven. */
static GstPadProbeReturn restream_live_cb(GstPad *pad, GstPadProbeInfo *info, gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;

    g_atomic_int_inc(&data->restream_sink_count);
    if (data->restream_reported_down) {
        data->restream_reported_down = FALSE;
        notify_log(data, "restream live");
    }
    if (!g_atomic_int_get(&data->restream_is_live)) {
        g_atomic_int_set(&data->restream_is_live, 1);
        notify_restream_live(data, TRUE);
    }
    return GST_PAD_PROBE_OK;
}

/* Errors from the egress pipeline's own bus. Nothing here can reach the player: this is a
 * different pipeline, with a different bus and a different flow path. */
static void restream_error_cb(GstBus *bus, GstMessage *msg, CustomData *data)
{
    GError *err;
    gchar *debug;
    gst_message_parse_error(msg, &err, &debug);

    gchar *line = g_strdup_printf("error from %s: %s", GST_OBJECT_NAME(msg->src), err->message);
    LOGE("%s", line);
    notify_log(data, line);
    g_free(line);

    /* A microphone that will not open costs the audio track, not the broadcast: the retry below
     * rebuilds with silence and the picture never notices. Latched for the session, because
     * whatever holds the device is unlikely to let go mid-flight. */
    if (data->restream_use_mic && !data->restream_mic_failed &&
        g_strcmp0(GST_OBJECT_NAME(msg->src), "micsrc") == 0) {
        data->restream_mic_failed = TRUE;
        notify_log(data, "microphone would not open, carrying silence instead");
        notify_audio(data, FALSE);
    } else if (!data->restream_reported_down) {
        /* One toast per outage, not one per retry: the latch is still clear on the failure that
         * begins an outage and set for every attempt after it. */
        notify_restream(data, err->message);
    }

    g_clear_error(&err);
    g_free(debug);
    restream_stop(data, TRUE);
}

/* Take the egress down. Runs on the loop thread.
 *
 * `retry` says whether the destination should be tried again, which is what an egress that went
 * down on its own wants and a teardown of the player underneath it does not.
 */
static void restream_detach_feed(CustomData *data)
{
    /* Probe first, so nothing is writing into the appsrc by the time the pad goes. This blocks
     * until a callback already running has returned. */
    if (data->restream_feed_probe != 0) {
        gst_pad_remove_probe(data->restream_feed_pad, data->restream_feed_probe);
        data->restream_feed_probe = 0;
    }
    if (data->restream_feed_pad != NULL) {
        gst_object_unref(data->restream_feed_pad);
        data->restream_feed_pad = NULL;
    }

    /* The next feed re-derives the offset against whatever timeline it arrives on, and starts
     * at a key frame because the one it left off at belongs to a decode chain that is gone. */
    g_mutex_lock(&data->restream_feed_lock);
    data->restream_offset_valid = FALSE;
    g_mutex_unlock(&data->restream_feed_lock);
    g_atomic_int_set(&data->restream_needs_key, 1);
}

static void restream_stop(CustomData *data, gboolean retry)
{
    restream_detach_feed(data);

    if (data->restream_hold_id != 0) {
        GSource *hold = g_main_context_find_source_by_id(data->context, data->restream_hold_id);
        if (hold != NULL) {
            g_source_destroy(hold);
        }
        data->restream_hold_id = 0;
    }

    g_mutex_lock(&data->restream_feed_lock);
    if (data->restream_src != NULL) {
        gst_object_unref(data->restream_src);
        data->restream_src = NULL;
    }
    gst_buffer_replace(&data->restream_last_key, NULL);
    data->restream_out_pts = GST_CLOCK_TIME_NONE;
    g_mutex_unlock(&data->restream_feed_lock);

    if (data->restream_queue != NULL) {
        gst_object_unref(data->restream_queue);
        data->restream_queue = NULL;
    }

    if (data->restream_bus_source != NULL) {
        g_source_destroy(data->restream_bus_source);
        g_source_unref(data->restream_bus_source);
        data->restream_bus_source = NULL;
    }

    if (data->restream_pipeline == NULL) {
        return;
    }

    gst_element_set_state(data->restream_pipeline, GST_STATE_NULL);
    gst_object_unref(data->restream_pipeline);
    data->restream_pipeline = NULL;

    if (g_atomic_int_get(&data->restream_is_live)) {
        g_atomic_int_set(&data->restream_is_live, 0);
        notify_restream_live(data, FALSE);
    }

    if (!data->restream_reported_down) {
        notify_log(data, "restream stopped");
    }

    if (!retry || !data->restream_armed) {
        return;
    }

    /* A session that carried for a while starts the backoff over: the destination works, and
     * this was an interruption rather than a wrong address. */
    if (data->restream_started_us != 0 &&
        g_get_monotonic_time() - data->restream_started_us > RESTREAM_GOOD_RUN_US) {
        data->restream_backoff_ms = 0;
    }
    restream_schedule_retry(data);
}

/* Stop the egress from the app's own request, which is never a reconnect. */
static gboolean restream_stop_cb(gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;

    restream_cancel_retry(data);
    restream_stop(data, FALSE);
    return G_SOURCE_REMOVE;
}

/* A destination can accept the connection and then stop reading, which raises no error at all:
 * the sink stops draining, the leaky queue throws the broadcast away and the app would otherwise
 * report itself live indefinitely. Reconnecting is the only recovery. The picture's own frame
 * count is the control: when it is not advancing either, the feed is what stopped and churning
 * the connection would not help. */
static gboolean restream_watchdog_cb(gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;

    if (!data->restream_armed || data->restream_pipeline == NULL) {
        data->watch_last_sink = -1;
        data->watch_reported_congested = 0;
        return G_SOURCE_CONTINUE;
    }

    /* The feed's backlog state, reported here rather than where it changes. Latched, so a slow
     * uplink writes one line when the feed starts dropping and one when it recovers. */
    gint congested = g_atomic_int_get(&data->restream_congested);
    if (congested != data->watch_reported_congested) {
        data->watch_reported_congested = congested;
        if (congested) {
            notify_log(data, "restream backlogged, dropping whole GOPs until it drains");
        } else {
            gchar *line = g_strdup_printf("restream feeding again, %d access units dropped",
                                          g_atomic_int_get(&data->restream_dropped));
            notify_log(data, line);
            g_free(line);
        }
    }

    gint sink = g_atomic_int_get(&data->restream_sink_count);
    gint frames = g_atomic_int_get(&data->frame_count);
    /* sink > 0 keeps this off the connect path: a session that has never carried is the
     * backoff's business, and rtmp2sink raises its own error when the connection cannot be
     * made. */
    gboolean egress_quiet = (sink > 0 && data->watch_last_sink >= 0 && sink == data->watch_last_sink);
    gboolean picture_moving = (frames != data->watch_last_frames);

    data->watch_last_sink = sink;
    data->watch_last_frames = frames;

    if (egress_quiet && picture_moving) {
        notify_log(data, "restream stalled while the picture flows, reconnecting");
        data->watch_last_sink = -1;
        restream_stop(data, TRUE);
    }
    return G_SOURCE_CONTINUE;
}

/* Declare the muxer's video pad as an Enhanced FLV track, which is what admits H.265.
 *
 * `eflvmux` keeps the mode per pad, and its default is `legacy`: a legacy track refuses H.265
 * whatever the pad template advertises ("pad's track type is set to legacy, H265 is only possible
 * with multitrack or non-multitrack types"). `non-multitrack` is plain Enhanced RTMP with no
 * track IDs, which is the shape YouTube and self-hosted servers accept.
 *
 * The audio pad is left alone. An enhanced video track beside a legacy AAC track is the pairing
 * enhanced RTMP defines for HEVC, and `voaacenc` output is what legacy FLV already carries.
 *
 * Runs before the pipeline leaves NULL, so the mode is set well ahead of any caps negotiation.
 */
static void restream_mark_enhanced_track(GstElement *pipeline)
{
    GstElement *mux = gst_bin_get_by_name(GST_BIN(pipeline), "mux");
    if (mux == NULL) {
        return;
    }

    GstIterator *pads = gst_element_iterate_sink_pads(mux);
    GValue item = G_VALUE_INIT;
    gboolean done = FALSE;
    while (!done) {
        switch (gst_iterator_next(pads, &item)) {
        case GST_ITERATOR_OK: {
            GstPad *pad = GST_PAD(g_value_get_object(&item));
            gchar *name = gst_pad_get_name(pad);
            if (g_str_has_prefix(name, "video")) {
                gst_util_set_object_arg(G_OBJECT(pad), "flv-track-mode", "non-multitrack");
            }
            g_free(name);
            g_value_reset(&item);
            break;
        }
        case GST_ITERATOR_RESYNC:
            gst_iterator_resync(pads);
            break;
        default:
            done = TRUE;
            break;
        }
    }
    g_value_unset(&item);
    gst_iterator_free(pads);
    gst_object_unref(mux);
}

/* Build the egress pipeline and start feeding it from the player's tee. Runs on the loop thread.
 *
 * The queue heads it so a stalled uplink drops access units rather than accumulating them, and
 * rtmp2sink's location is set as a property because a stream key carries characters the pipeline
 * parser would read as syntax.
 */
static gboolean restream_start(gpointer user_data)
{
    CustomData *data = (CustomData *) user_data;

    if (data->pipeline == NULL || data->downstream == NULL || data->rtmp_url == NULL) {
        return G_SOURCE_REMOVE;
    }

    /* An egress that survived a rebuild is carrying a codec, and the parser and caps it was
     * built around only fit that one. A session that comes back on the other codec has to be
     * rebuilt, which costs the RTMP session; the same codec keeps it. */
    if (data->restream_pipeline != NULL && data->restream_h264 != data->codec_is_h264) {
        notify_log(data, "restream rebuilding: the stream changed codec");
        restream_stop(data, FALSE);
    }

    /* The tee's sink pad carries every access unit the picture is decoded from, which is exactly
     * what the destination should receive. */
    GstElement *tee = gst_bin_get_by_name(GST_BIN(data->downstream), "t");
    GstPad *feed_pad = tee ? gst_element_get_static_pad(tee, "sink") : NULL;
    GstCaps *caps = feed_pad ? gst_pad_get_current_caps(feed_pad) : NULL;
    if (tee != NULL) {
        gst_object_unref(tee);
    }
    if (feed_pad == NULL || caps == NULL) {
        /* nothing has negotiated yet; the retry catches it once the stream is running */
        if (feed_pad != NULL) {
            gst_object_unref(feed_pad);
        }
        restream_schedule_retry(data);
        return G_SOURCE_REMOVE;
    }

    /* The egress outlived the player under it, so there is a live RTMP session to feed and only
     * the probe to put back. */
    if (data->restream_pipeline != NULL) {
        if (data->restream_feed_probe == 0) {
            gst_app_src_set_caps(GST_APP_SRC(data->restream_src), caps);
            data->restream_feed_pad = feed_pad;
            data->restream_feed_probe = gst_pad_add_probe(feed_pad, GST_PAD_PROBE_TYPE_BUFFER,
                                                          restream_feed_cb, data, NULL);
            notify_log(data, "restream re-attached to the new pipeline");
        } else {
            gst_object_unref(feed_pad);
        }
        gst_caps_unref(caps);
        return G_SOURCE_REMOVE;
    }

    const gchar *parse = data->codec_is_h264 ? "h264parse" : "h265parse";
    const gchar *media = data->codec_is_h264 ? "video/x-h264,stream-format=avc,alignment=au"
                                             : "video/x-h265,stream-format=hvc1,alignment=au";

    /* The muxer follows the codec. `flvmux` takes `video/x-h264, stream-format=avc` and nothing
     * else in this build, so an H.265 stream cannot be linked to it at all; `eflvmux`, the
     * Enhanced FLV muxer in the same plugin, is the one whose template carries
     * `video/x-h265, stream-format=hvc1`. H.264 stays on `flvmux`, because legacy FLV is what
     * Twitch expects for AVC and that path is proven against its ingest. */
    const gchar *muxer = data->codec_is_h264 ? "flvmux" : "eflvmux";

    /* The track exists either way: an ingest handed video alone behaves badly, and audio that
     * keeps flowing through an RF dropout is what holds the session open across a battery swap.
     * The source is named so that an error from it can be told apart from one raised by the
     * sink, which is a wrong stream key rather than a microphone that will not open. */
    gboolean use_mic = data->restream_use_mic && !data->restream_mic_failed;
    notify_audio(data, use_mic);
    const gchar *audio = use_mic
        ? "openslessrc name=micsrc ! audioconvert ! audioresample"
        : "audiotestsrc name=micsrc wave=silence is-live=true ! audioconvert ! audioresample";

    gchar *desc = g_strdup_printf(
        "appsrc name=esrc is-live=true format=time do-timestamp=false max-bytes=%d block=false "
        "! queue name=equeue leaky=downstream max-size-buffers=0 max-size-time=0 max-size-bytes=%d "
        "! %s ! %s "
        "! %s name=mux streamable=true ! rtmp2sink name=rtmpsink sync=false "
        "%s ! voaacenc bitrate=128000 ! mux.",
        RESTREAM_QUEUE_BYTES, RESTREAM_QUEUE_BYTES, parse, media, muxer, audio);

    GError *error = NULL;
    GstElement *pipeline = gst_parse_launch(desc, &error);
    g_free(desc);
    if (pipeline == NULL || error != NULL) {
        gchar *line = g_strdup_printf("restream pipeline failed to build: %s",
                                      error ? error->message : "unknown");
        notify_restream(data, line);
        g_free(line);
        g_clear_error(&error);
        if (pipeline != NULL) {
            gst_object_unref(pipeline);
        }
        gst_caps_unref(caps);
        gst_object_unref(feed_pad);
        restream_schedule_retry(data);
        return G_SOURCE_REMOVE;
    }

    if (!data->codec_is_h264) {
        restream_mark_enhanced_track(pipeline);
    }

    GstElement *src = gst_bin_get_by_name(GST_BIN(pipeline), "esrc");
    GstElement *sink = gst_bin_get_by_name(GST_BIN(pipeline), "rtmpsink");
    GstElement *queue = gst_bin_get_by_name(GST_BIN(pipeline), "equeue");
    if (src == NULL || sink == NULL || queue == NULL) {
        notify_restream(data, "restream pipeline is missing its source, queue or sink");
        if (src != NULL) {
            gst_object_unref(src);
        }
        if (sink != NULL) {
            gst_object_unref(sink);
        }
        if (queue != NULL) {
            gst_object_unref(queue);
        }
        gst_object_unref(pipeline);
        gst_caps_unref(caps);
        gst_object_unref(feed_pad);
        restream_schedule_retry(data);
        return G_SOURCE_REMOVE;
    }

    /* The buffers arrive already timestamped by the player, so the appsrc must not stamp them
     * again: an appsrc stamps against its own base time, and anything handed to it before its
     * pipeline is running lands as far in the future as the clock has been counting. */
    gst_app_src_set_caps(GST_APP_SRC(src), caps);
    gst_caps_unref(caps);

    g_object_set(sink, "location", data->rtmp_url, NULL);
    GstPad *sinkpad = gst_element_get_static_pad(sink, "sink");
    if (sinkpad != NULL) {
        gst_pad_add_probe(sinkpad, GST_PAD_PROBE_TYPE_BUFFER, restream_live_cb, data, NULL);
        gst_object_unref(sinkpad);
    }
    gst_object_unref(sink);

    /* Slaved to the player: the video carries the player's timestamps, and the silent audio is
     * generated here, so the two only line up while both pipelines measure running time the
     * same way. Clearing the start time is what stops this pipeline resetting its base time
     * when it goes to PLAYING. */
    GstClock *clock = gst_pipeline_get_clock(GST_PIPELINE(data->pipeline));
    if (clock != NULL) {
        gst_pipeline_use_clock(GST_PIPELINE(pipeline), clock);
        gst_object_unref(clock);
    }
    gst_element_set_start_time(pipeline, GST_CLOCK_TIME_NONE);
    gst_element_set_base_time(pipeline, gst_element_get_base_time(data->pipeline));

    GstBus *bus = gst_element_get_bus(pipeline);
    data->restream_bus_source = gst_bus_create_watch(bus);
    g_source_set_callback(data->restream_bus_source, (GSourceFunc) gst_bus_async_signal_func,
                          NULL, NULL);
    g_source_attach(data->restream_bus_source, data->context);
    g_signal_connect(G_OBJECT(bus), "message::error", (GCallback) restream_error_cb, data);
    gst_object_unref(bus);

    data->restream_pipeline = pipeline;
    data->restream_src = src;
    data->restream_queue = queue;
    data->restream_feed_pad = feed_pad;
    data->restream_started_us = g_get_monotonic_time();
    data->restream_fed_us = g_get_monotonic_time();
    data->restream_h264 = data->codec_is_h264;
    data->restream_out_pts = GST_CLOCK_TIME_NONE;
    data->restream_offset_valid = FALSE;
    g_atomic_int_set(&data->restream_needs_key, 1);
    g_atomic_int_set(&data->restream_dropped, 0);
    g_atomic_int_set(&data->restream_congested, 0);

    gst_element_set_state(pipeline, GST_STATE_PLAYING);
    data->restream_feed_probe = gst_pad_add_probe(feed_pad, GST_PAD_PROBE_TYPE_BUFFER,
                                                 restream_feed_cb, data, NULL);

    /* Lives as long as the egress does, because holding a gap open is only meaningful while
     * there is a session to hold. */
    GSource *hold = g_timeout_source_new(RESTREAM_HOLD_MS);
    g_source_set_callback(hold, restream_hold_cb, data, NULL);
    data->restream_hold_id = g_source_attach(hold, data->context);
    g_source_unref(hold);

    if (!data->restream_reported_down) {
        notify_log(data, "restream started");
    }
    return G_SOURCE_REMOVE;
}

static void build_pipeline(CustomData *data)
{
    if (data->uri == NULL) {
        return;
    }

    /* Built element by element rather than parsed. gst_parse_launch returns the element itself
     * when the description names only one, so a parsed "rtspsrc ..." would not be a pipeline at
     * all and nothing could be added to it later. Setting location as a property also keeps a
     * user-edited URL away from the parser, which reads characters in a stream key as syntax. */
    data->pipeline = gst_pipeline_new("player");
    GstElement *src = gst_element_factory_make("rtspsrc", "src");
    if (data->pipeline == NULL || src == NULL) {
        LOGE("could not create the pipeline or rtspsrc");
        if (src != NULL) {
            gst_object_unref(src);
        }
        if (data->pipeline != NULL) {
            gst_object_unref(data->pipeline);
            data->pipeline = NULL;
        }
        notify_state(data, ST_ERROR, "could not create the pipeline");
        return;
    }

    g_object_set(src, "location", data->uri, "latency", 100, NULL);
    gst_bin_add(GST_BIN(data->pipeline), src);
    g_signal_connect(src, "pad-added", (GCallback) pad_added_cb, data);

    g_signal_connect(data->pipeline, "deep-element-added", (GCallback) deep_element_added_cb, data);

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

/* GLib main loop thread */
static void *app_function(void *userdata)
{
    CustomData *data = (CustomData *) userdata;
    g_main_context_push_thread_default(data->context);
    LOGI("gst main loop running");

    data->watch_last_sink = -1;
    GSource *watchdog = g_timeout_source_new_seconds(RESTREAM_WATCHDOG_S);
    g_source_set_callback(watchdog, restream_watchdog_cb, data, NULL);
    g_source_attach(watchdog, data->context);
    g_source_unref(watchdog);

    g_main_loop_run(data->main_loop);
    LOGI("gst main loop exiting");

    /* Before the teardown below, which keeps an armed egress alive on purpose. There is no loop
     * left to drive one here. */
    restream_cancel_retry(data);
    restream_stop(data, FALSE);
    teardown_pipeline(data);
    g_main_context_pop_thread_default(data->context);

    return NULL;
}

/* JNI entry points */
static void gst_native_init(JNIEnv *env, jobject thiz)
{
    CustomData *data = g_new0(CustomData, 1);
    (*env)->SetLongField(env, thiz, custom_data_field_id, (jlong) (gsize) data);
    data->app = (*env)->NewGlobalRef(env, thiz);
    g_mutex_init(&data->restream_feed_lock);
    data->restream_out_pts = GST_CLOCK_TIME_NONE;
    data->context = g_main_context_new();
    data->main_loop = g_main_loop_new(data->context, FALSE);
    /* the handle lives in CustomData, not a file-scope global: a second player would
     * otherwise overwrite the first one's handle and finalize would join the wrong thread */
    if (pthread_create(&data->thread, NULL, &app_function, data) != 0) {
        LOGE("could not start the gst loop thread");
    }
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

    pthread_join(data->thread, NULL);
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
    g_mutex_clear(&data->restream_feed_lock);
    g_free(data->uri);
    g_free(data->rtmp_url);
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

/* Start or stop the restream. A NULL url stops it. The egress is a pipeline of its own, started
 * and stopped beside the player, so the picture is never interrupted by going live or stopping. */
static void gst_native_set_restream(JNIEnv *env, jobject thiz, jstring url, jboolean use_mic)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return;
    }

    g_free(data->rtmp_url);
    data->rtmp_url = NULL;
    data->restream_armed = FALSE;

    if (url != NULL) {
        const gchar *s = (*env)->GetStringUTFChars(env, url, NULL);
        data->rtmp_url = g_strdup(s);
        data->restream_armed = TRUE;
        data->restream_use_mic = (use_mic == JNI_TRUE);
        data->restream_mic_failed = FALSE;
        data->restream_backoff_ms = 0;
        data->restream_started_us = 0;
        data->restream_reported_down = FALSE;
        (*env)->ReleaseStringUTFChars(env, url, s);
    }

    g_main_context_invoke(data->context,
                          data->restream_armed ? restream_start : restream_stop_cb, data);
}

static void gst_native_play(JNIEnv *env, jobject thiz)
{
    CustomData *data = (CustomData *) (gsize) (*env)->GetLongField(env, thiz, custom_data_field_id);
    if (data == NULL) {
        return;
    }

    /* Reset here rather than in do_rebuild: the app baselines the count the moment play()
     * returns, and the rebuild only happens once the loop thread gets to it. Bumping the
     * generation first retires the old pipeline's probe, so nothing it emits in between is
     * counted. */
    g_atomic_int_inc(&data->generation);
    g_atomic_int_set(&data->frame_count, 0);
    notify_state(data, ST_CONNECTING, NULL);
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
    on_state_method_id =
        (*env)->GetMethodID(env, klass, "onNativeState", "(ILjava/lang/String;)V");
    on_log_method_id = (*env)->GetMethodID(env, klass, "onNativeLog", "(Ljava/lang/String;)V");
    on_codec_method_id = (*env)->GetMethodID(env, klass, "onNativeCodec", "(Ljava/lang/String;)V");
    on_restream_method_id =
        (*env)->GetMethodID(env, klass, "onNativeRestreamFailed", "(Ljava/lang/String;)V");
    on_restream_live_method_id =
        (*env)->GetMethodID(env, klass, "onNativeRestreamLive", "(Z)V");
    on_audio_method_id = (*env)->GetMethodID(env, klass, "onNativeAudioSource", "(Z)V");
    if (custom_data_field_id == NULL || on_state_method_id == NULL || on_log_method_id == NULL ||
        on_codec_method_id == NULL || on_restream_method_id == NULL ||
        on_restream_live_method_id == NULL || on_audio_method_id == NULL) {
        LOGE("GStreamerPlayer is missing nativeCustomData / onNativeState / onNativeLog / "
             "onNativeCodec / onNativeRestreamFailed / onNativeRestreamLive / "
             "onNativeAudioSource");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static JNINativeMethod native_methods[] = {
    {"nativeInit", "()V", (void *) gst_native_init},
    {"nativeFinalize", "()V", (void *) gst_native_finalize},
    {"nativeSetUri", "(Ljava/lang/String;)V", (void *) gst_native_set_uri},
    {"nativeSetRestream", "(Ljava/lang/String;Z)V", (void *) gst_native_set_restream},
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

    /* a rename or an obfuscated build breaks this lookup; fail the load with a message
     * naming the class instead of dereferencing NULL inside RegisterNatives */
    jclass klass = (*env)->FindClass(env, "at/websium/ml/GStreamerPlayer");
    if (klass == NULL) {
        (*env)->ExceptionClear(env);
        LOGE("class at/websium/ml/GStreamerPlayer not found; check the package name and any "
             "ProGuard/R8 keep rules");
        return 0;
    }

    if ((*env)->RegisterNatives(env, klass, native_methods, G_N_ELEMENTS(native_methods)) != 0) {
        (*env)->ExceptionClear(env);
        LOGE("RegisterNatives failed; the external declarations no longer match native_methods");
        return 0;
    }

    pthread_key_create(&current_jni_env, detach_current_thread);
    return JNI_VERSION_1_4;
}
