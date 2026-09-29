package javax.wireless.messaging;

import java.util.Date;

/** Minimal JSR-120 message contract used by offline MIDlets. */
public interface Message {
    String getAddress();

    void setAddress(String address);

    Date getTimestamp();
}
