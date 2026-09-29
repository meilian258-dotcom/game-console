package cn.piq.fcarcade.home;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Server-owned receipts for physical cables; old links without a receipt refund nothing. */
public final class DataCablePayments {
    private final Set<UUID> paid=new HashSet<>();
    public boolean record(UUID id) {return id!=null&&paid.size()<128&&paid.add(id);}
    public boolean paid(UUID id) {return paid.contains(id);}
    /** Claim before inventory/drop callbacks. Repeated unlink/removal cannot mint another cable. */
    public boolean claim(UUID id) {return id!=null&&paid.remove(id);}
}
