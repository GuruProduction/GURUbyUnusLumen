/*
 * quickjs_bridge.c - JNI bridge between Kotlin and QuickJS.
 *
 * Architecture:
 *   - A dedicated pthread owns the JSRuntime and JSContext for the engine's lifetime.
 *   - Kotlin calls nativeEval which posts a script to the JS thread via a
 *     mutex/condition variable queue, then blocks until the result is ready.
 *   - When a script calls __bridge.call(category, action, argsJson), the C bridge
 *     function uses the JS thread's JNIEnv to invoke the Kotlin BridgeCallback
 *     synchronously and returns the result string to JavaScript.
 *   - On close, the JS thread is signalled to exit, joined, and everything is freed.
 *
 * JNI function naming follows the standard mangling convention:
 *   Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeInit
 *   Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeSetBridgeCallback
 *   Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeEval
 *   Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeClose
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "quickjs/quickjs.h"

#define LOG_TAG "quickjs_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* Engine state                                                       */
/* ------------------------------------------------------------------ */

typedef struct {
    /* QuickJS */
    JSRuntime *rt;
    JSContext *ctx;

    /* Thread */
    pthread_t thread;
    int thread_started;

    /* Synchronization: protects pending_script/filename and result_string */
    pthread_mutex_t mutex;
    pthread_cond_t script_ready_cond;  /* signaled when a script is queued */
    pthread_cond_t result_ready_cond;  /* signaled when a result is available */

    /* Script queue (single-slot) */
    char *pending_script;
    char *pending_filename;
    int script_pending;

    /* Result (single-slot) */
    char *result_string;
    int result_is_exception;
    int result_ready;

    /* Lifecycle */
    int should_exit;

    /* JNI callback */
    JavaVM *jvm;
    jobject global_callback;   /* global ref to Kotlin BridgeCallback */
    int has_callback;
    JNIEnv *js_thread_env;      /* JNIEnv for the JS thread */
} Engine;

/* ------------------------------------------------------------------ */
/* Forward declarations                                               */
/* ------------------------------------------------------------------ */

static void *js_thread_func(void *arg);
static int init_globals(Engine *e);

/* ------------------------------------------------------------------ */
/* Bridge function: __bridge.call(category, action, argsJson)          */
/* ------------------------------------------------------------------ */

static JSValue js_bridge_call(JSContext *ctx, JSValueConst this_val,
                              int argc, JSValueConst *argv)
{
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);

    if (!e || !e->has_callback || !e->global_callback) {
        return JS_ThrowInternalError(ctx, "bridge callback not registered");
    }

    if (argc < 3) {
        return JS_ThrowTypeError(ctx, "expected 3 arguments: category, action, argsJson");
    }

    JNIEnv *env = e->js_thread_env;
    if (!env) {
        return JS_ThrowInternalError(ctx, "JNI environment not available on JS thread");
    }

    /* Convert JS string args to C strings */
    const char *c_category = JS_ToCString(ctx, argv[0]);
    const char *c_action   = JS_ToCString(ctx, argv[1]);
    const char *c_args     = JS_ToCString(ctx, argv[2]);

    if (!c_category || !c_action || !c_args) {
        if (c_category) JS_FreeCString(ctx, c_category);
        if (c_action)   JS_FreeCString(ctx, c_action);
        if (c_args)     JS_FreeCString(ctx, c_args);
        return JS_ThrowTypeError(ctx, "all arguments must be strings");
    }

    /* Create JNI string copies */
    jstring j_category = (*env)->NewStringUTF(env, c_category);
    jstring j_action   = (*env)->NewStringUTF(env, c_action);
    jstring j_args     = (*env)->NewStringUTF(env, c_args);

    /* Free the C strings (we have JNI copies now) */
    JS_FreeCString(ctx, c_category);
    JS_FreeCString(ctx, c_action);
    JS_FreeCString(ctx, c_args);

    if (!j_category || !j_action || !j_args) {
        /* OutOfMemoryError already pending in JNI */
        if (j_category) (*env)->DeleteLocalRef(env, j_category);
        if (j_action)   (*env)->DeleteLocalRef(env, j_action);
        if (j_args)     (*env)->DeleteLocalRef(env, j_args);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JS_ThrowInternalError(ctx, "failed to create JNI strings");
    }

    /* Find BridgeCallback.onBridgeCall(String, String, String): String */
    jclass cb_class = (*env)->GetObjectClass(env, e->global_callback);
    if (!cb_class) {
        (*env)->DeleteLocalRef(env, j_category);
        (*env)->DeleteLocalRef(env, j_action);
        (*env)->DeleteLocalRef(env, j_args);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JS_ThrowInternalError(ctx, "failed to get callback class");
    }

    jmethodID method = (*env)->GetMethodID(env, cb_class,
        "onBridgeCall",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, cb_class);

    if (!method) {
        (*env)->DeleteLocalRef(env, j_category);
        (*env)->DeleteLocalRef(env, j_action);
        (*env)->DeleteLocalRef(env, j_args);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JS_ThrowInternalError(ctx, "onBridgeCall method not found");
    }

    /* Invoke the Kotlin callback */
    jstring j_result = (jstring)(*env)->CallObjectMethod(env,
        e->global_callback, method, j_category, j_action, j_args);

    /* Check for Kotlin exceptions */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, j_category);
        (*env)->DeleteLocalRef(env, j_action);
        (*env)->DeleteLocalRef(env, j_args);
        if (j_result) (*env)->DeleteLocalRef(env, j_result);
        return JS_ThrowInternalError(ctx, "bridge callback threw an exception");
    }

    /* Clean up argument references */
    (*env)->DeleteLocalRef(env, j_category);
    (*env)->DeleteLocalRef(env, j_action);
    (*env)->DeleteLocalRef(env, j_args);

    /* Convert the result back to a JS string */
    if (!j_result) {
        return JS_NewString(ctx, "");
    }

    const char *result_c = (*env)->GetStringUTFChars(env, j_result, NULL);
    if (!result_c) {
        (*env)->DeleteLocalRef(env, j_result);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JS_NewString(ctx, "");
    }

    JSValue js_result = JS_NewString(ctx, result_c);

    (*env)->ReleaseStringUTFChars(env, j_result, result_c);
    (*env)->DeleteLocalRef(env, j_result);

    return js_result;
}

/* ------------------------------------------------------------------ */
/* Console: console.log / console.error / console.warn                 */
/* ------------------------------------------------------------------ */

static JSValue js_console_log(JSContext *ctx, JSValueConst this_val,
                              int argc, JSValueConst *argv)
{
    for (int i = 0; i < argc; i++) {
        const char *str = JS_ToCString(ctx, argv[i]);
        if (str) {
            LOGI("[console] %s", str);
            JS_FreeCString(ctx, str);
        }
    }
    return JS_UNDEFINED;
}

static JSValue js_console_error(JSContext *ctx, JSValueConst this_val,
                                int argc, JSValueConst *argv)
{
    for (int i = 0; i < argc; i++) {
        const char *str = JS_ToCString(ctx, argv[i]);
        if (str) {
            LOGE("[console] %s", str);
            JS_FreeCString(ctx, str);
        }
    }
    return JS_UNDEFINED;
}

/* ------------------------------------------------------------------ */
/* Register global objects (__bridge and console)                     */
/* ------------------------------------------------------------------ */

static int init_globals(Engine *e)
{
    JSContext *ctx = e->ctx;
    JSValue global = JS_GetGlobalObject(ctx);

    /* __bridge.call(category, action, argsJson) */
    JSValue bridge_obj = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, bridge_obj, "call",
        JS_NewCFunction(ctx, js_bridge_call, "call", 3));
    JS_SetPropertyStr(ctx, global, "__bridge", bridge_obj);

    /* console.log / console.error / console.warn */
    JSValue console_obj = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, console_obj, "log",
        JS_NewCFunction(ctx, js_console_log, "log", 1));
    JS_SetPropertyStr(ctx, console_obj, "error",
        JS_NewCFunction(ctx, js_console_error, "error", 1));
    JS_SetPropertyStr(ctx, console_obj, "warn",
        JS_NewCFunction(ctx, js_console_log, "warn", 1));
    JS_SetPropertyStr(ctx, global, "console", console_obj);

    JS_FreeValue(ctx, global);
    return 0;
}

/* ------------------------------------------------------------------ */
/* JS thread: owns the runtime/context, executes scripts              */
/* ------------------------------------------------------------------ */

static void *js_thread_func(void *arg)
{
    Engine *e = (Engine *)arg;

    /* Attach this thread to the JVM */
    JNIEnv *env;
    if ((*e->jvm)->AttachCurrentThread(e->jvm, &env, NULL) != JNI_OK) {
        LOGE("failed to attach JS thread to JVM");
        return NULL;
    }
    e->js_thread_env = env;

    /* Tell QuickJS about this thread's stack for overflow detection */
    JS_UpdateStackTop(e->rt);

    pthread_mutex_lock(&e->mutex);

    while (1) {
        /* Wait for a script to arrive or for shutdown */
        while (!e->script_pending && !e->should_exit) {
            pthread_cond_wait(&e->script_ready_cond, &e->mutex);
        }

        if (e->should_exit) {
            break;
        }

        /* Take the script from the queue */
        char *script   = e->pending_script;
        char *filename = e->pending_filename;
        e->pending_script   = NULL;
        e->pending_filename  = NULL;
        e->script_pending    = 0;

        pthread_mutex_unlock(&e->mutex);

        /* Evaluate the script (no mutex held so close can still signal) */
        size_t script_len = strlen(script);
        JSValue val = JS_Eval(e->ctx, script, script_len,
                              filename ? filename : "eval",
                              JS_EVAL_TYPE_GLOBAL);

        free(script);
        free(filename);

        char *result_str = NULL;
        int is_exception = 0;

        if (JS_IsException(val)) {
            /* Retrieve the exception */
            JSValue exc = JS_GetException(e->ctx);
            if (JS_IsError(e->ctx, exc)) {
                /* Error object: get .message or .stack */
                JSValue msg = JS_GetPropertyStr(e->ctx, exc, "message");
                const char *s = JS_ToCString(e->ctx, msg);
                result_str = strdup(s ? s : "Error (no message)");
                JS_FreeCString(e->ctx, s);
                JS_FreeValue(e->ctx, msg);
            } else {
                /* Non-Error throw: try to stringify */
                const char *s = JS_ToCString(e->ctx, exc);
                result_str = strdup(s ? s : "Unknown exception");
                JS_FreeCString(e->ctx, s);
            }
            JS_FreeValue(e->ctx, exc);
            is_exception = 1;
        } else {
            /* Success: JSON stringify the result */
            if (JS_IsUndefined(val)) {
                result_str = strdup("null");
            } else {
                JSValue json = JS_JSONStringify(e->ctx, val, JS_NULL, JS_NULL);
                if (JS_IsException(json) || JS_IsUndefined(json)) {
                    JS_FreeValue(e->ctx, json);
                    /* Fallback: convert to string directly */
                    const char *s = JS_ToCString(e->ctx, val);
                    result_str = strdup(s ? s : "null");
                    JS_FreeCString(e->ctx, s);
                } else {
                    const char *s = JS_ToCString(e->ctx, json);
                    result_str = strdup(s ? s : "null");
                    JS_FreeCString(e->ctx, s);
                    JS_FreeValue(e->ctx, json);
                }
            }
            is_exception = 0;
        }

        JS_FreeValue(e->ctx, val);

        /* Also run pending jobs (promise microtasks, if any) */
        while (JS_IsJobPending(e->rt)) {
            JSContext *ctx2;
            int err = JS_ExecutePendingJob(e->rt, &ctx2);
            if (err <= 0) {
                if (err < 0) {
                    JSValue exc = JS_GetException(ctx2);
                    const char *s = JS_ToCString(ctx2, exc);
                    LOGE("pending job error: %s", s ? s : "(unknown)");
                    JS_FreeCString(ctx2, s);
                    JS_FreeValue(ctx2, exc);
                }
                break;
            }
        }

        /* Store the result */
        pthread_mutex_lock(&e->mutex);
        /* Free any unclaimed result from a previous interrupted call */
        if (e->result_string) {
            free(e->result_string);
            e->result_string = NULL;
        }
        e->result_string     = result_str ? result_str : strdup("null");
        e->result_is_exception = is_exception;
        e->result_ready       = 1;
        pthread_cond_signal(&e->result_ready_cond);
        /* Loop back: will wait on script_ready_cond (releasing mutex) */
    }

    pthread_mutex_unlock(&e->mutex);

    /* Detach from the JVM */
    (*e->jvm)->DetachCurrentThread(e->jvm);
    e->js_thread_env = NULL;

    return NULL;
}

/* ------------------------------------------------------------------ */
/* JNI: nativeInit                                                     */
/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeInit
    (JNIEnv *env, jobject thiz)
{
    Engine *e = calloc(1, sizeof(Engine));
    if (!e) {
        goto fail;
    }

    /* Get the JavaVM for thread attachment */
    if ((*env)->GetJavaVM(env, &e->jvm) != JNI_OK) {
        LOGE("GetJavaVM failed");
        goto fail;
    }

    /* Create QuickJS runtime and context */
    e->rt = JS_NewRuntime();
    if (!e->rt) {
        LOGE("JS_NewRuntime failed");
        goto fail;
    }

    /* 16 MB memory limit for the JS environment */
    JS_SetMemoryLimit(e->rt, 16 * 1024 * 1024);

    /* GC threshold: collect when allocations exceed 8 MB */
    JS_SetGCThreshold(e->rt, 8 * 1024 * 1024);

    e->ctx = JS_NewContext(e->rt);
    if (!e->ctx) {
        LOGE("JS_NewContext failed");
        goto fail;
    }

    /* Store engine pointer on the context for bridge function access */
    JS_SetContextOpaque(e->ctx, e);

    /* Register global objects (__bridge, console) */
    if (init_globals(e) != 0) {
        LOGE("init_globals failed");
        goto fail;
    }

    /* Initialize synchronization primitives */
    if (pthread_mutex_init(&e->mutex, NULL) != 0) {
        LOGE("mutex init failed");
        goto fail;
    }
    if (pthread_cond_init(&e->script_ready_cond, NULL) != 0) {
        LOGE("script_ready_cond init failed");
        pthread_mutex_destroy(&e->mutex);
        goto fail;
    }
    if (pthread_cond_init(&e->result_ready_cond, NULL) != 0) {
        LOGE("result_ready_cond init failed");
        pthread_cond_destroy(&e->script_ready_cond);
        pthread_mutex_destroy(&e->mutex);
        goto fail;
    }

    /* Start the JS thread */
    if (pthread_create(&e->thread, NULL, js_thread_func, e) != 0) {
        LOGE("pthread_create failed");
        pthread_cond_destroy(&e->result_ready_cond);
        pthread_cond_destroy(&e->script_ready_cond);
        pthread_mutex_destroy(&e->mutex);
        goto fail;
    }
    e->thread_started = 1;

    LOGI("QuickJS engine initialized");
    return (jlong)e;

fail:
    if (e) {
        if (e->ctx) JS_FreeContext(e->ctx);
        if (e->rt)  JS_FreeRuntime(e->rt);
        free(e);
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* JNI: nativeSetBridgeCallback                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeSetBridgeCallback
    (JNIEnv *env, jobject thiz, jlong ptr, jobject callback)
{
    Engine *e = (Engine *)ptr;
    if (!e || !callback) {
        return JNI_FALSE;
    }

    /* Delete the old callback global ref if it exists */
    if (e->global_callback) {
        (*env)->DeleteGlobalRef(env, e->global_callback);
        e->global_callback = NULL;
    }

    /* Create a global ref so it survives across JNI calls and threads */
    e->global_callback = (*env)->NewGlobalRef(env, callback);
    if (!e->global_callback) {
        LOGE("NewGlobalRef failed for callback");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&e->mutex);
    e->has_callback = 1;
    pthread_mutex_unlock(&e->mutex);

    LOGI("Bridge callback registered");
    return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/* JNI: nativeEval                                                     */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeEval
    (JNIEnv *env, jobject thiz, jlong ptr, jstring j_script, jstring j_filename)
{
    Engine *e = (Engine *)ptr;
    if (!e) {
        jclass ex = (*env)->FindClass(env, "java/lang/IllegalStateException");
        (*env)->ThrowNew(env, ex, "engine not initialized");
        (*env)->DeleteLocalRef(env, ex);
        return NULL;
    }

    if (e->should_exit) {
        jclass ex = (*env)->FindClass(env, "java/lang/IllegalStateException");
        (*env)->ThrowNew(env, ex, "engine is closed");
        (*env)->DeleteLocalRef(env, ex);
        return NULL;
    }

    /* Extract C strings from JNI */
    const char *script_c = (*env)->GetStringUTFChars(env, j_script, NULL);
    if (!script_c) {
        return NULL;  /* OutOfMemoryError */
    }
    const char *filename_c = (*env)->GetStringUTFChars(env, j_filename, NULL);
    if (!filename_c) {
        (*env)->ReleaseStringUTFChars(env, j_script, script_c);
        return NULL;
    }

    /* Queue the script for the JS thread */
    pthread_mutex_lock(&e->mutex);

    /* Safety: clear any stale unclaimed result */
    if (e->result_string) {
        free(e->result_string);
        e->result_string = NULL;
        e->result_ready = 0;
    }

    e->pending_script   = strdup(script_c);
    e->pending_filename  = strdup(filename_c);
    e->script_pending    = 1;
    e->result_ready      = 0;

    /* Release JNI string memory (we've made copies) */
    (*env)->ReleaseStringUTFChars(env, j_script, script_c);
    (*env)->ReleaseStringUTFChars(env, j_filename, filename_c);
    script_c = NULL;
    filename_c = NULL;

    /* Wake the JS thread */
    pthread_cond_signal(&e->script_ready_cond);

    /* Wait for the result */
    while (!e->result_ready && !e->should_exit) {
        pthread_cond_wait(&e->result_ready_cond, &e->mutex);
    }

    /* Take the result */
    char *result = e->result_string;
    int is_exception = e->result_is_exception;
    e->result_string = NULL;
    e->result_ready = 0;

    pthread_mutex_unlock(&e->mutex);

    /* Handle the result */
    if (!result) {
        if (e->should_exit) {
            jclass ex = (*env)->FindClass(env, "java/lang/IllegalStateException");
            (*env)->ThrowNew(env, ex, "engine was closed during evaluation");
            (*env)->DeleteLocalRef(env, ex);
            return NULL;
        }
        result = strdup("null");
    }

    if (is_exception) {
        /* Throw a RuntimeException with the exception message */
        jclass ex = (*env)->FindClass(env, "java/lang/RuntimeException");
        (*env)->ThrowNew(env, ex, result);
        (*env)->DeleteLocalRef(env, ex);
        free(result);
        return NULL;
    }

    /* Return the JSON result string */
    jstring j_result = (*env)->NewStringUTF(env, result);
    free(result);
    return j_result;
}

/* ------------------------------------------------------------------ */
/* JNI: nativeClose                                                    */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_com_unuslumen_app_data_tools_quickjs_QuickJsEngine_nativeClose
    (JNIEnv *env, jobject thiz, jlong ptr)
{
    Engine *e = (Engine *)ptr;
    if (!e) {
        return;
    }

    /* Signal the JS thread to exit */
    pthread_mutex_lock(&e->mutex);
    e->should_exit = 1;
    pthread_cond_signal(&e->script_ready_cond);
    /* Also signal result_ready_cond in case a caller is stuck waiting */
    pthread_cond_signal(&e->result_ready_cond);
    pthread_mutex_unlock(&e->mutex);

    /* Wait for the JS thread to finish */
    if (e->thread_started) {
        pthread_join(e->thread, NULL);
        e->thread_started = 0;
    }

    /* Destroy synchronization primitives */
    pthread_cond_destroy(&e->result_ready_cond);
    pthread_cond_destroy(&e->script_ready_cond);
    pthread_mutex_destroy(&e->mutex);

    /* Free any pending script */
    if (e->pending_script) {
        free(e->pending_script);
        e->pending_script = NULL;
    }
    if (e->pending_filename) {
        free(e->pending_filename);
        e->pending_filename = NULL;
    }
    if (e->result_string) {
        free(e->result_string);
        e->result_string = NULL;
    }

    /* Free QuickJS */
    if (e->ctx) {
        JS_FreeContext(e->ctx);
        e->ctx = NULL;
    }
    if (e->rt) {
        JS_FreeRuntime(e->rt);
        e->rt = NULL;
    }

    /* Delete the global callback reference */
    if (e->global_callback) {
        (*env)->DeleteGlobalRef(env, e->global_callback);
        e->global_callback = NULL;
    }

    free(e);
    LOGI("QuickJS engine closed");
}