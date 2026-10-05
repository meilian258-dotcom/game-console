package cn.piq.fcarcade.home.content;

import java.util.List;

/** Shared bounded diagnostics, independent of a core's format and normalized content identity. */
public record ContentScanReport<T>(List<T> entries,List<ContentCardStore.Failure> failures,List<ContentCardStore.Failure> warnings){
    public ContentScanReport {
        entries=List.copyOf(entries);failures=List.copyOf(failures);warnings=List.copyOf(warnings);
        if(entries.size()>256||failures.size()>512||warnings.size()>512)throw new IllegalArgumentException("Scan report exceeds budget");
    }
    public String summary(String source){return source+"："+entries.size()+" 项可用"+(failures.isEmpty()?"":"，"+failures.size()+" 项被拒绝（状态栏查看原因）")+(warnings.isEmpty()?"":"，"+warnings.size()+" 项名称警告（游戏仍可用）");}
    public byte[] diagnostics(){
        var text=new StringBuilder();
        for(var failure:java.util.stream.Stream.concat(failures.stream(),warnings.stream()).toList()){
            String line=failure+"\n";
            if((text.toString()+line).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>7*1024){text.append("其余失败项请查看服务器日志；没有自动删除或修改原文件。");break;}
            text.append(line);
        }
        return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
