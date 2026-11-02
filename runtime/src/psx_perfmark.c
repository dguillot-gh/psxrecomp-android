/* perfmark for psxrecomp (runtime/include/perfmark.h; the canonical copy is D:\phoneperf\perfmark\).
 *
 * Off unless ./perfmark exists at start (on Android the process works from the app's files folder, so
 * `adb shell run-as <package> touch files/perfmark`); then one row per presented frame goes to ./perfmark.csv.
 *
 * Exact timers: spu (spu_render), present (gl_swap_with_osd, including a blocking vsync swap) and pace
 * (frame_pacer_wait). The guest's own time is split by sampling g_exec_phase -- the innermost backend the emu thread
 * is in, set at every boundary in every build (dirty_ram_interp.c) -- from a thread of ours at ~2 kHz, the same
 * idea as the debug server's phase_profile (compiled out of release builds). Each interval between two samples goes
 * to the phase seen at its end, so a frame's interp/native/static/gp0 are estimates (~0.5 ms grain) that average out
 * over a run; phase 0 (host/other) is not sampled: it is what the exact timers and the remainder cover. */
#ifndef PSX_PERFMARK
#define PSX_PERFMARK
#endif
#define PERFMARK_IMPL
#include "perfmark.h"
#include "psx_perfmark.h"

#if defined(_WIN32)
#include <windows.h>
#else
#include <pthread.h>
#include <time.h>
#endif

extern int g_exec_phase; /* dirty_ram_interp.c: 0 other, 1 interp, 2 native, 3 static, 4 gpu-gp0 */

static volatile int s_sampler_run = 0;

static void sampler_loop(void) {
    /* g_exec_phase -> perfmark phase (-1: not sampled) */
    static const int map[5] = {-1, PSX_PM_INTERP, PSX_PM_NATIVE, PSX_PM_STATIC, PSX_PM_GP0};
    uint64_t last = pm_now();
    while (s_sampler_run) {
#if defined(_WIN32)
        Sleep(1);
#else
        struct timespec ts = {0, 500000};
        nanosleep(&ts, NULL);
#endif
        const int p = g_exec_phase;
        const uint64_t now = pm_now();
        if (p >= 1 && p <= 4) pm_add(map[p], now - last);
        last = now;
    }
}

#if defined(_WIN32)
static DWORD WINAPI sampler_main(LPVOID arg) { (void)arg; sampler_loop(); return 0; }
#else
static void* sampler_main(void* arg) { (void)arg; sampler_loop(); return NULL; }
#endif

void psx_perfmark_start(const char* game) {
    if (!pm_open(".", game)) return;
    pm_phase(PSX_PM_SPU, "spu", 0);
    pm_phase(PSX_PM_PRESENT, "present", 1);
    pm_phase(PSX_PM_PACE, "pace", 1);
    pm_phase(PSX_PM_INTERP, "s_interp", 0);
    pm_phase(PSX_PM_NATIVE, "s_native", 0);
    pm_phase(PSX_PM_STATIC, "s_static", 0);
    pm_phase(PSX_PM_GP0, "s_gp0", 0);
    pm_note("framework", "psxrecomp");
    pm_note("rest", "host = wall - all phases: SDL, input, audio queue, GL scene upload outside GP0, idle");
    pm_note("sampled", "s_* phases are sampled at ~2 kHz from g_exec_phase (estimates per frame, exact on average)");
    s_sampler_run = 1;
#if defined(_WIN32)
    HANDLE h = CreateThread(NULL, 0, sampler_main, NULL, 0, NULL);
    if (h) CloseHandle(h); else s_sampler_run = 0;
#else
    pthread_t t;
    if (pthread_create(&t, NULL, sampler_main, NULL) == 0) pthread_detach(t); else s_sampler_run = 0;
#endif
    if (!s_sampler_run) pm_note("sampler", "could not start: s_* phases stay 0");
}
