package javax.wireless.messaging;

import java.io.IOException;
import java.io.InterruptedIOException;
import javax.microedition.io.Connection;

/** JSR-120 shape only; the emulator intentionally provides no SMS transport. */
public interface MessageConnection extends Connection {
    String TEXT_MESSAGE = "text";
    String BINARY_MESSAGE = "binary";
    String MULTIPART_MESSAGE = "multipart";

    Message newMessage(String type);

    Message newMessage(String type, String address);

    void send(Message message) throws IOException, InterruptedIOException;

    Message receive() throws IOException, InterruptedIOException;

    void setMessageListener(MessageListener listener) throws IOException;

    int numberOfSegments(Message message);
}
