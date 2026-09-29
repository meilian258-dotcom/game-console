package cn.piq.computer.client;
/** Client-local program boundary. Hardware knows nothing about a specific game core. */
public interface ProgramBackend extends AutoCloseable {
    int width(); int height();
    byte[] frame(); void releaseFrame(byte[] frame);
    void pointer(int x,int y,int buttons,boolean onScreen);
    void key(int key,boolean down); void text(int codepoint); void scroll(int direction);
    void pause(boolean paused); void volume(float volume);
    boolean ready(); boolean finished(); String status(); String error();
    void releaseInput();
    @FunctionalInterface interface AudioSink { void pcm(byte[] pcm,int rate,int channels); }
    default void audioSink(AudioSink sink){}
    @Override void close();
}
