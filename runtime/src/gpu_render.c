/* gpu_render.c — renderer facade / backend dispatch.
 *
 * gpu.c and main.cpp call the gr_* functions; this file forwards each to the
 * selected backend's vtable.  The software rasterizer is the default and the
 * fallback.  The OpenGL backend (gpu_gl_renderer.c) is selected via
 * gr_set_backend(GR_BACKEND_OPENGL) and supplies its table through
 * gl_backend_get(); if that returns NULL (GL unavailable / init failed) we
 * stay on software so a misconfigured [video] renderer never bricks boot.
 *
 * The software backend table points straight at the existing sw_* functions,
 * so the software path is byte-for-byte unchanged. */

#include "gpu_render.h"
#include "gpu_sw_renderer.h"
#include "gpu_hd_textures.h"
#include "gpu_uv.h"
#include "gl_thread.h"
#include <stdio.h>
#include <string.h>

static const GpuRenderBackend SW_BACKEND = {
    .name                          = "software",
    .init                          = sw_renderer_init,
    .set_scale                     = sw_renderer_set_scale,
    .scale                         = sw_renderer_scale,
    .set_texture_filter            = sw_set_texture_filter,
    .texture_filter                = sw_texture_filter,
    .set_semi_transparency         = sw_set_semi_transparency,
    .set_mask_bits                 = sw_set_mask_bits,
    .set_texture_window            = sw_set_texture_window,
    .set_color_modulation          = sw_set_color_modulation,
    .set_precise_triangle          = sw_set_precise_triangle,
    .set_perspective_triangle      = sw_set_perspective_triangle,
    .fill_rect                     = sw_fill_rect,
    .copy_rect                     = sw_copy_rect,
    .draw_flat_triangle            = sw_draw_flat_triangle,
    .draw_gouraud_triangle         = sw_draw_gouraud_triangle,
    .draw_textured_triangle        = sw_draw_textured_triangle,
    .draw_shaded_textured_triangle = sw_draw_shaded_textured_triangle,
    .draw_flat_rect                = sw_draw_flat_rect,
    .draw_textured_rect            = sw_draw_textured_rect,
    .draw_textured_rect_scaled     = sw_draw_textured_rect_scaled,
    .draw_line                     = sw_draw_line,
    .draw_shaded_line              = sw_draw_shaded_line,
    .render_display                = sw_render_display,
    .render_display_hires          = sw_render_display_hires,
    .vram_write                    = sw_vram_write,
    .vram_read                     = sw_vram_read,
    .vram_transfer_in              = sw_vram_transfer_in,
    .vram_transfer_out             = sw_vram_transfer_out,
    .set_draw_area                 = sw_set_draw_area,
    .get_draw_area                 = sw_get_draw_area,
    .set_draw_offset               = sw_set_draw_offset,
    .wide_configure                = sw_wide_configure,
    .wide_set_view                 = sw_wide_set_view,
    .wide_set_target               = sw_wide_set_target,
    .wide_disable_target           = sw_wide_disable_target,
    .wide_clear                    = sw_wide_clear,
    .wide_clear_margins            = sw_wide_clear_margins,
    .render_wide_display           = sw_render_wide_display,
    .wide_dump_full                = sw_wide_dump_full,
};

/* Supplied by gpu_gl_renderer.c; returns NULL until the GL backend is ready. */
extern const GpuRenderBackend *gl_backend_get(void);
/* Supplied by gpu_vk_renderer.c; returns NULL until the Vulkan backend is
 * ready (init succeeded). When Vulkan isn't compiled in it returns NULL. */
extern const GpuRenderBackend *vk_backend_get(void);

static const GpuRenderBackend *g_b         = &SW_BACKEND;
static GrBackend               g_effective = GR_BACKEND_SOFTWARE;
static int g_hd_offset_x, g_hd_offset_y;
static uint32_t g_hd_texture_window;
static int g_hd_semi, g_hd_perspective;
static void hd_observe_triangle(int x0,int y0,int u0,int v0,int x1,int y1,int u1,int v1,
    int x2,int y2,int u2,int v2,uint16_t cx,uint16_t cy,uint16_t tp) {
    if (g_effective == GR_BACKEND_OPENGL || !gpu_hd_textures_dump_enabled()) return;
    if (g_effective == GR_BACKEND_VULKAN) g_b->vram_read(0,0);
    int limits[4], xs[3]={x0,x1,x2},ys[3]={y0,y1,y2},us[3]={u0,u1,u2},vs[3]={v0,v1,v2};
    if (g_hd_perspective) psx_uv_tri_world_limits(us,vs,limits);
    else psx_uv_tri_limits(xs,ys,us,vs,limits);
    gpu_hd_textures_observe_draw(tp,cx,cy,limits,g_hd_texture_window,g_hd_semi);
}
static void hd_observe_rect(int u0,int v0,int u1,int v1,uint16_t cx,uint16_t cy,uint16_t tp) {
    if (g_effective == GR_BACKEND_OPENGL || !gpu_hd_textures_dump_enabled()) return;
    if (g_effective == GR_BACKEND_VULKAN) g_b->vram_read(0,0);
    int limits[4]; psx_uv_rect_limits(u0,v0,u1,v1,limits);
    gpu_hd_textures_observe_draw(tp,cx,cy,limits,g_hd_texture_window,g_hd_semi);
}

/* Residency describes guest native words, so any draw/copy which can touch
 * an upload ends its identity. Run after submission: an overlapping texture
 * draw must resolve its source before the destination invalidates it. */
static void hd_invalidate_draw(int x0, int y0, int x1, int y1) {
    int ax0, ay0, ax1, ay1;
    if (!gpu_hd_textures_active()) return;
    g_b->get_draw_area(&ax0, &ay0, &ax1, &ay1);
    x0 += g_hd_offset_x; x1 += g_hd_offset_x;
    y0 += g_hd_offset_y; y1 += g_hd_offset_y;
    if (x0 < ax0) x0 = ax0; if (x1 > ax1) x1 = ax1;
    if (y0 < ay0) y0 = ay0; if (y1 > ay1) y1 = ay1;
    if (x0 <= x1 && y0 <= y1)
        gpu_hd_textures_invalidate(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
}
static void hd_invalidate_triangle(int x0, int y0, int x1, int y1, int x2, int y2) {
    int xmin = x0, xmax = x0, ymin = y0, ymax = y0;
    if (x1 < xmin) xmin = x1; if (x2 < xmin) xmin = x2;
    if (x1 > xmax) xmax = x1; if (x2 > xmax) xmax = x2;
    if (y1 < ymin) ymin = y1; if (y2 < ymin) ymin = y2;
    if (y1 > ymax) ymax = y1; if (y2 > ymax) ymax = y2;
    hd_invalidate_draw(xmin, ymin, xmax, ymax);
}

void gr_set_backend(GrBackend backend) {
    if (backend == GR_BACKEND_OPENGL) {
        const GpuRenderBackend *gl = gl_backend_get();
        if (gl) {
            g_b = gl;
            g_effective = GR_BACKEND_OPENGL;
            fprintf(stdout, "psxrecomp: renderer = opengl (%s)\n", gl->name);
            return;
        }
        fprintf(stdout, "psxrecomp: renderer = opengl requested but unavailable "
                        "— falling back to software\n");
    } else if (backend == GR_BACKEND_VULKAN) {
        const GpuRenderBackend *vk = vk_backend_get();
        if (vk) {
            g_b = vk;
            g_effective = GR_BACKEND_VULKAN;
            fprintf(stdout, "psxrecomp: renderer = vulkan (%s)\n", vk->name);
            return;
        }
        fprintf(stdout, "psxrecomp: renderer = vulkan requested but unavailable "
                        "— falling back to software\n");
    }
    g_b = &SW_BACKEND;
    g_effective = GR_BACKEND_SOFTWARE;
}

GrBackend gr_backend(void) { return g_effective; }

/* ---- GL render thread (gl_thread.h) ----------------------------------------
 * While g_thr is set, every call into the backend table runs on the render
 * thread: calls without a result are posted as an op + 32-bit argument list
 * (colours and flags fit; floats travel bit-for-bit) and replayed by gr_exec;
 * calls with a result are synchronous. The HD-texture bookkeeping around them
 * stays on the game thread (HD packs disable the render thread anyway). */
enum {
    GOP_SET_SCALE = 1, GOP_SET_TEXTURE_FILTER, GOP_SET_SEMI, GOP_SET_MASK, GOP_SET_TEXWIN,
    GOP_SET_COLMOD, GOP_SET_PRECISE, GOP_SET_PERSPECTIVE, GOP_FILL, GOP_COPY, GOP_FLAT_TRI,
    GOP_GOURAUD_TRI, GOP_TEX_TRI, GOP_SHADED_TEX_TRI, GOP_FLAT_RECT, GOP_TEX_RECT,
    GOP_TEX_RECT_SCALED, GOP_LINE, GOP_SHADED_LINE, GOP_VRAM_WRITE, GOP_VRAM_IN,
    GOP_SET_AREA, GOP_SET_OFFSET, GOP_WIDE_CONFIGURE, GOP_WIDE_VIEW, GOP_WIDE_TARGET,
    GOP_WIDE_NO_TARGET, GOP_WIDE_CLEAR, GOP_WIDE_CLEAR_MARGINS,
    GOP_EXTERNAL = 0x1000   /* gr_post_external: ops owned by other modules (gpu.c) */
};
static int g_thr = 0;
static int g_scale_cache = -1;
static glt_exec_fn g_external_exec = NULL;

#define POSTI(op, ...) do { const int32_t a_[] = { __VA_ARGS__ }; \
                            glt_post((op), a_, (uint32_t)sizeof a_, NULL, 0); } while (0)
static int32_t f2i(float f) { int32_t i; memcpy(&i, &f, 4); return i; }
static float   i2f(int32_t i) { float f; memcpy(&f, &i, 4); return f; }

static void gr_exec(uint32_t op, const void *data, uint32_t len) {
    const int32_t *a = (const int32_t *)data;
    if (op >= GOP_EXTERNAL) { if (g_external_exec) g_external_exec(op, data, len); return; }
    switch (op) {
    case GOP_SET_SCALE:          g_b->set_scale(a[0]); break;
    case GOP_SET_TEXTURE_FILTER: g_b->set_texture_filter(a[0]); break;
    case GOP_SET_SEMI:           g_b->set_semi_transparency(a[0], a[1]); break;
    case GOP_SET_MASK:           g_b->set_mask_bits(a[0], a[1]); break;
    case GOP_SET_TEXWIN:         g_b->set_texture_window((uint32_t)a[0]); break;
    case GOP_SET_COLMOD:         g_b->set_color_modulation(a[0], a[1], a[2], a[3]); break;
    case GOP_SET_PRECISE:
        if (g_b->set_precise_triangle) g_b->set_precise_triangle(a[0], a[1], a[2], a[3], a[4], a[5], a[6]);
        break;
    case GOP_SET_PERSPECTIVE:
        if (g_b->set_perspective_triangle) g_b->set_perspective_triangle(a[0], i2f(a[1]), i2f(a[2]), i2f(a[3]));
        break;
    case GOP_FILL:      g_b->fill_rect(a[0], a[1], a[2], a[3], (uint16_t)a[4]); break;
    case GOP_COPY:      g_b->copy_rect(a[0], a[1], a[2], a[3], a[4], a[5]); break;
    case GOP_FLAT_TRI:  g_b->draw_flat_triangle(a[0], a[1], a[2], a[3], a[4], a[5], (uint16_t)a[6]); break;
    case GOP_GOURAUD_TRI:
        g_b->draw_gouraud_triangle(a[0], a[1], (uint16_t)a[2], a[3], a[4], (uint16_t)a[5],
                                   a[6], a[7], (uint16_t)a[8]);
        break;
    case GOP_TEX_TRI:
        g_b->draw_textured_triangle(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9],
                                    a[10], a[11], (uint16_t)a[12], (uint16_t)a[13], (uint16_t)a[14]);
        break;
    case GOP_SHADED_TEX_TRI:
        g_b->draw_shaded_textured_triangle(a[0], a[1], a[2], a[3], (uint32_t)a[4],
                                           a[5], a[6], a[7], a[8], (uint32_t)a[9],
                                           a[10], a[11], a[12], a[13], (uint32_t)a[14],
                                           (uint16_t)a[15], (uint16_t)a[16], (uint16_t)a[17], a[18]);
        break;
    case GOP_FLAT_RECT: g_b->draw_flat_rect(a[0], a[1], a[2], a[3], (uint16_t)a[4]); break;
    case GOP_TEX_RECT:
        g_b->draw_textured_rect(a[0], a[1], a[2], a[3], a[4], a[5],
                                (uint16_t)a[6], (uint16_t)a[7], (uint16_t)a[8]);
        break;
    case GOP_TEX_RECT_SCALED:
        g_b->draw_textured_rect_scaled(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
                                       (uint16_t)a[8], (uint16_t)a[9], (uint16_t)a[10]);
        break;
    case GOP_LINE:        g_b->draw_line(a[0], a[1], a[2], a[3], (uint16_t)a[4]); break;
    case GOP_SHADED_LINE: g_b->draw_shaded_line(a[0], a[1], (uint16_t)a[2], a[3], a[4], (uint16_t)a[5]); break;
    case GOP_VRAM_WRITE:  g_b->vram_write(a[0], a[1], (uint16_t)a[2]); break;
    case GOP_VRAM_IN:     g_b->vram_transfer_in(a[0], a[1], a[2], a[3], (const uint16_t *)(a + 4)); break;
    case GOP_SET_AREA:    g_b->set_draw_area(a[0], a[1], a[2], a[3]); break;
    case GOP_SET_OFFSET:  g_b->set_draw_offset(a[0], a[1]); break;
    case GOP_WIDE_CONFIGURE: if (g_b->wide_configure) g_b->wide_configure(a[0], a[1]); break;
    case GOP_WIDE_VIEW:   if (g_b->wide_set_view) g_b->wide_set_view(a[0], a[1], a[2], a[3]); break;
    case GOP_WIDE_TARGET: if (g_b->wide_set_target) g_b->wide_set_target(a[0]); break;
    case GOP_WIDE_NO_TARGET: if (g_b->wide_disable_target) g_b->wide_disable_target(); break;
    case GOP_WIDE_CLEAR:  if (g_b->wide_clear) g_b->wide_clear(a[0], a[1], a[2], (uint16_t)a[3]); break;
    case GOP_WIDE_CLEAR_MARGINS:
        if (g_b->wide_clear_margins) g_b->wide_clear_margins(a[0], a[1], a[2], (uint16_t)a[3], a[4]);
        break;
    default: break;
    }
}

/* Route backend calls through the render thread (only for the GL backend,
 * after glt_start()); 0 = direct calls again (after glt_stop()). */
void gr_set_threaded(int on) {
    g_thr = on && glt_active() && g_effective == GR_BACKEND_OPENGL;
    g_scale_cache = -1;
    if (g_thr) glt_set_executor(gr_exec);
}
int gr_threaded(void) { return g_thr; }
/* Other modules' posted ops (>= GOP_EXTERNAL) replay through this executor. */
void gr_set_external_executor(glt_exec_fn fn) { g_external_exec = fn; }

/* Synchronous calls with a result. */
typedef struct { int r; uint32_t *o; int p, dx, dy, dw, dh, hires; } RdArgs;
static void rd_call(void *v) { RdArgs *a = (RdArgs *)v;
    a->r = a->hires ? g_b->render_display_hires(a->o, a->p, a->dx, a->dy, a->dw, a->dh)
                    : g_b->render_display(a->o, a->p, a->dx, a->dy, a->dw, a->dh); }
typedef struct { int x, y; uint16_t r; } VrArgs;
static void vr_call(void *v) { VrArgs *a = (VrArgs *)v; a->r = g_b->vram_read(a->x, a->y); }
typedef struct { int x, y, w, h; uint16_t *d; } VoArgs;
static void vo_call(void *v) { VoArgs *a = (VoArgs *)v; g_b->vram_transfer_out(a->x, a->y, a->w, a->h, a->d); }
typedef struct { int *x1, *y1, *x2, *y2; } DaArgs;
static void da_call(void *v) { DaArgs *a = (DaArgs *)v; g_b->get_draw_area(a->x1, a->y1, a->x2, a->y2); }
static void sc_call(void *v) { *(int *)v = g_b->scale(); }
static void tf_call(void *v) { *(int *)v = g_b->texture_filter(); }
typedef struct { int r; uint32_t *out; int pitch, base_x, disp_y, disp_h; } WdArgs;
static void wd_call(void *v) { WdArgs *a = (WdArgs *)v;
    a->r = g_b->render_wide_display ? g_b->render_wide_display(a->out, a->pitch, a->base_x, a->disp_y, a->disp_h) : 0; }
typedef struct { int r; uint32_t *out; int cap, *ow, *oh, base_x; } WfArgs;
static void wf_call(void *v) { WfArgs *a = (WfArgs *)v;
    a->r = g_b->wide_dump_full ? g_b->wide_dump_full(a->out, a->cap, a->ow, a->oh, a->base_x) : 0; }
static void init_call(void *v) { g_b->init((uint16_t *)v); }

/* ---- Dispatch wrappers (forward to the active backend, or post/sync to the
 * render thread while g_thr) ---- */
void gr_init(uint16_t *vram)                         { if (g_thr) glt_call(init_call, vram); else g_b->init(vram); gpu_hd_textures_set_vram(vram); g_hd_offset_x = g_hd_offset_y = 0; g_hd_texture_window = 0; g_hd_semi = g_hd_perspective = 0; }
void gr_set_scale(int scale)                         { if (g_thr) { POSTI(GOP_SET_SCALE, scale); g_scale_cache = -1; } else g_b->set_scale(scale); }
int  gr_scale(void) {
    if (!g_thr) return g_b->scale();
    if (g_scale_cache < 0) glt_call(sc_call, &g_scale_cache);
    return g_scale_cache;
}
void gr_set_texture_filter(int bilinear)             { if (g_thr) POSTI(GOP_SET_TEXTURE_FILTER, bilinear); else g_b->set_texture_filter(bilinear); }
int  gr_texture_filter(void)                         { int r; if (g_thr) { glt_call(tf_call, &r); return r; } return g_b->texture_filter(); }
void gr_set_semi_transparency(int e, int m)          { if (g_thr) POSTI(GOP_SET_SEMI, e, m); else g_b->set_semi_transparency(e, m); g_hd_semi=e; }
void gr_set_mask_bits(int s, int c)                  { if (g_thr) POSTI(GOP_SET_MASK, s, c); else g_b->set_mask_bits(s, c); }
void gr_set_texture_window(uint32_t raw)             { if (g_thr) POSTI(GOP_SET_TEXWIN, (int32_t)raw); else g_b->set_texture_window(raw); g_hd_texture_window=raw; }
void gr_set_color_modulation(int r, int g, int b, int raw) { if (g_thr) POSTI(GOP_SET_COLMOD, r, g, b, raw); else g_b->set_color_modulation(r, g, b, raw); }
void gr_set_precise_triangle(int enabled, int32_t x0, int32_t y0, int32_t x1, int32_t y1,
                             int32_t x2, int32_t y2) {
    if (g_thr) { POSTI(GOP_SET_PRECISE, enabled, x0, y0, x1, y1, x2, y2); return; }
    if (g_b->set_precise_triangle)
        g_b->set_precise_triangle(enabled, x0, y0, x1, y1, x2, y2);
}
void gr_set_perspective_triangle(int enabled, float q0, float q1, float q2) {
    g_hd_perspective = enabled && q0 > 0 && q1 > 0 && q2 > 0;
    if (g_thr) { POSTI(GOP_SET_PERSPECTIVE, enabled, f2i(q0), f2i(q1), f2i(q2)); return; }
    if (g_b->set_perspective_triangle)
        g_b->set_perspective_triangle(enabled, q0, q1, q2);
}
void gr_fill_rect(int x, int y, int w, int h, uint16_t c)  { if (g_thr) POSTI(GOP_FILL, x, y, w, h, c); else g_b->fill_rect(x, y, w, h, c); gpu_hd_textures_invalidate(x, y, w, h); }
void gr_copy_rect(int sx, int sy, int dx, int dy, int w, int h) { if (g_thr) POSTI(GOP_COPY, sx, sy, dx, dy, w, h); else g_b->copy_rect(sx, sy, dx, dy, w, h); gpu_hd_textures_invalidate(dx, dy, w, h); }
void gr_draw_flat_triangle(int x0, int y0, int x1, int y1, int x2, int y2, uint16_t c) {
    if (g_thr) POSTI(GOP_FLAT_TRI, x0, y0, x1, y1, x2, y2, c);
    else g_b->draw_flat_triangle(x0, y0, x1, y1, x2, y2, c);
    hd_invalidate_triangle(x0, y0, x1, y1, x2, y2);
    g_hd_perspective = 0;
}
void gr_draw_gouraud_triangle(int x0, int y0, uint16_t c0, int x1, int y1, uint16_t c1,
                              int x2, int y2, uint16_t c2) {
    if (g_thr) POSTI(GOP_GOURAUD_TRI, x0, y0, c0, x1, y1, c1, x2, y2, c2);
    else g_b->draw_gouraud_triangle(x0, y0, c0, x1, y1, c1, x2, y2, c2);
    hd_invalidate_triangle(x0, y0, x1, y1, x2, y2);
    g_hd_perspective = 0;
}
void gr_draw_textured_triangle(int x0, int y0, int u0, int v0, int x1, int y1, int u1, int v1,
                               int x2, int y2, int u2, int v2,
                               uint16_t clut_x, uint16_t clut_y, uint16_t texpage) {
    hd_observe_triangle(x0,y0,u0,v0,x1,y1,u1,v1,x2,y2,u2,v2,clut_x,clut_y,texpage);
    if (g_thr) POSTI(GOP_TEX_TRI, x0, y0, u0, v0, x1, y1, u1, v1, x2, y2, u2, v2, clut_x, clut_y, texpage);
    else g_b->draw_textured_triangle(x0, y0, u0, v0, x1, y1, u1, v1, x2, y2, u2, v2,
                                     clut_x, clut_y, texpage);
    hd_invalidate_triangle(x0, y0, x1, y1, x2, y2);
    g_hd_perspective = 0;
}
void gr_draw_shaded_textured_triangle(int x0, int y0, int u0, int v0, uint32_t c0,
                                      int x1, int y1, int u1, int v1, uint32_t c1,
                                      int x2, int y2, int u2, int v2, uint32_t c2,
                                      uint16_t clut_x, uint16_t clut_y,
                                      uint16_t texpage, int raw) {
    hd_observe_triangle(x0,y0,u0,v0,x1,y1,u1,v1,x2,y2,u2,v2,clut_x,clut_y,texpage);
    if (g_thr) POSTI(GOP_SHADED_TEX_TRI, x0, y0, u0, v0, (int32_t)c0, x1, y1, u1, v1, (int32_t)c1,
                     x2, y2, u2, v2, (int32_t)c2, clut_x, clut_y, texpage, raw);
    else g_b->draw_shaded_textured_triangle(x0, y0, u0, v0, c0, x1, y1, u1, v1, c1,
                                            x2, y2, u2, v2, c2, clut_x, clut_y, texpage, raw);
    hd_invalidate_triangle(x0, y0, x1, y1, x2, y2);
    g_hd_perspective = 0;
}
void gr_draw_flat_rect(int x, int y, int w, int h, uint16_t c) { if (g_thr) POSTI(GOP_FLAT_RECT, x, y, w, h, c); else g_b->draw_flat_rect(x, y, w, h, c); if (w > 0 && h > 0) hd_invalidate_draw(x,y,x+w-1,y+h-1); }
void gr_draw_textured_rect(int x, int y, int w, int h, int u, int v,
                           uint16_t clut_x, uint16_t clut_y, uint16_t texpage) {
    hd_observe_rect(u,v,u+w,v+h,clut_x,clut_y,texpage);
    if (g_thr) POSTI(GOP_TEX_RECT, x, y, w, h, u, v, clut_x, clut_y, texpage);
    else g_b->draw_textured_rect(x, y, w, h, u, v, clut_x, clut_y, texpage);
    if (w > 0 && h > 0) hd_invalidate_draw(x,y,x+w-1,y+h-1);
}
void gr_draw_textured_rect_scaled(int x, int y, int w, int h, int u0, int v0, int u1, int v1,
                                  uint16_t clut_x, uint16_t clut_y, uint16_t texpage) {
    hd_observe_rect(u0,v0,u1,v1,clut_x,clut_y,texpage);
    if (g_thr) POSTI(GOP_TEX_RECT_SCALED, x, y, w, h, u0, v0, u1, v1, clut_x, clut_y, texpage);
    else g_b->draw_textured_rect_scaled(x, y, w, h, u0, v0, u1, v1, clut_x, clut_y, texpage);
    if (w > 0 && h > 0) hd_invalidate_draw(x,y,x+w-1,y+h-1);
}
void gr_draw_line(int x0, int y0, int x1, int y1, uint16_t c) { if (g_thr) POSTI(GOP_LINE, x0, y0, x1, y1, c); else g_b->draw_line(x0, y0, x1, y1, c); hd_invalidate_triangle(x0,y0,x1,y1,x1,y1); }
void gr_draw_shaded_line(int x0, int y0, uint16_t c0, int x1, int y1, uint16_t c1) {
    if (g_thr) POSTI(GOP_SHADED_LINE, x0, y0, c0, x1, y1, c1);
    else g_b->draw_shaded_line(x0, y0, c0, x1, y1, c1);
    hd_invalidate_triangle(x0,y0,x1,y1,x1,y1);
}
int gr_render_display(uint32_t *o, int p, int dx, int dy, int dw, int dh) {
    if (g_thr) { RdArgs a = {0, o, p, dx, dy, dw, dh, 0}; glt_call(rd_call, &a); return a.r; }
    return g_b->render_display(o, p, dx, dy, dw, dh);
}
int gr_render_display_hires(uint32_t *o, int p, int dx, int dy, int dw, int dh) {
    if (g_thr) { RdArgs a = {0, o, p, dx, dy, dw, dh, 1}; glt_call(rd_call, &a); return a.r; }
    return g_b->render_display_hires(o, p, dx, dy, dw, dh);
}
void gr_vram_upload_begin(int x, int y, int w, int h) { gpu_hd_textures_invalidate(x,y,w,h); }
void gr_vram_write(int x, int y, uint16_t pixel)     { if (g_thr) POSTI(GOP_VRAM_WRITE, x, y, pixel); else g_b->vram_write(x, y, pixel); gpu_hd_textures_invalidate(x,y,1,1); }
uint16_t gr_vram_read(int x, int y) {
    if (g_thr) { VrArgs a = {x, y, 0}; glt_call(vr_call, &a); return a.r; }
    return g_b->vram_read(x, y);
}
void gr_vram_transfer_in(int x, int y, int w, int h, const uint16_t *d)  {
    if (g_thr) {
        /* The pixels travel with the record: gpu.c may reuse its buffer. */
        const int32_t a[4] = { x, y, w, h };
        uint32_t n = (w > 0 && h > 0) ? (uint32_t)w * (uint32_t)h * 2u : 0u;
        glt_post(GOP_VRAM_IN, a, (uint32_t)sizeof a, d, n);
    } else {
        g_b->vram_transfer_in(x, y, w, h, d);
    }
    gpu_hd_textures_track_upload(x,y,w,h,d);
}
void gr_vram_transfer_out(int x, int y, int w, int h, uint16_t *d)       {
    if (g_thr) { VoArgs a = {x, y, w, h, d}; glt_call(vo_call, &a); return; }
    g_b->vram_transfer_out(x, y, w, h, d);
}
void gr_set_draw_area(int x1, int y1, int x2, int y2){ if (g_thr) POSTI(GOP_SET_AREA, x1, y1, x2, y2); else g_b->set_draw_area(x1, y1, x2, y2); }
void gr_get_draw_area(int *x1, int *y1, int *x2, int *y2) {
    if (g_thr) { DaArgs a = {x1, y1, x2, y2}; glt_call(da_call, &a); return; }
    g_b->get_draw_area(x1, y1, x2, y2);
}
void gr_set_draw_offset(int x, int y)                { if (g_thr) POSTI(GOP_SET_OFFSET, x, y); else g_b->set_draw_offset(x, y); g_hd_offset_x=x; g_hd_offset_y=y; }

/* Native-wide compositor — present only on backends that supply it. */
int  gr_wide_supported(void) { return g_b->render_wide_display != 0; }
void gr_wide_configure(int wide_w, int offset) {
    if (g_thr) { POSTI(GOP_WIDE_CONFIGURE, wide_w, offset); return; }
    if (g_b->wide_configure) g_b->wide_configure(wide_w, offset);
}
void gr_wide_set_view(int enabled, int shift, int pad_left, int pad_right) {
    if (g_thr) { POSTI(GOP_WIDE_VIEW, enabled, shift, pad_left, pad_right); return; }
    if (g_b->wide_set_view) g_b->wide_set_view(enabled, shift, pad_left, pad_right);
}
void gr_wide_set_target(int base_x) {
    if (g_thr) { POSTI(GOP_WIDE_TARGET, base_x); return; }
    if (g_b->wide_set_target) g_b->wide_set_target(base_x);
}
void gr_wide_disable_target(void) {
    if (g_thr) { glt_post(GOP_WIDE_NO_TARGET, NULL, 0, NULL, 0); return; }
    if (g_b->wide_disable_target) g_b->wide_disable_target();
}
void gr_wide_clear(int base_x, int y, int h, uint16_t color) {
    if (g_thr) { POSTI(GOP_WIDE_CLEAR, base_x, y, h, color); return; }
    if (g_b->wide_clear) g_b->wide_clear(base_x, y, h, color);
}
void gr_wide_clear_margins(int base_x, int y, int h, uint16_t color, int sides) {
    if (g_thr) { POSTI(GOP_WIDE_CLEAR_MARGINS, base_x, y, h, color, sides); return; }
    if (g_b->wide_clear_margins)
        g_b->wide_clear_margins(base_x, y, h, color, sides);
}
int gr_render_wide_display(uint32_t *out, int pitch, int base_x,
                           int disp_y, int disp_h) {
    if (g_thr) { WdArgs a = {0, out, pitch, base_x, disp_y, disp_h}; glt_call(wd_call, &a); return a.r; }
    if (g_b->render_wide_display)
        return g_b->render_wide_display(out, pitch, base_x, disp_y, disp_h);
    return 0;
}
int gr_wide_dump_full(uint32_t *out, int cap_pixels, int *ow, int *oh, int base_x) {
    if (g_thr) { WfArgs a = {0, out, cap_pixels, ow, oh, base_x}; glt_call(wf_call, &a); return a.r; }
    if (g_b->wide_dump_full)
        return g_b->wide_dump_full(out, cap_pixels, ow, oh, base_x);
    return 0;
}
