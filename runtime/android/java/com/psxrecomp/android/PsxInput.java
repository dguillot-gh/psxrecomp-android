package com.psxrecomp.android;

/**
 * JNI bridge from the on-screen controls to the native runtime (libmain.so,
 * runtime/src/main.cpp). Shared by every game's Android app.
 */
public final class PsxInput {
    private PsxInput() {}

    /* PS1 pad button bits as they travel on the SIO wire (1 = held here; the
     * runtime stores them active-low). */
    public static final int SELECT = 0x0001;
    public static final int L3 = 0x0002;
    public static final int R3 = 0x0004;
    public static final int START = 0x0008;
    public static final int UP = 0x0010;
    public static final int RIGHT = 0x0020;
    public static final int DOWN = 0x0040;
    public static final int LEFT = 0x0080;
    public static final int L2 = 0x0100;
    public static final int R2 = 0x0200;
    public static final int L1 = 0x0400;
    public static final int R1 = 0x0800;
    public static final int TRIANGLE = 0x1000;
    public static final int CIRCLE = 0x2000;
    public static final int CROSS = 0x4000;
    public static final int SQUARE = 0x8000;

    /** Every button currently held on the touch pad (port 1). */
    public static native void nativeSetButtons(int held);

    /** stick 0 = left, 1 = right; x/y are PS1 analog bytes, 0x80 = centred. */
    public static native void nativeSetStick(int stick, int x, int y);

    /** Discs in this game's set (1 for single-disc games; 0 before the game has started). */
    public static native int nativeDiscCount();

    /** The disc in the drive now, 1-based. */
    public static native int nativeCurrentDisc();

    /** Swap to disc (1-based) between frames: tray open, new disc, tray close. */
    public static native void nativeRequestDiscSwap(int disc);

    /** Number of save-state slots (0-based slot numbers below). */
    public static native int nativeStateSlots();

    /** When the slot was saved (seconds since 1970), or 0 if it is empty. */
    public static native long nativeStateSlotTime(int slot);

    /** Save (load = false) or load a state at the next safe point. */
    public static native void nativeRequestState(int slot, boolean load);
}
