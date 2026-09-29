package cn.piq.fcarcade.skin;

/** Layout identity is explicit on the wire; equal file names are not compatibility evidence. */
public enum SkinLayout {
    LEGACY_512(1, 512),
    ROCKET_V1_2048(2, 2048);

    private final int id;
    private final int size;

    SkinLayout(int id, int size) { this.id = id; this.size = size; }
    public int id() { return id; }
    public int size() { return size; }
    public boolean compatible() { return this == ROCKET_V1_2048; }

    public static SkinLayout fromId(int id) {
        for (SkinLayout layout : values()) if (layout.id == id) return layout;
        throw new IllegalArgumentException("未知机身皮肤 UV 布局");
    }

    public static SkinLayout fromDimensions(int width, int height) {
        for (SkinLayout layout : values()) if (width == layout.size && height == layout.size) return layout;
        throw new IllegalArgumentException("皮肤必须使用新版 2048×2048 街机模板；旧版仅保留 512×512 文件");
    }
}
