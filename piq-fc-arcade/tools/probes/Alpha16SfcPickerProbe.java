package cn.piq.sfchome.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** SFC draft/paging/cancellation checks against delivered classes, without a Minecraft runtime. */
public final class Alpha16SfcPickerProbe {
    private static int assertions;
    private static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("SFC packaged UI check "+assertions);}
    private static void origin(Class<?> type,Path jar)throws Exception{
        check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar));
    }
    public static void main(String[] args)throws Exception{
        Path jar=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(SfcCardLibrary.class,SfcCardLibrary.Row.class,SfcCardEditorLayout.class,
                SfcEditorWork.class,SfcUploadTitle.class))origin(type,jar);
        for(int width:new int[]{320,360,480,512,640,800,1024,1280,1920})
            for(int height:new int[]{240,256,278,320,360,480,556,1080}){
                var layout=SfcCardEditorLayout.of(width,height);
                check(layout.supported());check(layout.x()>=0&&layout.y()>=0);
                check(layout.x()+layout.width()<=width&&layout.y()+layout.height()<=height);
                check(layout.rows()>0);
                for(int row=0;row<layout.rows();row++){
                    check(layout.listY()+row*22>=layout.y());
                    check(layout.listY()+row*22+20<=layout.pageY());
                }
                check(layout.pageY()+18<=layout.actionY());
                check(layout.actionY()+20<=layout.statusY());
                check(layout.statusY()+20<=layout.y()+layout.height());
            }
        for(int[] size:new int[][]{{1,1},{319,240},{320,239}})check(!SfcCardEditorLayout.of(size[0],size[1]).supported());
        var library=new SfcCardLibrary("原名称");
        check(!library.titleDirty());library.title(library.title());check(!library.titleDirty());
        var local=new ArrayList<SfcCardLibrary.Row>();
        for(int i=0;i<67;i++)local.add(SfcCardLibrary.Row.local(Path.of("game-"+i+".sfc"),String.format("Game %02d",i),i+1));
        var remote=SfcCardLibrary.Row.server("a".repeat(64),"Server Game",32);
        library.server(List.of(remote),remote.hash());library.local(local);
        check(library.selected().equals(remote));check(library.filtered().size()==68);
        var chosen=local.get(32);library.select(chosen);
        check(library.title().equals(chosen.name()));check(!library.titleDirty());
        library.title("未提交草稿");check(library.titleDirty());
        for(int rows:new int[]{1,2,4,6,9,12}){
            check(library.pages(rows)==(68+rows-1)/rows);
            library.turn(999,rows);check(library.page(rows)==library.pages(rows)-1);
            check(!library.visible(rows).isEmpty()&&library.visible(rows).size()<=rows);
            library.turn(-999,rows);check(library.page(rows)==0);
            check(library.selected().equals(chosen));check(library.title().equals("未提交草稿"));
        }
        library.query("game 32");check(library.filtered().size()==1);check(library.visible(6).getFirst().equals(chosen));
        library.query("missing");check(library.filtered().isEmpty());check(library.visible(6).isEmpty());check(library.pages(6)==1);
        check(library.selected().equals(chosen));library.query("");
        library.local(local.stream().filter(r->!r.equals(chosen)).toList());check(library.selected()==null);
        check(library.title().equals("未提交草稿"));
        var work=new SfcEditorWork();int first=work.begin();check(work.accepts(first));
        int second=work.begin();check(!work.accepts(first));check(work.accepts(second));
        work.close();check(!work.accepts(second));check(!work.accepts(work.begin()));
        var follow=new SfcUploadTitle("a".repeat(64),"draft");
        check(!follow.afterUpload("UPLOAD_READY","a".repeat(64),"old"));
        check(!follow.afterUpload("写入完成","b".repeat(64),"old"));
        check(follow.afterUpload("写入完成","a".repeat(64),"old"));
        check(!follow.afterUpload("写入完成","a".repeat(64),"old"));
        check(!new SfcUploadTitle("hash"," ").afterUpload("写入完成","hash","old"));
        check(!new SfcUploadTitle("hash","same").afterUpload("写入完成","hash","same"));
        System.out.println("{\"ok\":true,\"assertions\":"+assertions
                +",\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false}");
    }
}
