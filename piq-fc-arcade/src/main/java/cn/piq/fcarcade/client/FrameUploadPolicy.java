// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client;

final class FrameUploadPolicy {
    private FrameUploadPolicy() {
    }

    static boolean shouldUpload(
            boolean controller,
            boolean visible,
            long sequence
    ) {
        if (!visible) return false;
        return controller || sequence <= 0 || (sequence & 1L) == 0L;
    }
}
