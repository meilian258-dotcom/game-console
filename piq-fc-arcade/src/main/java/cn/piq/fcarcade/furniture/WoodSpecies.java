package cn.piq.fcarcade.furniture;

/** Exact Minecraft 1.21.1 wood families. Recipes deliberately do not use #planks. */
public enum WoodSpecies {
    OAK("oak", "橡木"), SPRUCE("spruce", "云杉木"), BIRCH("birch", "白桦木"),
    JUNGLE("jungle", "丛林木"), ACACIA("acacia", "金合欢木"), DARK_OAK("dark_oak", "深色橡木"),
    MANGROVE("mangrove", "红树木"), CHERRY("cherry", "樱花木"), BAMBOO("bamboo", "竹"),
    CRIMSON("crimson", "绯红木"), WARPED("warped", "诡异木");
    private final String id, chinese;
    WoodSpecies(String id, String chinese) { this.id=id; this.chinese=chinese; }
    public String id() { return id; }
    public String chinese() { return chinese; }
    public String planks() { return "minecraft:" + id + "_planks"; }
}
