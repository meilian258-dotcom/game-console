// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.computer;

import java.util.Arrays;
import java.util.List;

/** Pure, persisted slot layout. Ordinals are save-format IDs; append, never reorder. */
public final class Assembly {
    private Assembly() {}
    public enum Part {
        MOTHERBOARD("motherboard", "主板", 0), CPU("cpu", "处理器", 1),
        COOLER("cooler", "散热器", 3), RAM("ram", "内存 1", 1),
        GPU("gpu", "显卡", 1), HDD("hdd", "硬盘", 0), PSU("psu", "电源", 0),
        RAM_2("ram_2", "内存 2", 1);
        public final String model, label;
        public final int requires;
        Part(String model, String label, int requires) { this.model=model;this.label=label;this.requires=requires; }
        public int bit() {return 1<<ordinal();}
        public String item() {return this==RAM_2?"ram":model;}
    }
    public static int clean(int mask) { return mask & 255; }
    public static boolean has(int mask, Part part) {return (mask&part.bit())!=0;}
    public static boolean installable(int mask, Part part) {return !has(mask,part)&&(mask&part.requires)==part.requires;}
    public static boolean removable(int mask, Part part) {
        return has(mask,part)&&Arrays.stream(Part.values()).noneMatch(p->has(mask,p)&&(p.requires&part.bit())!=0);
    }
    public static List<Part> missing(int mask) {
        return Arrays.stream(Part.values()).filter(p->p!=Part.RAM_2&&!has(mask,p)&&!(p==Part.RAM&&has(mask,Part.RAM_2))).toList();
    }
    public static boolean ready(int mask) {return missing(mask).isEmpty();}
}
