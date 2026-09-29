package cn.piq.fcarcade.client;

import cn.piq.fcarcade.session.ControllerInputTransitions;
import cn.piq.fcarcade.session.LockstepState;
import cn.piq.fcarcade.home.LargeLcdTvLayout;
import cn.piq.fcarcade.home.LargeLcdTvFootprint;
import cn.piq.fcarcade.home.VintageTvLayout;
import cn.piq.fcarcade.layout.LargeLcdPresentation;
import java.util.UUID;
import java.util.List;
import cn.piq.fcarcade.registry.CreativeTabCatalog;
import cn.piq.fcarcade.home.CartridgeComputerBinding;
import cn.piq.fcarcade.home.CartridgeComputerLayout;
import cn.piq.fcarcade.home.CartridgeEditBinding;
import cn.piq.fcarcade.home.TvRemotePolicy;

/** Compiled against and executed with the final JAR only; no Minecraft runtime. */
public final class Alpha13PackagedProbe {
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
            require(near(small.width(),10.2/16)&&near(small.height(),7.65/16)&&near(small.aspectRatio(),4D/3),"vintage complete 4:3 image");
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
        require(near(vintage.lowerMinX().x(),4.35/16)&&near(vintage.lowerMinX().y(),2.0/16)&&near(vintage.lowerMinX().z(),3.35/16-.0015),"vintage mirrored front-screen placement");
        for(int c=0;c<3;c++){
            var socket=LargeLcdTvLayout.socket(0,c);require(near(socket.x(),(5D+3*c)/16)&&near(socket.y(),4D/16)&&near(socket.z(),8.23/16),"large yellow white red RCA anchors");
            socket=VintageTvLayout.socket(0,c);require(near(socket.x(),(9.5-2*c)/16)&&near(socket.y(),3.1/16)&&near(socket.z(),14.04/16),"vintage yellow white red RCA anchors");
        }
        require(near(LargeLcdTvLayout.bounds(0).minX(),-8)&&near(LargeLcdTvLayout.bounds(0).maxX(),24),"large width two blocks around center");
        require(near(VintageTvLayout.bounds(0).minX(),.2)&&near(VintageTvLayout.bounds(0).maxX(),15.8),"vintage stays one cell");
        var expected=List.of("famicom_console","retro_tv","fc_cartridge","av_cable","subor_console",
                "wide_lcd_tv","large_lcd_tv","vintage_tv","dual_cabinet","fc_cartridge_board","fc_cartridge_shell","lcd_tv","legacy_fc_arcade","cartridge_computer","tv_remote");
        var optional=List.of("waterframes_fc_arcade","waterframes_tv_fc_arcade","waterframes_tv_box_fc_arcade","waterframes_panel_fc_arcade");
        require(CreativeTabCatalog.itemPaths(false).equals(expected),"ordinary creative catalog exact visible items/order");
        var withOptional=CreativeTabCatalog.itemPaths(true);
        require(withOptional.size()==expected.size()+optional.size()&&withOptional.subList(0,expected.size()).equals(expected)
                &&withOptional.subList(expected.size(),withOptional.size()).equals(optional),"WaterFrames additions remain independent");
        for(boolean compat:new boolean[]{false,true})for(String retired:List.of("fc_arcade","stream_fc_arcade","deluxe_fc_arcade","deluxe_stream_fc_arcade","leaderboard_panel"))
            require(!CreativeTabCatalog.itemPaths(compat).contains(retired),"retired display item hidden in both environments");
        UUID stationId=UUID.randomUUID();
        var station=new CartridgeComputerBinding(stationId,"minecraft:overworld",2,64,3);
        require(station.permits(stationId,"minecraft:overworld",true,true,true,true,true,25),"station boundary exactly five blocks");
        require(station.permits(stationId,"minecraft:overworld",true,true,true,true,true,0),"station zero distance allowed");
        require(!station.permits(stationId,"minecraft:the_nether",true,true,true,true,true,0),"dimension change invalidates");
        require(!station.permits(UUID.randomUUID(),"minecraft:overworld",true,true,true,true,true,0),"computer UUID replacement invalidates");
        for(double distance:new double[]{-.1,25.000001,Double.NaN,Double.POSITIVE_INFINITY})
            require(!station.permits(stationId,"minecraft:overworld",true,true,true,true,true,distance),"invalid/outside station distance");
        for(int flag=0;flag<5;flag++)
            require(!station.permits(stationId,"minecraft:overworld",flag!=0,flag!=1,flag!=2,flag!=3,flag!=4,1),"each workstation authority condition required");
        for(int players:new int[]{-1,0,1,2,3,Integer.MAX_VALUE})for(boolean exists:new boolean[]{true,false})for(boolean busy:new boolean[]{true,false})
            require(CartridgeComputerBinding.permitsPlayersSetting(players,exists,busy)==((players==1||players==2)&&exists&&!busy),"exact shared ROM player setting policy");
        UUID cardId=UUID.randomUUID();var cardBinding=new CartridgeEditBinding(UUID.randomUUID(),cardId,0,2);
        require(cardBinding.permits(cardBinding,cardId,2,true,true,true),"same physical card");
        require(!cardBinding.permits(cardBinding,cardId,2,false,true,true),"copied UUID cannot substitute physical card");
        require(!cardBinding.permits(cardBinding,cardId,3,true,true,true),"changing selected slot revokes");
        require(!cardBinding.permits(new CartridgeEditBinding(UUID.randomUUID(),cardId,0,2),cardId,2,true,true,true),"old token cannot target new edit session");
        for(int turn=0;turn<4;turn++){
            var parts=CartridgeComputerLayout.parts(turn);require(parts.size()==6,"computer six physical parts");
            for(var p:parts)require(p.minX()>=0&&p.minY()>=0&&p.minZ()>=0&&p.maxX()<=16&&p.maxY()<=16&&p.maxZ()<=16,"computer stays in one physical cell");
            require(CartridgeComputerLayout.parts(turn)==CartridgeComputerLayout.parts(turn+4),"computer shape cache wraps facings");
        }

        require(near(TvRemotePolicy.RANGE,8)&&TvRemotePolicy.COOLDOWN_TICKS==8&&TvRemotePolicy.HOLD_TICKS==72000,"packaged remote reach cooldown and hold latch");
        for(int flags=0;flags<128;flags++)
            require(TvRemotePolicy.canActivate((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,
                    (flags&16)!=0,(flags&32)!=0,(flags&64)!=0)==(flags==27),"all seven activation prerequisites");
        for(double distance:new double[]{0,1,64,64.00001,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            require(TvRemotePolicy.inRange(distance)==(Double.isFinite(distance)&&distance>=0&&distance<=64),"finite exact eight-block remote limit");
        require(CrtScanlinePattern.SOURCE_ROWS==240,"one shading strip per original NES row");
        require(near(CrtScanlinePattern.strength(240),0)&&near(CrtScanlinePattern.strength(480),.28),"sampling fade endpoints");
        require(near(CrtScanlinePattern.strength(360),.14),"smooth fade midpoint");
        for(double height:new double[]{-1,0,120,239,240,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            require(CrtScanlinePattern.strength(height)==0,"invalid or undersampled view stays unshaded");
        double previousStrength=0;
        for(int height=240;height<=500;height++){
            double strength=CrtScanlinePattern.strength(height);
            require(strength>=previousStrength&&strength<=.28,"fade bounded and monotonic");previousStrength=strength;
        }
        for(int row=0;row<240;row++){
            require(CrtScanlinePattern.top(row)>=0&&CrtScanlinePattern.bottom(row)<=1
                    &&CrtScanlinePattern.top(row)<CrtScanlinePattern.bottom(row),"strip UV remains positive and bounded");
            require(CrtScanlinePattern.brightness(row,240)==255,"undersampled rows remain original brightness");
            require(CrtScanlinePattern.brightness(row,480)==((row&1)==0?255:184),"alternating bounded grey shading");
            if(row>0)require(CrtScanlinePattern.top(row)==CrtScanlinePattern.bottom(row-1),"adjacent strips no overlap or gap");
        }
        require(CrtScanlinePattern.top(0)==0&&CrtScanlinePattern.bottom(239)==1,"strip union is full frame");
        for(int invalid:new int[]{-1,240,Integer.MIN_VALUE,Integer.MAX_VALUE}){
            boolean topRejected=false,bottomRejected=false,shadeRejected=false;
            try{CrtScanlinePattern.top(invalid);}catch(IllegalArgumentException expectedError){topRejected=true;}
            try{CrtScanlinePattern.bottom(invalid);}catch(IllegalArgumentException expectedError){bottomRejected=true;}
            try{CrtScanlinePattern.brightness(invalid,480);}catch(IllegalArgumentException expectedError){shadeRejected=true;}
            require(topRejected&&bottomRejected&&shadeRejected,"invalid row rejected consistently");
        }
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"source\":\"final JAR classes\"}");
    }
}
