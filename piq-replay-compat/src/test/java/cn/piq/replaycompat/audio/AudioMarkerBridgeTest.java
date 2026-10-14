package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioFrame;
import cn.piq.replaycompat.replay.AudioMarkerBridge;
import cn.piq.replaycompat.replay.TrackCutPlan;
import com.replaymod.replaystudio.data.Marker;
import java.io.IOException;
import java.util.*;
import static cn.piq.replaycompat.audio.AudioTestSupport.*;

/** Actual marker lifecycle with an audio-only source: independent of the screen track context. */
public final class AudioMarkerBridgeTest {
    public static void main(String[] args)throws Exception{
        Archive source=new Archive(100);var writer=new AudioTrackFiles.Writer(source.file);
        writer.append(List.of(new AudioFrame("terminal-0",0,48000,2,pcm(4800,2,3210))));writer.finish(1,0,"complete");
        source.markers=Set.of(marker(50,TrackCutPlan.SPLIT));
        Archive first=new Archive(49),second=new Archive(50);
        AudioMarkerBridge.begin();AudioMarkerBridge.input(source.file);
        AudioMarkerBridge.beforeSave(source.file);AudioMarkerBridge.beforeSave(first.file);AudioMarkerBridge.beforeSave(second.file);AudioMarkerBridge.end();
        check(first.entries.containsKey(AudioTrackFiles.INDEX)&&second.entries.containsKey(AudioTrackFiles.INDEX),"audio-only split creates both output tracks");
        var fi=AudioTrackFiles.readIndex(first.file);var si=AudioTrackFiles.readIndex(second.file);
        var ff=AudioTrackFiles.readChunk(first.file,fi.refs().get(0));var sf=AudioTrackFiles.readChunk(second.file,si.refs().get(0));
        check(ff.get(0).sampleFrames()==2400 && sf.get(0).sampleFrames()==2400,"split halves preserve whole PCM samples");
        check(sf.get(0).startMicros()==0 && sf.get(0).sample(0,0)==3210,"actual second save remaps sourceclock");
        check(source.entries.keySet().stream().noneMatch(name->name.startsWith("piq-screen/")),"source genuinely has no screen context");
        Archive ignored=new Archive(100);AudioMarkerBridge.beforeSave(ignored.file);check(ignored.entries.isEmpty(),"end clears threadlocal marker state");
        AudioMarkerBridge.begin();AudioMarkerBridge.input(source.file);AudioMarkerBridge.closing(source.file);AudioMarkerBridge.beforeSave(ignored.file);
        check(ignored.entries.isEmpty(),"source close invalidates marker context");AudioMarkerBridge.end();
        Archive malformed=new Archive(100);malformed.entries.putAll(source.entries);malformed.markers=Set.of(marker(20,TrackCutPlan.END_CUT));
        Archive failed=new Archive(100);AudioMarkerBridge.begin();AudioMarkerBridge.input(malformed.file);AudioMarkerBridge.beforeSave(failed.file);AudioMarkerBridge.end();
        check(failed.entries.containsKey(AudioMarkerBridge.WARNING_ENTRY),"bad cut gets explicit audio warning");
        boolean rejected=false;try{AudioTrackFiles.readIndex(failed.file);}catch(IOException expected){rejected=true;}
        check(rejected,"playback refuses marker-failed track");
        Archive empty=new Archive(100),output=new Archive(100);AudioMarkerBridge.begin();AudioMarkerBridge.input(empty.file);AudioMarkerBridge.beforeSave(output.file);AudioMarkerBridge.end();
        check(output.entries.isEmpty(),"unpatched old recording creates no artificial audio");
        System.out.println("AudioMarkerBridgeTest: "+checks+" checks passed");
    }
    private static Marker marker(int time,String name){Marker marker=new Marker();marker.setTime(time);marker.setName(name);return marker;}
}
