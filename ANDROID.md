# psxrecomp-android

Private copy of [mstan/psxrecomp](https://github.com/mstan/psxrecomp) with an Android layer, used by
[psx-android](https://github.com/dguillot-gh/psx-android) to build PS1 games as native Android apps.

- **Branch `android`** = upstream master + our work: the Android app (`runtime/android/`: start menu, touch pad,
  side menu with save states / disc change / display options, trackpad for mouse games), Android paths in
  `runtime/src/main.cpp` and `runtime/runtime.cmake`, OpenGL ES support in the GL renderer, overlay
  pre-compile for arm64 (`tools/compile_overlays.py --target-os android`), and speed work in the runtime.
- Tag `android-pre-upstream`: our branch before it moved to upstream master (2026-10-07).
- Updating from upstream: `git fetch upstream` then merge `upstream/master` into `android` (see
  psx-android's CONTEXT.md, 2026-10-07, for what the last merge kept and dropped).

License: PolyForm Noncommercial 1.0.0 (see LICENSE), same as upstream. Personal, non-commercial use only.
