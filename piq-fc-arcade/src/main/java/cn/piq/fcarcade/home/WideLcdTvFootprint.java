package cn.piq.fcarcade.home;

import java.util.List;
import java.util.function.Predicate;

/** Exactly two reserved cells. The second has only the visible half-block collision. */
public final class WideLcdTvFootprint {
    public enum Facing { NORTH,EAST,SOUTH,WEST }
    public record Cell(int part,int x,int y,int z) {}
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private WideLcdTvFootprint() {}
    public static int cellCount(boolean ignored) { return 2; }
    public static Cell cell(Facing facing,int part,boolean ignored) {
        if(part<0||part>1)throw new IllegalArgumentException("Wide LCD part outside two-cell layout");
        return switch(facing) {
            case NORTH -> new Cell(part,part,0,0);
            case EAST -> new Cell(part,0,0,part);
            case SOUTH -> new Cell(part,-part,0,0);
            case WEST -> new Cell(part,0,0,-part);
        };
    }
    public static List<Cell> cells(Facing facing,boolean ignored) { return List.of(cell(facing,0,false),cell(facing,1,false)); }
    public static boolean canPlace(Facing facing,boolean ignored,Predicate<Cell> available) { return cells(facing,false).stream().allMatch(available); }
    public static Bounds selection(Facing facing,int part,boolean ignored) {
        var c=cell(facing,part,false);var b=WideLcdTvLayout.bounds(facing.ordinal());
        return new Bounds(b.minX()-c.x()*16,0,b.minZ()-c.z()*16,b.maxX()-c.x()*16,b.maxY(),b.maxZ()-c.z()*16);
    }
    public static Bounds clipped(Facing facing,int part,boolean ignored) {
        var b=selection(facing,part,false);
        return new Bounds(Math.max(0,b.minX()),0,Math.max(0,b.minZ()),Math.min(16,b.maxX()),b.maxY(),Math.min(16,b.maxZ()));
    }
}
