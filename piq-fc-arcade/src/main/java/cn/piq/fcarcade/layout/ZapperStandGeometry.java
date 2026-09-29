package cn.piq.fcarcade.layout;

import java.util.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Box;

/** Original source scale and pivot. No rescaling/rebuilding of the supplied model. */
public final class ZapperStandGeometry {
    public static final Point CORD=new Point(10.64/16,1.15/16,8.20/16);
    public static final Point PLUG=new Point(7.08/16,.8/16,9.113/16);
    private ZapperStandGeometry(){}
    public static Point rotate(Point p,int turns){return RocketArcadeGeometry.rotate(p,turns);}
    public static Box bounds(int turns){var a=rotate(new Point(3.4999/16,0,6.1999/16),turns);var b=rotate(new Point(12.5001/16,5.274/16,10.3001/16),turns);
        return new Box(Math.min(a.x(),b.x()),0,Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),5.274/16,Math.max(a.z(),b.z()));}
    /** Bounded sagged cord, endpoint exact; only visual, never treats air as support. */
    public static List<Point> cable(Point a,Point b){if(!finite(a)||!finite(b))return List.of();double dx=b.x()-a.x(),dy=b.y()-a.y(),dz=b.z()-a.z();double len=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(len<.001||len>24)return List.of();int count=Math.max(8,Math.min(64,(int)Math.ceil(len*5)));double sag=Math.min(.65,len*.08);var points=new ArrayList<Point>(count+1);
        for(int i=0;i<=count;i++){double t=i/(double)count;points.add(new Point(a.x()+dx*t,a.y()+dy*t-Math.sin(Math.PI*t)*sag,a.z()+dz*t));}return List.copyOf(points);}
    private static boolean finite(Point p){return p!=null&&Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z());}
}
