package com.nokia.mid.ui;

import javax.microedition.lcdui.Canvas;

/** Nokia UI API compatibility surface used by many Series 40/60 games. */
public abstract class FullCanvas extends Canvas {
    public static final int KEY_SOFTKEY1 = -6;
    public static final int KEY_SOFTKEY2 = -7;
    public static final int KEY_SEND = -10;
    public static final int KEY_END = -11;

    protected FullCanvas() {
        setFullScreenMode(true);
    }
}
