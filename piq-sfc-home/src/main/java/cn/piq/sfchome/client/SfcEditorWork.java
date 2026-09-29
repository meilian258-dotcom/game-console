// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

/** Invalidates late completions, including an old request completing after a retry. */
final class SfcEditorWork {
    private int revision;
    private boolean closed;
    int begin(){return ++revision;}
    boolean accepts(int ticket){return !closed&&revision==ticket;}
    void close(){closed=true;revision++;}
}
