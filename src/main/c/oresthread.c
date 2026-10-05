#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sched.h>
#include <stdatomic.h>
#if defined(__APPLE__)
#include <mach/mach.h>
#include <mach/thread_info.h>
#include <mach/thread_act.h>
#endif

typedef struct ores_carrier_pool ores_carrier_pool;
static uint64_t pthread_cpu_time_nanos(pthread_t pthread);

typedef struct {
    ores_carrier_pool *pool;
    int slot;
    char *name;
} ores_carrier_arg;

struct ores_carrier_pool {
    JavaVM *jvm;
    jobject executor;
    jmethodID carrier_loop;
    pthread_t *threads;
    ores_carrier_arg *args;
    int max_threads;
    int desired_threads;
    int started;
    int shutdown;
    int attach_ready_count;
    int attach_failures;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
};

static void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static char *copy_thread_name(const char *prefix, int slot) {
    size_t prefix_len = strlen(prefix);
    size_t size = prefix_len + 32;
    char *name = (char *)calloc(size, 1);
    if (name == NULL) return NULL;
    snprintf(name, size, "%s%d", prefix, slot + 1);
    return name;
}

static void *carrier_main(void *raw) {
    ores_carrier_arg *arg = (ores_carrier_arg *)raw;
    ores_carrier_pool *pool = arg->pool;

    pthread_mutex_lock(&pool->mutex);
    while (!pool->started && !pool->shutdown) {
        pthread_cond_wait(&pool->condition, &pool->mutex);
    }
    int should_stop = pool->shutdown;
    pthread_mutex_unlock(&pool->mutex);
    if (should_stop) return NULL;

    JNIEnv *env = NULL;
    JavaVMAttachArgs attach;
    memset(&attach, 0, sizeof(attach));
    attach.version = JNI_VERSION_1_8;
    attach.name = arg->name;
    attach.group = NULL;

    jint status = (*pool->jvm)->AttachCurrentThreadAsDaemon(
            pool->jvm, (void **)&env, &attach);

    pthread_mutex_lock(&pool->mutex);
    pool->attach_ready_count++;
    if (status != JNI_OK || env == NULL) pool->attach_failures++;
    pthread_cond_broadcast(&pool->condition);
    pthread_mutex_unlock(&pool->mutex);
    if (status != JNI_OK || env == NULL) return NULL;

    (*env)->CallVoidMethod(env, pool->executor, pool->carrier_loop, (jint)arg->slot);

    if ((*env)->ExceptionCheck(env)) {
        // Surface an unexpected executor-boundary failure before detaching.
        // Actor turn failures should normally be contained in Java.
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    (*pool->jvm)->DetachCurrentThread(pool->jvm);
    return NULL;
}

static void free_pool(JNIEnv *env, ores_carrier_pool *pool) {
    if (pool == NULL) return;
    if (pool->executor != NULL) (*env)->DeleteGlobalRef(env, pool->executor);
    if (pool->args != NULL) {
        for (int i = 0; i < pool->max_threads; i++) free(pool->args[i].name);
    }
    free(pool->args);
    free(pool->threads);
    pthread_cond_destroy(&pool->condition);
    pthread_mutex_destroy(&pool->mutex);
    free(pool);
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeCreate(
        JNIEnv *env,
        jclass cls,
        jobject executor,
        jint max_threads,
        jint desired_threads,
        jstring thread_prefix,
        jlong stack_bytes) {
    (void)cls;
    if (executor == NULL || thread_prefix == NULL) {
        throw_illegal_state(env, "native carrier executor/prefix cannot be null");
        return 0;
    }
    if (max_threads <= 0 || desired_threads <= 0 || desired_threads > max_threads) {
        throw_illegal_state(env, "invalid native carrier thread counts");
        return 0;
    }
    if (stack_bytes < 262144) {
        throw_illegal_state(env, "native carrier stack size must be at least 262144 bytes");
        return 0;
    }

    ores_carrier_pool *pool = (ores_carrier_pool *)calloc(1, sizeof(*pool));
    if (pool == NULL) {
        throw_illegal_state(env, "failed to allocate native carrier pool");
        return 0;
    }
    pool->max_threads = (int)max_threads;
    pool->desired_threads = (int)desired_threads;
    pthread_mutex_init(&pool->mutex, NULL);
    pthread_cond_init(&pool->condition, NULL);

    if ((*env)->GetJavaVM(env, &pool->jvm) != JNI_OK || pool->jvm == NULL) {
        free_pool(env, pool);
        throw_illegal_state(env, "JNI GetJavaVM failed");
        return 0;
    }

    pool->executor = (*env)->NewGlobalRef(env, executor);
    if (pool->executor == NULL) {
        free_pool(env, pool);
        throw_illegal_state(env, "failed to root native carrier executor");
        return 0;
    }

    jclass executor_class = (*env)->GetObjectClass(env, executor);
    if (executor_class == NULL) {
        free_pool(env, pool);
        return 0;
    }
    pool->carrier_loop = (*env)->GetMethodID(env, executor_class, "nativeCarrierLoop", "(I)V");
    (*env)->DeleteLocalRef(env, executor_class);
    if (pool->carrier_loop == NULL) {
        free_pool(env, pool);
        throw_illegal_state(env, "nativeCarrierLoop(int) JNI callback is missing");
        return 0;
    }

    const char *prefix = (*env)->GetStringUTFChars(env, thread_prefix, NULL);
    if (prefix == NULL) {
        free_pool(env, pool);
        return 0;
    }

    pool->threads = (pthread_t *)calloc((size_t)max_threads, sizeof(pthread_t));
    pool->args = (ores_carrier_arg *)calloc((size_t)max_threads, sizeof(ores_carrier_arg));
    if (pool->threads == NULL || pool->args == NULL) {
        (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
        free_pool(env, pool);
        throw_illegal_state(env, "failed to allocate native carrier slots");
        return 0;
    }

    pthread_attr_t attr;
    if (pthread_attr_init(&attr) != 0) {
        (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
        free_pool(env, pool);
        throw_illegal_state(env, "pthread_attr_init failed for native carrier");
        return 0;
    }
    if (pthread_attr_setstacksize(&attr, (size_t)stack_bytes) != 0) {
        pthread_attr_destroy(&attr);
        (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
        free_pool(env, pool);
        throw_illegal_state(env, "failed to configure native carrier stack size");
        return 0;
    }

    int created = 0;
    for (int i = 0; i < max_threads; i++) {
        pool->args[i].pool = pool;
        pool->args[i].slot = i;
        pool->args[i].name = copy_thread_name(prefix, i);
        if (pool->args[i].name == NULL
                || pthread_create(&pool->threads[i], &attr, carrier_main, &pool->args[i]) != 0) {
            pthread_mutex_lock(&pool->mutex);
            pool->shutdown = 1;
            pool->started = 1;
            pthread_cond_broadcast(&pool->condition);
            pthread_mutex_unlock(&pool->mutex);
            for (int j = 0; j < created; j++) pthread_join(pool->threads[j], NULL);
            pthread_attr_destroy(&attr);
            (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
            free_pool(env, pool);
            throw_illegal_state(env, "pthread_create failed for Oreslang carrier");
            return 0;
        }
        created++;
    }

    pthread_attr_destroy(&attr);
    (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
    return (jlong)(intptr_t)pool;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeStart(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;
    pthread_mutex_lock(&pool->mutex);
    pool->started = 1;
    pthread_cond_broadcast(&pool->condition);
    while (!pool->shutdown && pool->attach_ready_count < pool->max_threads) {
        pthread_cond_wait(&pool->condition, &pool->mutex);
    }
    int failures = pool->attach_failures;
    pthread_mutex_unlock(&pool->mutex);
    if (failures != 0) {
        throw_illegal_state(env, "one or more native carrier pthreads failed to attach to the JVM");
    }
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeSetDesired(
        JNIEnv *env, jclass cls, jlong handle, jint desired_threads) {
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;
    if (desired_threads <= 0 || desired_threads > pool->max_threads) {
        throw_illegal_state(env, "native desired carrier count is out of bounds");
        return;
    }
    pthread_mutex_lock(&pool->mutex);
    pool->desired_threads = (int)desired_threads;
    pthread_cond_broadcast(&pool->condition);
    pthread_mutex_unlock(&pool->mutex);
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeAwaitEnabled(
        JNIEnv *env, jclass cls, jlong handle, jint slot) {
    (void)env;
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;
    pthread_mutex_lock(&pool->mutex);
    while (!pool->shutdown && slot >= pool->desired_threads) {
        pthread_cond_wait(&pool->condition, &pool->mutex);
    }
    pthread_mutex_unlock(&pool->mutex);
}

static void *carrier_reaper_main(void *raw) {
    ores_carrier_pool *pool = (ores_carrier_pool *)raw;

    // Joining happens off the runtime/control-plane caller. A carrier that is
    // still inside non-cooperative guest code may delay reclamation of this
    // retired pool, but can no longer block ActorRuntime.close()/shutdownNow().
    for (int i = 0; i < pool->max_threads; i++) {
        pthread_join(pool->threads[i], NULL);
    }

    JNIEnv *env = NULL;
    JavaVM *jvm = pool->jvm;
    JavaVMAttachArgs attach;
    memset(&attach, 0, sizeof(attach));
    attach.version = JNI_VERSION_1_8;
    attach.name = "ores-carrier-reaper";
    attach.group = NULL;

    jint status = (*jvm)->AttachCurrentThreadAsDaemon(
            jvm, (void **)&env, &attach);
    if (status == JNI_OK && env != NULL) {
        free_pool(env, pool);
        (*jvm)->DetachCurrentThread(jvm);
    }
    // If the VM is already tearing down and attachment fails, deliberately
    // leak only the retired native pool metadata rather than touching JNI with
    // an invalid environment. Process teardown will reclaim it.
    return NULL;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeShutdown(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;

    pthread_mutex_lock(&pool->mutex);
    if (!pool->shutdown) {
        pool->shutdown = 1;
        pool->started = 1;
        pthread_cond_broadcast(&pool->condition);
    }
    pthread_mutex_unlock(&pool->mutex);

    pthread_t reaper;
    if (pthread_create(&reaper, NULL, carrier_reaper_main, pool) == 0) {
        pthread_detach(reaper);
        return;
    }

    /*
     * Resource exhaustion must not force the caller back into unbounded joins.
     * Leave this retired pool rooted until process teardown. Actor admission is
     * already closed and every parked/cooperative carrier has been woken.
     */
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeCurrentThreadId(
        JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jlong)(uintptr_t)pthread_self();
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeCurrentThreadCpuNanos(
        JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jlong)pthread_cpu_time_nanos(pthread_self());
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeCarrierCpuTimeNanos(
        JNIEnv *env, jclass cls, jlong handle, jint slot) {
    (void)env;
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL || slot < 0 || slot >= pool->max_threads) return 0;
    return (jlong)pthread_cpu_time_nanos(pool->threads[slot]);
}

static uint64_t pthread_cpu_time_nanos(pthread_t pthread) {
#if defined(__APPLE__)
    mach_port_t mach_thread = pthread_mach_thread_np(pthread);
    thread_basic_info_data_t info;
    mach_msg_type_number_t count = THREAD_BASIC_INFO_COUNT;
    kern_return_t status = thread_info(
            mach_thread,
            THREAD_BASIC_INFO,
            (thread_info_t)&info,
            &count);
    if (status != KERN_SUCCESS) return 0;
    uint64_t user = (uint64_t)info.user_time.seconds * 1000000000ULL
            + (uint64_t)info.user_time.microseconds * 1000ULL;
    uint64_t system = (uint64_t)info.system_time.seconds * 1000000000ULL
            + (uint64_t)info.system_time.microseconds * 1000ULL;
    return user + system;
#elif defined(CLOCK_THREAD_CPUTIME_ID)
    clockid_t clock_id;
    if (pthread_getcpuclockid(pthread, &clock_id) != 0) return 0;
    struct timespec ts;
    if (clock_gettime(clock_id, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
#else
    (void)pthread;
    return 0;
#endif
}


/* ------------------------------------------------------------------------- */
/* Process-owned native timer service.                                       */
/* ------------------------------------------------------------------------- */

typedef struct ores_timer_entry {
    uint64_t id;
    uint64_t deadline_nanos;
    struct ores_timer_entry *next;
} ores_timer_entry;

typedef struct {
    JavaVM *jvm;
    jobject service;
    jmethodID fire;
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    ores_timer_entry *head;
} ores_timer_service;

static uint64_t monotonic_nanos(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static void realtime_after_nanos(uint64_t delay_nanos, struct timespec *out) {
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0) {
        now.tv_sec = 0;
        now.tv_nsec = 0;
    }

    uint64_t sec = delay_nanos / 1000000000ULL;
    uint64_t nsec = delay_nanos % 1000000000ULL;
    uint64_t total_nsec = (uint64_t)now.tv_nsec + nsec;

    out->tv_sec = now.tv_sec + (time_t)sec + (time_t)(total_nsec / 1000000000ULL);
    out->tv_nsec = (long)(total_nsec % 1000000000ULL);
}

static void *timer_service_main(void *raw) {
    ores_timer_service *service = (ores_timer_service *)raw;
    JNIEnv *env = NULL;
    JavaVMAttachArgs attach;
    memset(&attach, 0, sizeof(attach));
    attach.version = JNI_VERSION_1_8;
    attach.name = "ores-native-timer";
    attach.group = NULL;

    jint status = (*service->jvm)->AttachCurrentThreadAsDaemon(
            service->jvm, (void **)&env, &attach);
    if (status != JNI_OK || env == NULL) return NULL;

    for (;;) {
        pthread_mutex_lock(&service->mutex);
        while (service->head == NULL) {
            pthread_cond_wait(&service->condition, &service->mutex);
        }

        uint64_t now = monotonic_nanos();
        ores_timer_entry *entry = service->head;
        if (entry->deadline_nanos > now) {
            uint64_t remaining = entry->deadline_nanos - now;
            struct timespec wake;
            realtime_after_nanos(remaining, &wake);
            (void)pthread_cond_timedwait(
                    &service->condition,
                    &service->mutex,
                    &wake);
            pthread_mutex_unlock(&service->mutex);
            continue;
        }

        service->head = entry->next;
        pthread_mutex_unlock(&service->mutex);

        (*env)->CallVoidMethod(
                env,
                service->service,
                service->fire,
                (jlong)entry->id);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        free(entry);
    }

    return NULL;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeTimerService_nativeCreate(
        JNIEnv *env,
        jclass cls,
        jobject service_object) {
    (void)cls;
    if (service_object == NULL) {
        throw_illegal_state(env, "native timer service cannot be null");
        return 0;
    }

    ores_timer_service *service =
            (ores_timer_service *)calloc(1, sizeof(*service));
    if (service == NULL) {
        throw_illegal_state(env, "failed to allocate native timer service");
        return 0;
    }

    if ((*env)->GetJavaVM(env, &service->jvm) != JNI_OK || service->jvm == NULL) {
        free(service);
        throw_illegal_state(env, "JNI GetJavaVM failed for native timer service");
        return 0;
    }

    service->service = (*env)->NewGlobalRef(env, service_object);
    if (service->service == NULL) {
        free(service);
        throw_illegal_state(env, "failed to root native timer service");
        return 0;
    }

    jclass service_class = (*env)->GetObjectClass(env, service_object);
    if (service_class == NULL) {
        (*env)->DeleteGlobalRef(env, service->service);
        free(service);
        return 0;
    }
    service->fire = (*env)->GetMethodID(env, service_class, "nativeFire", "(J)V");
    (*env)->DeleteLocalRef(env, service_class);
    if (service->fire == NULL) {
        (*env)->DeleteGlobalRef(env, service->service);
        free(service);
        return 0;
    }

    if (pthread_mutex_init(&service->mutex, NULL) != 0) {
        (*env)->DeleteGlobalRef(env, service->service);
        free(service);
        throw_illegal_state(env, "pthread_mutex_init failed for native timer service");
        return 0;
    }
    if (pthread_cond_init(&service->condition, NULL) != 0) {
        pthread_mutex_destroy(&service->mutex);
        (*env)->DeleteGlobalRef(env, service->service);
        free(service);
        throw_illegal_state(env, "pthread_cond_init failed for native timer service");
        return 0;
    }

    if (pthread_create(&service->thread, NULL, timer_service_main, service) != 0) {
        pthread_cond_destroy(&service->condition);
        pthread_mutex_destroy(&service->mutex);
        (*env)->DeleteGlobalRef(env, service->service);
        free(service);
        throw_illegal_state(env, "pthread_create failed for native timer service");
        return 0;
    }
    pthread_detach(service->thread);
    return (jlong)(intptr_t)service;
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_NativeTimerService_nativeSchedule(
        JNIEnv *env,
        jclass cls,
        jlong handle,
        jlong id,
        jlong delay_nanos) {
    (void)env;
    (void)cls;
    ores_timer_service *service = (ores_timer_service *)(intptr_t)handle;
    if (service == NULL || id <= 0 || delay_nanos < 0) return JNI_FALSE;

    ores_timer_entry *entry =
            (ores_timer_entry *)calloc(1, sizeof(*entry));
    if (entry == NULL) return JNI_FALSE;

    uint64_t now = monotonic_nanos();
    uint64_t delay = (uint64_t)delay_nanos;
    entry->id = (uint64_t)id;
    entry->deadline_nanos =
            UINT64_MAX - now < delay ? UINT64_MAX : now + delay;

    pthread_mutex_lock(&service->mutex);
    ores_timer_entry **cursor = &service->head;
    while (*cursor != NULL
            && (*cursor)->deadline_nanos <= entry->deadline_nanos) {
        cursor = &(*cursor)->next;
    }
    int became_head = cursor == &service->head;
    entry->next = *cursor;
    *cursor = entry;
    if (became_head) pthread_cond_signal(&service->condition);
    pthread_mutex_unlock(&service->mutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_NativeTimerService_nativeCancel(
        JNIEnv *env,
        jclass cls,
        jlong handle,
        jlong id) {
    (void)env;
    (void)cls;
    ores_timer_service *service = (ores_timer_service *)(intptr_t)handle;
    if (service == NULL || id <= 0) return JNI_FALSE;

    pthread_mutex_lock(&service->mutex);
    ores_timer_entry **cursor = &service->head;
    ores_timer_entry *removed = NULL;
    int removed_head = 0;
    while (*cursor != NULL) {
        if ((*cursor)->id == (uint64_t)id) {
            removed = *cursor;
            removed_head = cursor == &service->head;
            *cursor = removed->next;
            break;
        }
        cursor = &(*cursor)->next;
    }
    if (removed_head) pthread_cond_signal(&service->condition);
    pthread_mutex_unlock(&service->mutex);

    if (removed != NULL) {
        free(removed);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}
