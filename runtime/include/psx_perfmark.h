/* psxrecomp's perfmark phases (runtime/src/psx_perfmark.c; the timing header is perfmark.h).
 * The hooks exist only where PSX_PERFMARK is defined (the game runtime target, which also builds psx_perfmark.c);
 * in the tests and psx-beetle, which build some of the same files without it, they compile to nothing. */
#ifndef PSX_PERFMARK_H
#define PSX_PERFMARK_H

enum {
    PSX_PM_SPU = 0,     /* spu_render */
    PSX_PM_PRESENT = 1, /* gl_swap_with_osd (OSD, swap; a vsync swap blocks here) */
    PSX_PM_PACE = 2,    /* frame_pacer_wait */
    PSX_PM_INTERP = 3,  /* sampled: dirty-RAM interpreter */
    PSX_PM_NATIVE = 4,  /* sampled: native overlay shard */
    PSX_PM_STATIC = 5,  /* sampled: compiled game EXE / BIOS */
    PSX_PM_GP0 = 6      /* sampled: GPU GP0 commands (raster, batching, VRAM transfers) */
};

#ifdef PSX_PERFMARK
#include "perfmark.h"
#define PSX_PM_BEGIN(id) PM_BEGIN(id)
#define PSX_PM_END(id) PM_END(id)
#define PSX_PM_FRAME() pm_frame()
#ifdef __cplusplus
extern "C" {
#endif
/* Opens ./perfmark.csv if ./perfmark exists and starts the phase sampler; otherwise does nothing. */
void psx_perfmark_start(const char* game);
#ifdef __cplusplus
}
#endif
#else
#define PSX_PM_BEGIN(id) ((void)0)
#define PSX_PM_END(id) ((void)0)
#define PSX_PM_FRAME() ((void)0)
#endif

#endif
