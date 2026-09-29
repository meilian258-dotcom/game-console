package cn.piq.flashbox.runtime;

import java.util.ArrayDeque;

/** Bounded ordered edges with replaceable consecutive pointer motion; never drop a button edge. */
public final class FlashCommands {
    private record Entry(String text,int pointerButtons){}
    private final ArrayDeque<Entry> queue=new ArrayDeque<>();
    public synchronized boolean offer(String text,int buttons){
        var last=queue.peekLast();
        if(last!=null&&last.text.equals(text))return true;
        if(buttons>=0&&last!=null&&last.pointerButtons==buttons)queue.removeLast();
        if(queue.size()>=128)return false;
        queue.addLast(new Entry(text,buttons));return true;
    }
    public synchronized String poll(){var e=queue.pollFirst();return e==null?null:e.text;}
    public synchronized void clear(){queue.clear();}
    public synchronized int size(){return queue.size();}
}
