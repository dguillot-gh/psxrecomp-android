/* gl_thread_route.h — main.cpp only: run every gl_renderer_* call on the GL
 * render thread (gl_thread.h). Each name becomes a function-like macro whose
 * body calls the real function through glt_sync; a macro name is not
 * re-expanded inside its own body. Synchronous: the game thread waits, so the
 * lambda may capture the arguments by reference. A no-op wrapper while the
 * render thread is off. Include after gpu_gl_renderer.h; declare these
 * functions as (name)(...) after it. */
#ifndef PSX_GL_THREAD_ROUTE_H
#define PSX_GL_THREAD_ROUTE_H
#include "gl_thread.h"
#define gl_renderer_capture_display_hires(...) glt_sync([&]() { return gl_renderer_capture_display_hires(__VA_ARGS__); })
#define gl_renderer_cpu_auth_dual(...) glt_sync([&]() { return gl_renderer_cpu_auth_dual(__VA_ARGS__); })
#define gl_renderer_fit_wide_aspect(...) glt_sync([&]() { return gl_renderer_fit_wide_aspect(__VA_ARGS__); })
#define gl_renderer_flush_cpu_uploads(...) glt_sync([&]() { return gl_renderer_flush_cpu_uploads(__VA_ARGS__); })
#define gl_renderer_interpolation_owns_cadence(...) glt_sync([&]() { return gl_renderer_interpolation_owns_cadence(__VA_ARGS__); })
#define gl_renderer_invalidate_present(...) glt_sync([&]() { return gl_renderer_invalidate_present(__VA_ARGS__); })
#define gl_renderer_present(...) glt_sync([&]() { return gl_renderer_present(__VA_ARGS__); })
#define gl_renderer_present_blank(...) glt_sync([&]() { return gl_renderer_present_blank(__VA_ARGS__); })
#define gl_renderer_present_hold_last(...) glt_sync([&]() { return gl_renderer_present_hold_last(__VA_ARGS__); })
#define gl_renderer_present_probe_reset(...) glt_sync([&]() { return gl_renderer_present_probe_reset(__VA_ARGS__); })
#define gl_renderer_present_probe_take(...) glt_sync([&]() { return gl_renderer_present_probe_take(__VA_ARGS__); })
#define gl_renderer_present_rect_dirty(...) glt_sync([&]() { return gl_renderer_present_rect_dirty(__VA_ARGS__); })
#define gl_renderer_present_vram(...) glt_sync([&]() { return gl_renderer_present_vram(__VA_ARGS__); })
#define gl_renderer_present_wide_fbo(...) glt_sync([&]() { return gl_renderer_present_wide_fbo(__VA_ARGS__); })
#define gl_renderer_restage_vram_after_savestate(...) glt_sync([&]() { return gl_renderer_restage_vram_after_savestate(__VA_ARGS__); })
#define gl_renderer_runtime_diag(...) glt_sync([&]() { return gl_renderer_runtime_diag(__VA_ARGS__); })
#define gl_renderer_set_bezel(...) glt_sync([&]() { return gl_renderer_set_bezel(__VA_ARGS__); })
#define gl_renderer_set_cpu_auth_dual(...) glt_sync([&]() { return gl_renderer_set_cpu_auth_dual(__VA_ARGS__); })
#define gl_renderer_set_display_aspect(...) glt_sync([&]() { return gl_renderer_set_display_aspect(__VA_ARGS__); })
#define gl_renderer_set_fmv_filter(...) glt_sync([&]() { return gl_renderer_set_fmv_filter(__VA_ARGS__); })
#define gl_renderer_set_interpolation(...) glt_sync([&]() { return gl_renderer_set_interpolation(__VA_ARGS__); })
#define gl_renderer_set_interpolation_blend(...) glt_sync([&]() { return gl_renderer_set_interpolation_blend(__VA_ARGS__); })
#define gl_renderer_set_interpolation_source(...) glt_sync([&]() { return gl_renderer_set_interpolation_source(__VA_ARGS__); })
#define gl_renderer_set_interpolation_suspended(...) glt_sync([&]() { return gl_renderer_set_interpolation_suspended(__VA_ARGS__); })
#define gl_renderer_set_present_stretch(...) glt_sync([&]() { return gl_renderer_set_present_stretch(__VA_ARGS__); })
#define gl_renderer_set_scanlines(...) glt_sync([&]() { return gl_renderer_set_scanlines(__VA_ARGS__); })
#define gl_renderer_set_swap_interval(...) glt_sync([&]() { return gl_renderer_set_swap_interval(__VA_ARGS__); })
#define gl_renderer_set_texture_window_batching(...) glt_sync([&]() { return gl_renderer_set_texture_window_batching(__VA_ARGS__); })
#define gl_renderer_set_wide_fast(...) glt_sync([&]() { return gl_renderer_set_wide_fast(__VA_ARGS__); })
#define gl_renderer_sync_cpu(...) glt_sync([&]() { return gl_renderer_sync_cpu(__VA_ARGS__); })
#endif
