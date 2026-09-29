package cn.piq.fcarcade.session;

public record LockstepInputRun(
        int frames,
        int playerOneMask,
        int playerTwoMask,
        int zapperState
) {
    public LockstepInputRun(int frames,int one,int two){this(frames,one,two,ZapperInput.NEUTRAL);}
    public LockstepInputRun {
        ZapperInput.validate(zapperState);
        if (frames <= 0) throw new IllegalArgumentException("持续模拟帧必须大于 0");
        if ((playerOneMask & ~0xFF) != 0 || (playerTwoMask & ~0xFF) != 0) {
            throw new IllegalArgumentException("手柄状态必须是 8 位掩码");
        }
    }
}
