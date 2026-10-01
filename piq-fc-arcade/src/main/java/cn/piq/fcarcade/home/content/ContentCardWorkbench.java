package cn.piq.fcarcade.home.content;

import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Pure writer rules shared by UI/server. Selection is not a write transaction. */
public final class ContentCardWorkbench {
    public static final int PAGE_SIZE=8;
    public record Page(int index,int total,List<ContentCardStore.Entry> entries){public Page{entries=List.copyOf(entries);}}
    public static Page page(List<ContentCardStore.Entry> catalog,String query,int page){
        if(query==null||query.length()>64||query.chars().anyMatch(Character::isISOControl)||page<0||page>31)throw new IllegalArgumentException("搜索或页码无效");
        var key=query.strip().toLowerCase(Locale.ROOT);
        var found=catalog.stream().filter(e->e.name().toLowerCase(Locale.ROOT).contains(key)).toList();
        int index=Math.min(page,Math.max(0,(found.size()-1)/PAGE_SIZE)),from=index*PAGE_SIZE;
        return new Page(index,found.size(),found.subList(from,Math.min(from+PAGE_SIZE,found.size())));
    }
    public static String title(String title){
        if(title==null||title.length()>128||title.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("名称最多128字且不能包含控制字符");
        if(title.isBlank())throw new IllegalArgumentException("请填写卡带名称");
        return title.strip();
    }
    public static String uploadTitle(byte[] data,String fallback){
        if(data.length==0)return title(fallback);
        if(data.length>512)throw new IllegalArgumentException("名称过长");
        try{return title(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString());}
        catch(CharacterCodingException invalid){throw new IllegalArgumentException("名称编码无效");}
    }
    private ContentCardWorkbench(){}
}
