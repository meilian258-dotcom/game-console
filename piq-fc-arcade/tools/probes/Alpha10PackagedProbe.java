package cn.piq.fcarcade.client;

import cn.piq.fcarcade.session.ControllerInputTransitions;
import cn.piq.fcarcade.session.LockstepState;
import cn.piq.fcarcade.home.LargeLcdTvLayout;
import cn.piq.fcarcade.home.LargeLcdTvFootprint;
import cn.piq.fcarcade.home.VintageTvLayout;
import cn.piq.fcarcade.layout.LargeLcdPresentation;
import java.util.UUID;

/** Compiled against and executed with the final JAR only; no Minecraft runtime. */
public final class Alpha10PackagedProbe {
    private static int checks;
    private static void require(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-10;}
    public static void main(String[] args) {
        var q=new ControllerInputTransitions();
        require(q.offer(1)&&q.offer(0),"short tap enqueue");require(q.nextFrame()==1,"first frame must retain down");require(q.nextFrame()==0,"second frame must release");
        q.offer(1);q.offer(2);q.offer(0);require(q.nextFrame()==1&&q.nextFrame()==2&&q.nextFrame()==0,"absolute masks must not OR merge");
        for(int i=0;i<33;i++)q.offer(i%2==0?1:0);
        require(q.nextFrame()==0&&q.pendingCount()==0,"overflow neutral");require(!q.offer(1)&&q.offer(0)&&q.offer(2)&&q.nextFrame()==2,"overflow recovers only after real release");
        q.clear();require(q.nextFrame()==0,"lifecycle input flush");
        var lock=new LockstepState();var p1=UUID.randomUUID();var p2=UUID.randomUUID();
        require(lock.acceptInput(p1,0,0,0,1)&&lock.acceptInput(p1,0,0,1,0),"server fast tap accepted");
        require(lock.advanceFrame().playerOneMask()==1&&lock.advanceFrame().playerOneMask()==0,"server broadcasts down then up");
        lock.acceptInput(p2,0,1,0,2);lock.acceptInput(p1,0,0,2,1);
        require(lock.acceptInput(p1,0,0,3,0,true,false),"force release accepted");var frame=lock.advanceFrame();require(frame.playerOneMask()==0&&frame.playerTwoMask()==2,"release one does not flush other port");
        require(!lock.acceptInput(p2,0,1,0,0,true,false)&&lock.advanceFrame().playerTwoMask()==2,"stale release cannot clear newer held state");
        var view=new ControllerFramePresentation();long generation=view.revision();view.complete(generation,1,0);view.complete(generation,0,0);view.present();
        require(view.presented(0)==1,"completed pulse reaches at least one render");view.present();require(view.presented(0)==0,"pulse is drained, never sticky");
        view.clear();view.complete(generation,255,255);view.present();require(view.presented(0)==0&&view.presented(1)==0,"stale completed frame cannot revive released presentation");
        var pose=ControllerPoseLayout.first(true,true,0,0);require(near(pose.pitch(),-60)&&near(pose.y(),-.62)&&near(pose.z(),-1.46),"packaged first rig");
        for(boolean right:new boolean[]{true,false})for(double size:new double[]{1,1.18,1.3}) {
            var a=ControllerPoseLayout.firstArm(right,size);require(near(a.pitch(),30)&&near(a.roll(),right?60:-60)&&near(a.scale(),.82*size),"packaged local wrists");
        }
        for(int t=0;t<4;t++) {
            var screen=LargeLcdTvLayout.screen(t);var small=VintageTvLayout.screen(t);var fit=LargeLcdPresentation.frame(t);
            require(near(screen.width(),30D/16)&&near(screen.height(),16.875/16)&&near(screen.aspectRatio(),16D/9),"physical 16:9 large screen every facing");
            require(near(fit.width(),22.5/16)&&near(fit.height(),16.875/16)&&near(fit.aspectRatio(),4D/3),"complete 4:3 image with side bars");
            require(near(small.width(),10D/16)&&near(small.height(),7.5/16)&&near(small.aspectRatio(),4D/3),"vintage complete 4:3 image");
            var cells=LargeLcdTvFootprint.cells(LargeLcdTvFootprint.Facing.values()[t],false);require(cells.size()==6,"large centered six-cell reservation");
            double volume=0;
            for(var cell:cells){var b=LargeLcdTvFootprint.clipped(LargeLcdTvFootprint.Facing.values()[t],cell.part(),false);
                require(b.minX()>=0&&b.minY()>=0&&b.minZ()>=0&&b.maxX()<=16&&b.maxY()<=16&&b.maxZ()<=16,"proxy collision remains in local cell");
                volume+=(b.maxX()-b.minX())*(b.maxY()-b.minY())*(b.maxZ()-b.minZ())/4096;
            }
            require(near(volume,.975),"six clipped cells exactly cover large bounds without duplicate physical volume");
            for(int c=0;c<3;c++){require(LargeLcdTvLayout.socket(t,c)!=null&&VintageTvLayout.socket(t,c)!=null,"three RCA channels each facing");}
        }
        var large=LargeLcdTvLayout.screen(0);var vintage=VintageTvLayout.screen(0);var picture=LargeLcdPresentation.frame(0);
        require(near(large.lowerMinX().x(),-7D/16)&&near(large.lowerMinX().y(),1.5/16)&&near(large.lowerMinX().z(),6D/16-.0015),"large actual screen lower corner");
        require(near(picture.lowerMinX().x(),(-7+3.75)/16)&&near(picture.lowerMaxX().x(),(23-3.75)/16),"large symmetric pillar bars");
        require(near(vintage.lowerMinX().x(),4.6/16)&&near(vintage.lowerMinX().y(),2.1/16)&&near(vintage.lowerMinX().z(),2.28/16-.0015),"vintage mirrored front-screen placement");
        for(int c=0;c<3;c++){
            var socket=LargeLcdTvLayout.socket(0,c);require(near(socket.x(),(5D+3*c)/16)&&near(socket.y(),4D/16)&&near(socket.z(),8.23/16),"large yellow white red RCA anchors");
            socket=VintageTvLayout.socket(0,c);require(near(socket.x(),(9.5-2*c)/16)&&near(socket.y(),3.1/16)&&near(socket.z(),14.04/16),"vintage yellow white red RCA anchors");
        }
        require(near(LargeLcdTvLayout.bounds(0).minX(),-8)&&near(LargeLcdTvLayout.bounds(0).maxX(),24),"large width two blocks around center");
        require(near(VintageTvLayout.bounds(0).minX(),.2)&&near(VintageTvLayout.bounds(0).maxX(),15.8),"vintage stays one cell");
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"source\":\"final JAR classes\"}");
    }
}
