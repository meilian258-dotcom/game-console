// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** UI-only draft, search and stable selection. No ROM reads or network operations. */
final class SfcCardLibrary {
    record Row(String key,String name,long bytes,String hash,Path path) {
        static Row server(String hash,String name,long bytes){return new Row("server:"+hash,name,bytes,hash,null);}
        static Row local(Path path,String name,long bytes){return new Row("local:"+path.toAbsolutePath().normalize(),name,bytes,"",path);}
        boolean local(){return path!=null;}
    }
    private List<Row> server=List.of(),local=List.of();
    private String title,query="",selected="";
    private boolean titleDirty;
    private Function<Row,String> label=Row::name;
    private int first;
    SfcCardLibrary(String title){this.title=title;}
    void labels(Function<Row,String> label){this.label=Objects.requireNonNull(label);}
    String title(){return title;}
    boolean titleDirty(){return titleDirty;}
    void title(String value){if(!title.equals(value)){title=value;titleDirty=true;}}
    String query(){return query;}
    void query(String value){if(!query.equals(value)){query=value;first=0;}}
    void server(List<Row> rows,String currentRom){
        server=sorted(rows);
        if(selected.isEmpty())for(Row row:server)if(row.hash().equals(currentRom)){selected=row.key();break;}
        retainSelection();
    }
    void local(List<Row> rows){local=sorted(rows);retainSelection();}
    private static List<Row> sorted(List<Row> rows){return rows.stream().sorted(Comparator.comparing(Row::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Row::key)).toList();}
    private List<Row> all(){var rows=new ArrayList<Row>(server);rows.addAll(local);return rows;}
    private void retainSelection(){if(!selected.isEmpty()&&all().stream().noneMatch(r->r.key().equals(selected)))selected="";}
    List<Row> filtered(){String q=query.strip().toLowerCase(Locale.ROOT);return all().stream().filter(r->q.isEmpty()||r.name().toLowerCase(Locale.ROOT).contains(q)||label.apply(r).toLowerCase(Locale.ROOT).contains(q)).toList();}
    Row selected(){return all().stream().filter(r->r.key().equals(selected)).findFirst().orElse(null);}
    boolean selected(Row row){return selected.equals(row.key());}
    void select(Row row){
        if(all().stream().noneMatch(r->r.key().equals(row.key()))||selected.equals(row.key()))return;
        selected=row.key();
        if(!titleDirty){
            String name=label.apply(row);int end=Math.min(128,name.length());
            if(end>0&&Character.isHighSurrogate(name.charAt(end-1)))end--;
            title=name.substring(0,end);
        }
    }
    int page(int rows){return start(rows)/Math.max(1,rows);}
    int pages(int rows){return Math.max(1,(filtered().size()+Math.max(1,rows)-1)/Math.max(1,rows));}
    private int start(int rows){rows=Math.max(1,rows);int last=(pages(rows)-1)*rows;first=Math.max(0,Math.min(first,last));return first/rows*rows;}
    void turn(int delta,int rows){first=Math.max(0,Math.min(page(rows)+delta,pages(rows)-1))*Math.max(1,rows);}
    List<Row> visible(int rows){var list=filtered();int from=start(rows);return list.subList(from,Math.min(list.size(),from+Math.max(1,rows)));}
}
