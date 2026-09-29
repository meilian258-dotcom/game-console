package cn.piq.fcarcade.home;

import java.util.UUID;

/** Exactly one physical value moves between dock and borrower; never manufactures a replacement. */
public final class ZapperDock<T> {
    public record Loan(UUID id,UUID player) {public Loan {if(id==null||player==null)throw new IllegalArgumentException("Loan identity");}}
    private T stored;
    private Loan loan;
    public T stored(){return stored;}
    public Loan loan(){return loan;}
    public boolean canDeposit(UUID receipt){return stored==null&&(loan==null?receipt==null:loan.id.equals(receipt));}
    public boolean deposit(T item,UUID receipt){if(item==null||!canDeposit(receipt))return false;stored=item;loan=null;return true;}
    public T take(UUID player){if(stored==null||loan!=null||player==null)return null;T result=stored;stored=null;loan=new Loan(UUID.randomUUID(),player);return result;}
    public T remove(){T result=stored;stored=null;loan=null;return result;}
    public void restore(T item,Loan issued){if(item!=null&&issued!=null)throw new IllegalArgumentException("Dock cannot contain its borrowed gun");stored=item;loan=issued;}
}
