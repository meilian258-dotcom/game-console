package cn.piq.replaycompat.audio;

/** Non-blocking output contract. Implementations own device IO and reject stale generations. */
public interface PcmSink extends AutoCloseable {
    enum OfferResult { ACCEPTED, BACKPRESSURE, STALE, DEVICE_RECOVERING, DEVICE_FAILED, INVALID }
    void reset(long generation);
    boolean offer(long generation, byte[] stereo48k);
    /** A refusal is not evidence of a damaged recording. Legacy sinks can retry next tick. */
    default OfferResult offerResult(long generation, byte[] stereo48k) {
        return offer(generation, stereo48k) ? OfferResult.ACCEPTED : OfferResult.BACKPRESSURE;
    }
    @Override void close();
}
