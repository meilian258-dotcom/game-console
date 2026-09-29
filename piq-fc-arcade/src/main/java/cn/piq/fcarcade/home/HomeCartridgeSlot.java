package cn.piq.fcarcade.home;

/** One physical cartridge; taking ownership empties the slot before delivery. */
final class HomeCartridgeSlot<T> {
    private T value;
    T value() { return value; }
    boolean occupied() { return value != null; }
    boolean insert(T cartridge) {
        if (cartridge == null || occupied()) return false;
        value = cartridge;
        return true;
    }
    T take() {
        T removed = value;
        value = null;
        return removed;
    }
    void restore(T cartridge) { value = cartridge; }
}
