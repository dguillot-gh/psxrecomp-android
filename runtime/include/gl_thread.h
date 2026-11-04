/* gl_thread.h — optional GL render thread (Android).
 *
 * One render thread owns the GL context. The game thread POSTS fire-and-forget
 * command records (draws, state, uploads with their data copied) into a
 * single-producer/single-consumer byte ring, and makes SYNC calls (run a
 * function on the render thread and wait) for anything that returns data.
 * gpu_render.c replays the records into the real backend table.
 *
 * Context ownership: SDL's Android lifecycle code backs up / restores the GL
 * context on the game thread inside its event pump, so glt_handback() returns
 * the context to the game thread before every event pump, and the next post or
 * sync call hands it to the render thread again.
 *
 * Everything here is a no-op passthrough until glt_start() succeeded. */
#ifndef PSX_GL_THREAD_H
#define PSX_GL_THREAD_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct SDL_Window;

/* [video] gl_thread: wanted by the config (default off). */
void glt_configure(int on);
int  glt_configured(void);
/* The render thread is running (posts and syncs go through it). */
int  glt_active(void);
/* Called on the game thread right after gl_renderer_init_context() made the
 * context current there. Returns 1 when the render thread took it over. */
int  glt_start(struct SDL_Window *win);
/* Drain, stop the render thread and make the context current on the caller. */
void glt_stop(void);
/* Game thread, before pumping SDL events: drain and take the context back. */
void glt_handback(void);
int  glt_on_render_thread(void);

/* Replays one posted record on the render thread (gpu_render.c). */
typedef void (*glt_exec_fn)(uint32_t op, const void *data, uint32_t len);
void glt_set_executor(glt_exec_fn fn);
/* Post a record: op + len bytes (copied). Optional second block appended. */
void glt_post(uint32_t op, const void *data, uint32_t len,
              const void *extra, uint32_t extra_len);
/* Run fn(arg) on the render thread and wait for it. Direct call when inactive
 * or already on the render thread. */
void glt_call(void (*fn)(void *), void *arg);
/* Wait until every posted record ran. */
void glt_drain(void);

/* Counters for the [GLT] log line: posts, syncs, handbacks, waits on a full ring. */
void glt_stats(uint64_t out[4]);

#ifdef __cplusplus
}

#include <type_traits>

/* C++: run a callable on the render thread and return its result. */
template <class F>
static inline auto glt_sync(F &&f) -> decltype(f()) {
    using R = decltype(f());
    if constexpr (std::is_void<R>::value) {
        struct Box { F *f; } box{&f};
        glt_call([](void *p) { (*static_cast<Box *>(p)->f)(); }, &box);
    } else {
        struct Box { F *f; R r; } box{&f, R{}};
        glt_call([](void *p) { auto *b = static_cast<Box *>(p); b->r = (*b->f)(); }, &box);
        return box.r;
    }
}
#endif

#endif /* PSX_GL_THREAD_H */
