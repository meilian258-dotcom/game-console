package cn.piq.fcarcade.home;

import java.util.UUID;

/** Pure physical-data boundary: a board cannot carry a cover, a shell cannot carry a ROM/title. */
public final class CartridgeParts {
    public static final int VARIANT_COUNT = 3;
    private CartridgeParts() {}
    private static void check(UUID id, long revision, int variant, boolean whole) {
        if (id == null || id.equals(new UUID(0, 0)) || revision < 0
                || variant < (whole ? -1 : 0) || variant >= VARIANT_COUNT)
            throw new IllegalArgumentException("卡带身份或电路板外观无效");
    }
    public record Whole(UUID id, String rom, String title, String cover, int variant, long revision, int saveMode, boolean saved) {
        public Whole(UUID id,String rom,String title,String cover,int variant,long revision){this(id,rom,title,cover,variant,revision,-1,false);}
        public Whole {
            check(id, revision, variant, true);
            checkSaveMode(saveMode);
            rom = CartridgeLimits.hashOrEmpty(rom); cover = CartridgeLimits.hashOrEmpty(cover);
            title = CartridgeLimits.cleanTitle(title);
        }
    }
    public record Board(UUID id, String rom, String internalTitle, int variant, long revision, int saveMode, boolean saved) {
        public Board(UUID id,String rom,String internalTitle,int variant,long revision){this(id,rom,internalTitle,variant,revision,-1,false);}
        public Board {
            checkSaveMode(saveMode);
            check(id, revision, variant, false); rom = CartridgeLimits.hashOrEmpty(rom);
            internalTitle = CartridgeLimits.cleanTitle(internalTitle);
        }
    }
    public record Shell(UUID id, String cover) {
        public Shell { check(id, 0, 0, false); cover = CartridgeLimits.hashOrEmpty(cover); }
    }
    public record Split(Board board, Shell shell) {}
    private static void checkSaveMode(int mode){if(mode < -1 || mode > 2)throw new IllegalArgumentException("卡带存档方式无效");}
    public static Split split(Whole whole, int randomVariant, UUID shellId) {
        if (randomVariant < 0 || randomVariant >= VARIANT_COUNT) throw new IllegalArgumentException("电路板外观无效");
        int variant = whole.variant() < 0 ? randomVariant : whole.variant();
        return new Split(new Board(whole.id(), whole.rom(), whole.title(), variant, Math.addExact(whole.revision(), 1),whole.saveMode(),whole.saved()),
                new Shell(shellId, whole.cover()));
    }
    public static Whole combine(Board board, Shell shell) {
        // A different shell is deliberately allowed: ROM follows the board, cover follows the shell.
        return new Whole(board.id(), board.rom(), board.internalTitle(), shell.cover(), board.variant(), Math.addExact(board.revision(), 1),board.saveMode(),board.saved());
    }
}
