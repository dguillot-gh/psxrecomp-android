/* gl_thread.cpp — optional GL render thread (see gl_thread.h).
 *
 * Ring: one producer (the game thread), one consumer (the render thread).
 * Records are [u32 op][u32 len][len bytes] padded to 8; a record that does not
 * fit before the end of the ring is preceded by a WRAP record. The producer
 * waits when the ring is full; the consumer sleeps when it is empty and the
 * producer only signals when the consumer said it is going to sleep, so a
 * busy frame costs no syscalls per record.
 *
 * Internal ops (high range) carry sync calls and context ownership changes;
 * everything else goes to the executor (gpu_render.c). */
#include "gl_thread.h"
#include "psx_sdl.h"

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <thread>

namespace {

constexpr uint32_t kRingBytes = 8u << 20;     /* a busy frame is ~0.3-1 MB of records */
constexpr uint32_t OP_WRAP    = 0xFFFFFF00u;
constexpr uint32_t OP_CALL    = 0xFFFFFF01u;  /* payload: fn, arg, done-flag pointer */
constexpr uint32_t OP_ACQUIRE = 0xFFFFFF02u;  /* payload: SDL_GLContext */
constexpr uint32_t OP_RELEASE = 0xFFFFFF03u;  /* payload: done-flag pointer */
constexpr uint32_t OP_QUIT    = 0xFFFFFF04u;
constexpr uint32_t OP_FN      = 0xFFFFFF05u;  /* payload: fn, frame flag, copied argument bytes */
constexpr uint32_t kFnMaxArg  = 256;
constexpr int      kMaxFramesInFlight = 2;    /* async presents queued ahead of the render thread */

struct CallRec { void (*fn)(void *); void *arg; std::atomic<int> *done; };

int                 g_configured = 0;
std::atomic<int>    g_active{0};
SDL_Window         *g_win = nullptr;
SDL_GLContext       g_ctx = nullptr;
int                 g_render_owns = 0;        /* game-thread view: context on the render thread */
glt_exec_fn         g_exec = nullptr;
std::thread         g_thread;
std::thread::id     g_render_id;
std::thread::id     g_game_id;                /* the thread that called glt_start */
std::atomic<int>    g_frames_inflight{0};
int                 g_watch_on = 0;           /* pause event watch installed */

uint8_t            *g_ring = nullptr;
std::atomic<uint64_t> g_head{0};              /* bytes written (producer) */
std::atomic<uint64_t> g_tail{0};              /* bytes consumed (consumer) */
std::atomic<int>    g_sleeping{0};
std::mutex          g_mtx;
std::condition_variable g_cv_data;            /* consumer waits for data */
std::condition_variable g_cv_space;           /* producer waits for space / completion */

std::atomic<uint64_t> g_n_posts{0}, g_n_syncs{0}, g_n_handbacks{0}, g_n_full_waits{0},
                      g_n_async{0}, g_n_frame_waits{0};

inline uint32_t pad8(uint32_t n) { return (n + 7u) & ~7u; }

void wake_consumer() {
    if (g_sleeping.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_cv_data.notify_one();
    }
}

/* Reserve `total` contiguous bytes (header included), waiting for space. */
uint8_t *reserve(uint32_t total) {
    for (;;) {
        uint64_t head = g_head.load(std::memory_order_relaxed);
        uint32_t pos = (uint32_t)(head % kRingBytes);
        uint32_t to_end = kRingBytes - pos;
        uint32_t need = total <= to_end ? total : to_end + total;   /* wrap: skip the tail end */
        uint64_t tail = g_tail.load(std::memory_order_acquire);
        if (head + need - tail <= kRingBytes) {
            if (total > to_end) {
                uint32_t hdr[2] = { OP_WRAP, to_end - 8u };
                std::memcpy(g_ring + pos, hdr, 8);
                g_head.store(head + to_end, std::memory_order_release);
                continue;
            }
            return g_ring + pos;
        }
        g_n_full_waits.fetch_add(1, std::memory_order_relaxed);
        wake_consumer();
        std::unique_lock<std::mutex> lk(g_mtx);
        g_cv_space.wait_for(lk, std::chrono::milliseconds(2));
    }
}

void commit(uint32_t total) {
    g_head.store(g_head.load(std::memory_order_relaxed) + total, std::memory_order_release);
    wake_consumer();
}

void post_raw(uint32_t op, const void *a, uint32_t alen, const void *b, uint32_t blen) {
    uint32_t len = alen + blen;
    uint32_t total = 8u + pad8(len);
    uint8_t *p = reserve(total);
    uint32_t hdr[2] = { op, len };
    std::memcpy(p, hdr, 8);
    if (alen) std::memcpy(p + 8, a, alen);
    if (blen) std::memcpy(p + 8 + alen, b, blen);
    commit(total);
}

void wait_flag(std::atomic<int> *flag) {
    for (int spin = 0; spin < 2000; spin++)
        if (flag->load(std::memory_order_acquire)) return;
    std::unique_lock<std::mutex> lk(g_mtx);
    while (!flag->load(std::memory_order_acquire))
        g_cv_space.wait_for(lk, std::chrono::milliseconds(1));
}

void signal_flag(std::atomic<int> *flag) {
    flag->store(1, std::memory_order_release);
    std::lock_guard<std::mutex> lk(g_mtx);
    g_cv_space.notify_all();
}

/* Game thread: make sure the render thread holds the context. */
void ensure_render_owns() {
    if (g_render_owns) return;
    /* SDL's lifecycle code may have recreated the context during the pump. */
    SDL_GLContext cur = SDL_GL_GetCurrentContext();
    if (cur) g_ctx = cur;
    SDL_GL_MakeCurrent(g_win, nullptr);
    post_raw(OP_ACQUIRE, &g_ctx, sizeof g_ctx, nullptr, 0);
    g_render_owns = 1;
}

void render_main() {
    g_render_id = std::this_thread::get_id();
    for (;;) {
        uint64_t tail = g_tail.load(std::memory_order_relaxed);
        uint64_t head = g_head.load(std::memory_order_acquire);
        if (tail == head) {
            int spun = 0;
            while (spun < 4000 && g_head.load(std::memory_order_acquire) == tail) spun++;
            if (g_head.load(std::memory_order_acquire) == tail) {
                std::unique_lock<std::mutex> lk(g_mtx);
                g_sleeping.store(1, std::memory_order_release);
                g_cv_space.notify_all();              /* drained: wake a waiting producer */
                while (g_head.load(std::memory_order_acquire) == tail)
                    g_cv_data.wait_for(lk, std::chrono::milliseconds(4));
                g_sleeping.store(0, std::memory_order_release);
            }
            continue;
        }
        const uint8_t *p = g_ring + (uint32_t)(tail % kRingBytes);
        uint32_t hdr[2];
        std::memcpy(hdr, p, 8);
        const uint32_t op = hdr[0], len = hdr[1];
        const uint8_t *data = p + 8;
        bool quit = false;
        switch (op) {
        case OP_WRAP:
            break;
        case OP_CALL: {
            CallRec rec;
            std::memcpy(&rec, data, sizeof rec);
            rec.fn(rec.arg);
            signal_flag(rec.done);
            break;
        }
        case OP_ACQUIRE: {
            SDL_GLContext ctx;
            std::memcpy(&ctx, data, sizeof ctx);
            if (SDL_GL_MakeCurrent(g_win, ctx) != 0)
                std::fprintf(stdout, "psxrecomp: GL thread could not take the context (%s)\n",
                             SDL_GetError());
            break;
        }
        case OP_RELEASE: {
            std::atomic<int> *done;
            std::memcpy(&done, data, sizeof done);
            SDL_GL_MakeCurrent(g_win, nullptr);
            signal_flag(done);
            break;
        }
        case OP_QUIT:
            quit = true;
            break;
        case OP_FN: {
            void (*fn)(void *);
            uint32_t frame;
            std::memcpy(&fn, data, sizeof fn);
            std::memcpy(&frame, data + sizeof fn, sizeof frame);
            alignas(16) uint8_t arg[kFnMaxArg];
            const uint32_t hdr_len = (uint32_t)(sizeof fn + 8);
            std::memcpy(arg, data + hdr_len, len - hdr_len);
            fn(arg);
            if (frame) {
                g_frames_inflight.fetch_sub(1, std::memory_order_acq_rel);
                std::lock_guard<std::mutex> lk(g_mtx);
                g_cv_space.notify_all();
            }
            break;
        }
        default:
            if (g_exec) g_exec(op, data, len);
            break;
        }
        g_tail.store(tail + 8u + pad8(len), std::memory_order_release);
        if (quit) {
            SDL_GL_MakeCurrent(g_win, nullptr);
            std::lock_guard<std::mutex> lk(g_mtx);
            g_cv_space.notify_all();
            return;
        }
    }
}

/* SDL3 Android: the pause lifecycle runs inside the game thread's event pump
 * and backs up the GL context there (SDL_GL_GetCurrentContext + MakeCurrent
 * NULL), then blocks until resume and restores it there. It sends
 * WILL_ENTER_BACKGROUND first, synchronously to event watches on that same
 * thread, so handing the context back from a watch makes the backup see it.
 * Nothing else in the pump touches the context, so no per-pump handback is
 * needed while this watch is installed. */
bool pause_watch(void *, SDL_Event *ev) {
    if (ev->type == SDL_EVENT_WILL_ENTER_BACKGROUND || ev->type == SDL_EVENT_TERMINATING)
        glt_handback();
    return true;
}

}  // namespace

extern "C" {

void glt_configure(int on) { g_configured = on ? 1 : 0; }
int  glt_configured(void) { return g_configured; }
int  glt_active(void) { return g_active.load(std::memory_order_acquire); }
int  glt_on_render_thread(void) {
    return glt_active() && std::this_thread::get_id() == g_render_id;
}
void glt_set_executor(glt_exec_fn fn) { g_exec = fn; }

int glt_start(SDL_Window *win) {
    if (!g_configured || glt_active() || !win) return 0;
    SDL_GLContext ctx = SDL_GL_GetCurrentContext();
    if (!ctx) return 0;
    if (!g_ring) g_ring = new (std::nothrow) uint8_t[kRingBytes];
    if (!g_ring) return 0;
    g_win = win;
    g_ctx = ctx;
    g_head.store(0); g_tail.store(0);
    g_render_owns = 0;
    g_game_id = std::this_thread::get_id();
    g_frames_inflight.store(0);
    g_thread = std::thread(render_main);
    g_active.store(1, std::memory_order_release);
    ensure_render_owns();
#if defined(PSX_SDL3) && defined(__ANDROID__)
    g_watch_on = SDL_AddEventWatch(pause_watch, nullptr) ? 1 : 0;
#endif
    std::fprintf(stdout, "psxrecomp: GL render thread started (ring %u MB, %s)\n", kRingBytes >> 20,
                 g_watch_on ? "context handback on pause" : "context handback every event pump");
    return 1;
}

void glt_stop(void) {
    if (!glt_active()) return;
#if defined(PSX_SDL3) && defined(__ANDROID__)
    if (g_watch_on) SDL_RemoveEventWatch(pause_watch, nullptr);
    g_watch_on = 0;
#endif
    ensure_render_owns();
    post_raw(OP_QUIT, nullptr, 0, nullptr, 0);
    g_thread.join();
    g_active.store(0, std::memory_order_release);
    g_render_owns = 0;
    SDL_GL_MakeCurrent(g_win, g_ctx);
}

void glt_handback(void) {
    if (!glt_active() || !g_render_owns) return;
    if (std::this_thread::get_id() != g_game_id) return;
    std::atomic<int> done{0};
    std::atomic<int> *pd = &done;
    post_raw(OP_RELEASE, &pd, sizeof pd, nullptr, 0);
    wait_flag(&done);
    SDL_GL_MakeCurrent(g_win, g_ctx);
    g_render_owns = 0;
    g_n_handbacks.fetch_add(1, std::memory_order_relaxed);
}

void glt_pump_handback(void) {
    if (!g_watch_on) glt_handback();
}

void glt_post_fn(void (*fn)(void *), const void *arg, uint32_t len, int frame) {
    if (!glt_active() || std::this_thread::get_id() == g_render_id || len > kFnMaxArg) {
        if (len > kFnMaxArg) glt_call(fn, const_cast<void *>(arg));
        else fn(const_cast<void *>(arg));
        return;
    }
    ensure_render_owns();
    if (frame) {
        if (g_frames_inflight.load(std::memory_order_acquire) >= kMaxFramesInFlight) {
            g_n_frame_waits.fetch_add(1, std::memory_order_relaxed);
            wake_consumer();
            std::unique_lock<std::mutex> lk(g_mtx);
            while (g_frames_inflight.load(std::memory_order_acquire) >= kMaxFramesInFlight)
                g_cv_space.wait_for(lk, std::chrono::milliseconds(1));
        }
        g_frames_inflight.fetch_add(1, std::memory_order_acq_rel);
    }
    uint8_t hdr[sizeof fn + 8] = {};
    const uint32_t fr = frame ? 1u : 0u;
    std::memcpy(hdr, &fn, sizeof fn);
    std::memcpy(hdr + sizeof fn, &fr, sizeof fr);
    post_raw(OP_FN, hdr, sizeof hdr, arg, len);
    g_n_async.fetch_add(1, std::memory_order_relaxed);
}

void glt_post(uint32_t op, const void *data, uint32_t len, const void *extra, uint32_t extra_len) {
    ensure_render_owns();
    post_raw(op, data, len, extra, extra_len);
    g_n_posts.fetch_add(1, std::memory_order_relaxed);
}

void glt_call(void (*fn)(void *), void *arg) {
    if (!glt_active() || std::this_thread::get_id() == g_render_id) { fn(arg); return; }
    ensure_render_owns();
    std::atomic<int> done{0};
    CallRec rec{fn, arg, &done};
    post_raw(OP_CALL, &rec, sizeof rec, nullptr, 0);
    wait_flag(&done);
    g_n_syncs.fetch_add(1, std::memory_order_relaxed);
}

static void nop(void *) {}
void glt_drain(void) { glt_call(nop, nullptr); }

void glt_stats(uint64_t out[4]) {
    out[0] = g_n_posts.load(); out[1] = g_n_syncs.load();
    out[2] = g_n_handbacks.load(); out[3] = g_n_full_waits.load();
}

void glt_stats2(uint64_t out[2]) {
    out[0] = g_n_async.load(); out[1] = g_n_frame_waits.load();
}

}  /* extern "C" */
