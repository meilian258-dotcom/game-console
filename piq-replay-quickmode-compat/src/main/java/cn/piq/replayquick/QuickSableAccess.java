package cn.piq.replayquick;

/** Per-sender state, never shared between recordings. */
public interface QuickSableAccess {
    QuickSableState piq$sableState();
}
