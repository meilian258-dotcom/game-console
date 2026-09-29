package cn.piq.fcarcade.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-only test support; never loads a Minecraft or native class. */
final class ClientSourceContracts {
    private ClientSourceContracts() { }
    static Path workspace() throws IOException {
        for (Path p = Path.of("").toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("piq-fc-arcade/src/main/java")) && Files.isDirectory(p.resolve("piq-native-arcade/src/main/java"))) return p;
        }
        throw new IOException("PIQ source workspace not found");
    }
    static String read(String relative) throws IOException { return uncomment(Files.readString(workspace().resolve(relative))); }
    static String compact(String source) { return source.replaceAll("\\s+", ""); }
    static String body(String source, String signature) {
        int match = source.indexOf(signature);
        if (match < 0) throw new AssertionError("Missing production method: " + signature);
        int start = source.indexOf('{', match), depth = 0; char quote = 0; boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return compact(source.substring(start + 1, i));
        }
        throw new AssertionError("Unclosed method: " + signature);
    }
    static String uncomment(String source) {
        StringBuilder out = new StringBuilder(); char quote = 0; boolean escaped = false, line = false, block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n') { line = false; out.append(c); } continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (quote != 0) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; out.append(c); continue; }
            if (c == '/' && next == '/') { line = true; i++; continue; }
            if (c == '/' && next == '*') { block = true; i++; continue; }
            out.append(c);
        }
        return out.toString();
    }
}
