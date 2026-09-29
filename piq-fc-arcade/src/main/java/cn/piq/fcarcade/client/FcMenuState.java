package cn.piq.fcarcade.client;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Pure UI identity helpers, used only on the client main thread. */
final class FcMenuState {
    private FcMenuState() {}

    static final class PageAnchor {
        private String key = "";
        String key() { return key; }
        void reset() { key = ""; }
        void focus(String selected) { key = selected; }
        int page(List<String> keys, int rows) {
            if (keys.isEmpty()) return 0;
            int index = keys.indexOf(key);
            if (index < 0) { index = 0; key = keys.getFirst(); }
            return index / rows;
        }
        void move(List<String> keys, int rows, int delta) {
            if (keys.isEmpty()) return;
            int next = FcMenuLayout.clampPage(page(keys, rows) + delta, keys.size(), rows);
            key = keys.get(next * rows);
        }
    }

    static final class DismissedTokens {
        private static final int LIMIT = 32;
        private final LinkedHashSet<UUID> tokens = new LinkedHashSet<>();
        boolean contains(UUID token) { return tokens.contains(token); }
        boolean permitsOpen(UUID token, UUID expectedCard, int expectedSlot, UUID heldCard, int actualSlot, boolean alive) {
            return !contains(token) && alive && expectedSlot == actualSlot && expectedCard.equals(heldCard);
        }
        int size() { return tokens.size(); }
        void dismiss(UUID token) {
            tokens.remove(token);
            tokens.add(token);
            while (tokens.size() > LIMIT) tokens.remove(tokens.getFirst());
        }
    }

    /** Null means the full public runtime state is unavailable: callers must not replace it. */
    static List<String> publicBlacklist(Object handler) throws ReflectiveOperationException {
        var names = new LinkedHashSet<String>();
        boolean available = false;
        Class<?> type = handler.getClass();
        for (var method : type.getMethods()) {
            if (method.getParameterCount() == 0 && Collection.class.isAssignableFrom(method.getReturnType())
                    && (method.getName().equals("getBlacklist") || method.getName().equals("getBlurBlacklist"))) {
                Object value = method.invoke(handler);
                if (value instanceof Collection<?> entries) { addNames(names, entries); available = true; }
            }
        }
        for (var field : type.getFields()) {
            if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("blacklist")) {
                Object value = field.get(handler);
                if (value instanceof Collection<?> entries) { addNames(names, entries); available = true; }
            }
        }
        return available ? List.copyOf(names) : null;
    }

    static void addNames(LinkedHashSet<String> names, Collection<?> values) {
        for (Object value : values) {
            String name = value instanceof Class<?> type ? type.getName() : value instanceof String text ? text : null;
            if (name == null) throw new IllegalArgumentException("Unknown runtime blacklist entry; refusing partial replacement");
            if (!name.isBlank()) names.add(name);
        }
    }
}
