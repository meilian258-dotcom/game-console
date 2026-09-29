// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.runtime;
import java.nio.file.Path;
import java.util.function.Consumer;
/** Local engine boundary; neither implementations nor save paths are chosen by a server. */
public interface PvzEngine extends AutoCloseable {
    void input(int pad,int x,int y,int buttons,boolean pointer);
    void key(boolean down,int key,int character);
    void pause(boolean paused); void volume(float value); void audioSink(Consumer<byte[]> sink);
    byte[] poll(); void releaseFrame(byte[] frame);
    boolean ready(); boolean finished(); String error(); String status(); Path saveDirectory();
    @Override void close();
}
