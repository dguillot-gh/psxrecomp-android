/* perfmark.h -- per-frame phase timing for the recomp ports (DS, PS1, N64), written for the LG V30 / Pixel 8 work.
 *
 * One row per frame into <dir>/perfmark.csv: wall time, the calling thread's CPU time, the core it ended on, and the
 * time spent in each named phase (ARM7, 2D, present...). Off unless <dir>/perfmark exists when pm_open() runs; off,
 * every macro is one predictable branch. The file's content can turn on extras:
 *   trace   phases marked coarse also become ATrace sections (systrace / Perfetto / Snapdragon Profiler trace mode)
 * D:\phoneperf\phoneperf.cmd creates the flag, launches the game, pulls the CSV and reports. See README.md there.
 *
 * Use (C or C++), in exactly one source file:   #define PERFMARK_IMPL   then   #include "perfmark.h"
 *   pm_open(data_dir, "st");            once, early (data_dir: where the app keeps its files)
 *   pm_phase(PM_P0, "arm7", 0);         name the phases (0..PM_MAX_PHASES-1); coarse=1 also traces it
 *   PM_BEGIN(PM_P0); ... PM_END(PM_P0); around the work (same scope; nesting different phases is fine)
 *   pm_count(PM_C0, "advance", 1);      optional counters, summed per frame
 *   pm_frame();                          once per emulated frame, on the thread that runs the frame loop
 *   pm_note("renderer", "compute 4x");  free-form facts, written as "# key=value" lines
 * Phases may be timed on any thread (sums are atomic); wall/cpu/core are those of the thread calling pm_frame().
 * The timer is the arm64 virtual counter; elsewhere clock_gettime / QueryPerformanceCounter. On the LG V30 a read of
 * it costs ~121 ns (the kernel traps it: Cortex-A73 erratum 858921), so a timed phase costs ~250 ns per entry: fine for
 * a few hundred entries a frame, not for thousands. For those, tag instead (one store each, no clock read):
 *   pm_sampler_start();                 once after pm_open: a thread samples the tag ~2000 times a second
 *   PM_TAG_BEGIN(PM_P4); ... PM_TAG_END(PM_P4);   the interval between two samples goes to the tag seen at its end
 * Tagged phases are estimates per frame (~0.5 ms grain) and exact on average; name them "s_..." so reports say so.
 * Tags are for the frame-loop thread only, and do not nest inside each other (an inner tag restores the outer one).
 * Nothing here changes what the game does: it only reads clocks and writes its own file. */
#ifndef PERFMARK_H
#define PERFMARK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { PM_MAX_PHASES = 8, PM_MAX_COUNTERS = 4 };
enum { PM_P0, PM_P1, PM_P2, PM_P3, PM_P4, PM_P5, PM_P6, PM_P7 };
enum { PM_C0, PM_C1, PM_C2, PM_C3 };

extern int pm_on;     /* 1 while recording */
extern int pm_trace;  /* 1 when coarse phases also go to ATrace */
extern int pm_coarse[PM_MAX_PHASES];
extern volatile int pm_tag;  /* the tagged phase the frame-loop thread is in now; -1: none */

int  pm_open(const char* dir, const char* game);
int  pm_sampler_start(void);
void pm_phase(int id, const char* name, int coarse);
void pm_add(int id, uint64_t ticks);
void pm_count(int id, const char* name, double v);
void pm_frame(void);
void pm_note(const char* key, const char* value);
void pm_close(void);
void pm_trace_begin(int id);
void pm_trace_end(void);

#if defined(__aarch64__) && !defined(_MSC_VER)
static inline uint64_t pm_now(void) {
    uint64_t t;
    __asm__ __volatile__("mrs %0, cntvct_el0" : "=r"(t));
    return t;
}
#else
uint64_t pm_now(void);
#endif

#define PM_BEGIN(id) \
    const uint64_t pm_t_##id = pm_on ? pm_now() : 0; \
    if (pm_trace && pm_coarse[id]) pm_trace_begin(id)
#define PM_END(id) \
    do { \
        if (pm_on) pm_add((id), pm_now() - pm_t_##id); \
        if (pm_trace && pm_coarse[id]) pm_trace_end(); \
    } while (0)

#define PM_TAG_BEGIN(id) \
    const int pm_g_##id = pm_tag; \
    if (pm_on) pm_tag = (id)
#define PM_TAG_END(id) \
    do { \
        if (pm_on) pm_tag = pm_g_##id; \
    } while (0)

#ifdef __cplusplus
}
#endif

/* ------------------------------------------------------------------------------------------------------------- */
#ifdef PERFMARK_IMPL

#include <stdio.h>
#include <string.h>
#include <time.h>
#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <pthread.h>
#include <unistd.h>
#endif
#if defined(__linux__) || defined(__ANDROID__)
#include <sys/syscall.h>
#endif
#if defined(__ANDROID__)
#include <dlfcn.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

int pm_on = 0;
int pm_trace = 0;
volatile int pm_tag = -1;
static volatile int pm_sampling_ = 0;

static FILE* pm_file_ = NULL;
static uint64_t pm_freq_ = 1;  /* ticks per second of pm_now() */
static char pm_names_[PM_MAX_PHASES][24];
int pm_coarse[PM_MAX_PHASES];
static char pm_cnames_[PM_MAX_COUNTERS][24];
static uint64_t pm_acc_[PM_MAX_PHASES];
static double pm_cacc_[PM_MAX_COUNTERS];
static uint64_t pm_frames_ = 0;
static uint64_t pm_t0_ = 0, pm_last_ = 0;
static double pm_cpu_last_ = -1.0;
static int pm_header_done_ = 0;

#if defined(__GNUC__) || defined(__clang__)
#define PM_XCHG(p) __atomic_exchange_n((p), (uint64_t)0, __ATOMIC_RELAXED)
#define PM_ADD(p, v) __atomic_fetch_add((p), (v), __ATOMIC_RELAXED)
#else
static uint64_t pm_xchg_(uint64_t* p) { uint64_t v = *p; *p = 0; return v; }
#define PM_XCHG(p) pm_xchg_(p)
#define PM_ADD(p, v) (*(p) += (v))
#endif

#if defined(__aarch64__) && !defined(_MSC_VER)
static uint64_t pm_freq_read_(void) {
    uint64_t f;
    __asm__ __volatile__("mrs %0, cntfrq_el0" : "=r"(f));
    return f ? f : 19200000u;
}
#elif defined(_WIN32)
uint64_t pm_now(void) {
    LARGE_INTEGER t;
    QueryPerformanceCounter(&t);
    return (uint64_t)t.QuadPart;
}
static uint64_t pm_freq_read_(void) {
    LARGE_INTEGER f;
    QueryPerformanceFrequency(&f);
    return (uint64_t)f.QuadPart;
}
#else
uint64_t pm_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000u + (uint64_t)ts.tv_nsec;
}
static uint64_t pm_freq_read_(void) { return 1000000000u; }
#endif

/* (CPU time of the calling thread, in ms; -1 where there is no such clock.) */
static double pm_thread_cpu_ms_(void) {
#if defined(CLOCK_THREAD_CPUTIME_ID) && !defined(_WIN32)
    struct timespec ts;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) == 0) return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
#endif
    return -1.0;
}

static int pm_core_(void) {
#if defined(__linux__) || defined(__ANDROID__)
    unsigned c = 0;
    return syscall(SYS_getcpu, &c, (void*)0, (void*)0) == 0 ? (int)c : -1;
#else
    return -1;
#endif
}

#if defined(__ANDROID__)
/* (ATrace, looked up at run time: the NDK functions are API 23+, the V30 is API 26.) */
static void (*pm_atb_)(const char*) = NULL;
static void (*pm_ate_)(void) = NULL;
#endif

void pm_trace_begin(int id) {
#if defined(__ANDROID__)
    if (id >= 0 && id < PM_MAX_PHASES && pm_atb_) pm_atb_(pm_names_[id]);
#else
    (void)id;
#endif
}

void pm_trace_end(void) {
#if defined(__ANDROID__)
    if (pm_ate_) pm_ate_();
#endif
}

int pm_open(const char* dir, const char* game) {
    char path[1024], flag[64] = {0};
    if (pm_file_ || !dir) return pm_on;
    snprintf(path, sizeof path, "%s/perfmark", dir);
    FILE* f = fopen(path, "rb");
    if (!f) return 0;
    size_t n = fread(flag, 1, sizeof flag - 1, f);
    flag[n] = 0;
    fclose(f);
    snprintf(path, sizeof path, "%s/perfmark.csv", dir);
    pm_file_ = fopen(path, "w");
    if (!pm_file_) return 0;
    setvbuf(pm_file_, NULL, _IOFBF, 1 << 16);
    pm_freq_ = pm_freq_read_();
#if defined(__ANDROID__)
    if (strstr(flag, "trace")) {
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (lib) {
            pm_atb_ = (void (*)(const char*))dlsym(lib, "ATrace_beginSection");
            pm_ate_ = (void (*)(void))dlsym(lib, "ATrace_endSection");
        }
        pm_trace = pm_atb_ && pm_ate_;
    }
#endif
    fprintf(pm_file_, "# perfmark 1\n# game=%s\n# timer_hz=%llu\n# trace=%d\n", game ? game : "?",
            (unsigned long long)pm_freq_, pm_trace);
    pm_t0_ = pm_last_ = pm_now();
    pm_cpu_last_ = pm_thread_cpu_ms_();
    pm_on = 1;
    return 1;
}

void pm_phase(int id, const char* name, int coarse) {
    if (id < 0 || id >= PM_MAX_PHASES || !name) return;
    snprintf(pm_names_[id], sizeof pm_names_[id], "%s", name);
    pm_coarse[id] = coarse;
}

void pm_add(int id, uint64_t ticks) {
    if (id >= 0 && id < PM_MAX_PHASES) PM_ADD(&pm_acc_[id], ticks);
}

void pm_count(int id, const char* name, double v) {
    if (!pm_on || id < 0 || id >= PM_MAX_COUNTERS) return;
    if (name && !pm_cnames_[id][0]) snprintf(pm_cnames_[id], sizeof pm_cnames_[id], "%s", name);
    pm_cacc_[id] += v;
}

void pm_note(const char* key, const char* value) {
    if (pm_on && key) fprintf(pm_file_, "# %s=%s\n", key, value ? value : "");
}

void pm_frame(void) {
    if (!pm_on) return;
    const uint64_t now = pm_now();
    const double cpu = pm_thread_cpu_ms_();
    const double tick_ms = 1000.0 / (double)pm_freq_;
    int i;
    if (!pm_header_done_) {
        /* (The header is written at the first frame, so phases and counters named by then are all in it.) */
        fputs("frame,t_ms,wall_ms,cpu_ms,core", pm_file_);
        for (i = 0; i < PM_MAX_PHASES; i++)
            if (pm_names_[i][0]) fprintf(pm_file_, ",%s", pm_names_[i]);
        for (i = 0; i < PM_MAX_COUNTERS; i++)
            if (pm_cnames_[i][0]) fprintf(pm_file_, ",n_%s", pm_cnames_[i]);
        fputc('\n', pm_file_);
        pm_header_done_ = 1;
    }
    fprintf(pm_file_, "%llu,%.2f,%.3f,%.3f,%d", (unsigned long long)pm_frames_, (now - pm_t0_) * tick_ms,
            (now - pm_last_) * tick_ms, (cpu >= 0 && pm_cpu_last_ >= 0) ? cpu - pm_cpu_last_ : -1.0, pm_core_());
    for (i = 0; i < PM_MAX_PHASES; i++) {
        const uint64_t t = PM_XCHG(&pm_acc_[i]);
        if (pm_names_[i][0]) fprintf(pm_file_, ",%.3f", t * tick_ms);
    }
    for (i = 0; i < PM_MAX_COUNTERS; i++) {
        if (pm_cnames_[i][0]) fprintf(pm_file_, ",%.0f", pm_cacc_[i]);
        pm_cacc_[i] = 0;
    }
    fputc('\n', pm_file_);
    if ((++pm_frames_ & 127) == 0) fflush(pm_file_);  /* (the phone may kill the app without warning) */
    pm_last_ = now;
    pm_cpu_last_ = cpu;
}

void pm_close(void) {
    if (!pm_file_) return;
    pm_sampling_ = 0;
    pm_on = 0;
    pm_trace = 0;
    fclose(pm_file_);
    pm_file_ = NULL;
}

/* (The tag sampler: every ~0.5 ms the time since the last sample goes to the tag the frame-loop thread shows now.) */
static void pm_sampler_loop_(void) {
    uint64_t last = pm_now();
    while (pm_sampling_) {
#if defined(_WIN32)
        Sleep(1);
#else
        struct timespec ts = {0, 500000};
        nanosleep(&ts, NULL);
#endif
        const int t = pm_tag;
        const uint64_t now = pm_now();
        if (t >= 0 && t < PM_MAX_PHASES) PM_ADD(&pm_acc_[t], now - last);
        last = now;
    }
}
#if defined(_WIN32)
static DWORD WINAPI pm_sampler_main_(LPVOID arg) { (void)arg; pm_sampler_loop_(); return 0; }
#else
static void* pm_sampler_main_(void* arg) { (void)arg; pm_sampler_loop_(); return NULL; }
#endif

int pm_sampler_start(void) {
    if (!pm_on || pm_sampling_) return pm_sampling_;
    pm_sampling_ = 1;
#if defined(_WIN32)
    HANDLE h = CreateThread(NULL, 0, pm_sampler_main_, NULL, 0, NULL);
    if (h) CloseHandle(h); else pm_sampling_ = 0;
#else
    pthread_t t;
    if (pthread_create(&t, NULL, pm_sampler_main_, NULL) == 0) pthread_detach(t); else pm_sampling_ = 0;
#endif
    if (pm_file_) fprintf(pm_file_, "# sampler=%s\n", pm_sampling_ ? "2 kHz" : "failed to start: tagged phases stay 0");
    return pm_sampling_;
}

#ifdef __cplusplus
}
#endif

#endif /* PERFMARK_IMPL */
#endif /* PERFMARK_H */
