// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

/** Names and limits shared by the Java loader and native/WASM adapter. */
public final class SfcCoreAbi {
    public static final int VERSION = 1;
    public static final String ABI_VERSION = "piq_sfc_abi_version";
    public static final String CREATE = "piq_sfc_create";
    public static final String DESTROY = "piq_sfc_destroy";
    public static final String LOAD_ROM = "piq_sfc_load_rom";
    public static final String SET_INPUT = "piq_sfc_set_input";
    public static final String RUN_FRAME = "piq_sfc_run_frame";
    public static final String RESET = "piq_sfc_reset";
    public static final String FRAME_WIDTH = "piq_sfc_frame_width";
    public static final String FRAME_HEIGHT = "piq_sfc_frame_height";
    public static final String FRAME_STRIDE = "piq_sfc_frame_stride";
    public static final String FRAME_POINTER = "piq_sfc_frame_ptr";
    public static final String FRAME_LENGTH = "piq_sfc_frame_len";
    public static final String PIXEL_ASPECT_RATIO = "piq_sfc_pixel_aspect_ratio";
    public static final String TARGET_FPS = "piq_sfc_target_fps";
    public static final String AUDIO_POINTER = "piq_sfc_audio_ptr";
    public static final String AUDIO_SAMPLE_FRAMES = "piq_sfc_audio_sample_frames";
    public static final String AUDIO_SAMPLE_RATE = "piq_sfc_audio_sample_rate";
    public static final String SAVE_STATE_SIZE = "piq_sfc_save_state_size";
    public static final String SAVE_STATE = "piq_sfc_save_state";
    public static final String LOAD_STATE = "piq_sfc_load_state";
    public static final String SAVE_SRAM_SIZE = "piq_sfc_sram_size";
    public static final String SAVE_SRAM = "piq_sfc_save_sram";
    public static final String LOAD_SRAM = "piq_sfc_load_sram";
    public static final String LAST_ERROR_POINTER = "piq_sfc_last_error_ptr";
    public static final String LAST_ERROR_LENGTH = "piq_sfc_last_error_len";
    public static final String ALLOCATE = "piq_sfc_alloc";
    public static final String FREE = "piq_sfc_free";

    private SfcCoreAbi() {
    }
}
