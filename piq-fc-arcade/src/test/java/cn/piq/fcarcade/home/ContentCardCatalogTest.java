package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardCatalogTest {
    private static final String HASH="a".repeat(64),OTHER="b".repeat(64);
    private ContentCardStore.Entry server(String hash,String name){return new ContentCardStore.Entry(hash,hash+".md",1024,name);}
    private ContentCardCatalog.Choice local(String hash,String name){return new ContentCardCatalog.Choice(name,1024,hash,Path.of("local",name));}
    @Test void sameContentMergesAndUsesServerWithoutUploadingAgain(){
        var rows=ContentCardCatalog.page(List.of(server(HASH,"服务器原名.md")),List.of(local(HASH,"本地别名.md")),"",true,true);
        assertEquals(1,rows.size());var row=rows.getFirst();assertEquals("本地 / 服务器",row.source());
        assertEquals("服务器原名.md",row.name());assertNull(row.path());assertEquals(HASH,row.hash());
    }
    @Test void missingServerUsePermissionPreservesExplicitLocalUploadChoice(){
        var original=local(HASH,"local.md");var row=ContentCardCatalog.page(List.of(server(HASH,"shared.md")),List.of(original),"",true,false).getFirst();
        assertEquals(original.path(),row.path());assertTrue(row.serverAvailable());assertTrue(row.localAvailable());
    }
    @Test void sameNameDifferentContentsNeverMerge(){
        var rows=ContentCardCatalog.page(List.of(server(HASH,"same.md")),List.of(local(OTHER,"same.md")),"",true,true);
        assertEquals(2,rows.size());assertNotEquals(rows.get(0).hash(),rows.get(1).hash());
    }
    @Test void legacyServerNameUsesMatchingLocalDisplayWithoutChangingPhysicalIdentity(){
        var old=new ContentCardStore.Entry(HASH,HASH+".md",1024);
        var row=ContentCardCatalog.page(List.of(old),List.of(local(HASH,"可识别的原名.md")),"",true,true).getFirst();
        assertEquals("可识别的原名.md",row.name());assertEquals(HASH,row.hash());assertEquals(HASH+".md",old.name());assertNull(row.path());
    }
    @Test void localOnlySearchAndLaterServerPagesKeepCorrectSources(){
        var locals=List.of(local(HASH,"中文名字.md"),local(OTHER,"Other.md"));
        var searched=ContentCardCatalog.page(List.of(),locals,"中文",true,false);assertEquals(1,searched.size());assertEquals(HASH,searched.getFirst().hash());
        var later=ContentCardCatalog.page(List.of(server(OTHER,"Other.md")),locals,"",false,true);
        assertEquals(1,later.size());assertEquals("本地 / 服务器",later.getFirst().source());assertNull(later.getFirst().path());
    }
    @Test void duplicateDiskNamesDoNotProduceExtraCatalogPages(){
        var first=server(HASH,"中文名称.md");var alias=new ContentCardStore.Entry(HASH,"another.md",1024,"中文别名.md");
        var page=ContentCardWorkbench.page(List.of(first,alias),"",0);assertEquals(1,page.total());
        assertEquals(1,ContentCardWorkbench.page(List.of(first,alias),"别名",0).total());
        assertEquals(1,ContentCardWorkbench.page(List.of(first),HASH.substring(0,12),0).total());
    }
    @Test void pagesKeepContentIdentityAcrossAliasesAndIdenticalVisibleNames(){
        var catalog=new ArrayList<ContentCardStore.Entry>();
        for(int i=0;i<10;i++){
            String hash=String.format("%064x",i);
            catalog.add(server(hash,"相同标题.md"));
            catalog.add(new ContentCardStore.Entry(hash,"alias"+i+".md",1024,"别名"+i+".md"));
        }
        var page0=ContentCardWorkbench.page(catalog,"",0);var page1=ContentCardWorkbench.page(catalog,"",1);
        assertEquals(10,page0.total());assertEquals(8,page0.entries().size());assertEquals(2,page1.entries().size());
        var combined=new ArrayList<>(page0.entries());combined.addAll(page1.entries());
        assertEquals(10,combined.stream().map(ContentCardStore.Entry::hash).distinct().count());
        var rows=ContentCardCatalog.page(page1.entries(),List.of(local(String.format("%064x",9),"我的副本.md")),"",false,true);
        assertEquals(2,rows.size());assertEquals(String.format("%064x",8),rows.get(0).hash());assertEquals(String.format("%064x",9),rows.get(1).hash());
        assertEquals("相同标题.md",rows.get(0).name());assertEquals(rows.get(0).name(),rows.get(1).name());
        assertEquals("服务器",rows.get(0).source());assertEquals("本地 / 服务器",rows.get(1).source());
        var aliasSearch=ContentCardWorkbench.page(catalog,"别名9",0);
        assertEquals(1,aliasSearch.total());assertEquals(rows.get(1).hash(),aliasSearch.entries().getFirst().hash());
    }
}
