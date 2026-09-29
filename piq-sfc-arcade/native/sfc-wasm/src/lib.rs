// SPDX-License-Identifier: GPL-3.0-or-later

use bincode::{Decode, Encode};
use jgenesis_common::frontend::{
    AudioOutput, EmulatorTrait, FrameSize, InputPoller, RenderFrameOptions, Renderer, SaveWriter,
    TickEffect,
};
use snes_config::SnesJoypadState;
use snes_core::api::{CoprocessorRoms, SnesEmulator, SnesEmulatorConfig};
use snes_core::input::{SnesInputDevice, SnesInputs};
use std::cell::RefCell;
use std::cmp;
use std::slice;
use std::sync::atomic::{AtomicU32, Ordering};

const ABI_VERSION: u32 = 1;
const AUDIO_SAMPLE_RATE: u32 = 48_000;
const MAX_ROM_BYTES: usize = 32 * 1024 * 1024;

// OS-less WASM has no system entropy source. jgenesis only reaches getrandom
// through utility dependencies; a deterministic source keeps the core
// headless and, importantly, replay-safe for future multiplayer sessions.
static RANDOM_STATE: AtomicU32 = AtomicU32::new(0x5049_5153);

#[unsafe(no_mangle)]
unsafe extern "Rust" fn __getrandom_v03_custom(
    dest: *mut u8,
    len: usize,
) -> Result<(), getrandom::Error> {
    if dest.is_null() && len != 0 {
        return Err(getrandom::Error::UNEXPECTED);
    }

    let output = unsafe { slice::from_raw_parts_mut(dest, len) };
    for byte in output {
        let mut previous = RANDOM_STATE.load(Ordering::Relaxed);
        loop {
            let mut next = previous;
            next ^= next << 13;
            next ^= next >> 17;
            next ^= next << 5;
            match RANDOM_STATE.compare_exchange_weak(
                previous,
                next,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => {
                    *byte = next as u8;
                    break;
                }
                Err(actual) => previous = actual,
            }
        }
    }
    Ok(())
}

thread_local! {
    static LAST_ERROR: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

fn set_error(message: impl Into<String>) -> i32 {
    LAST_ERROR.with(|cell| {
        *cell.borrow_mut() = message.into().into_bytes();
    });
    -1
}

#[derive(Default)]
struct FrameCollector {
    rgba: Vec<u8>,
    width: u32,
    height: u32,
    target_fps: f64,
    pixel_aspect_ratio: f64,
}

impl Renderer for FrameCollector {
    type Err = String;

    fn render_frame(
        &mut self,
        frame_buffer: &[jgenesis_common::frontend::Color],
        frame_size: FrameSize,
        target_fps: f64,
        options: RenderFrameOptions,
    ) -> Result<(), Self::Err> {
        let pixel_count = frame_size.len() as usize;
        if frame_buffer.len() < pixel_count {
            return Err("jgenesis returned a frame buffer shorter than its dimensions".into());
        }

        self.rgba.clear();
        self.rgba.reserve(pixel_count.saturating_mul(4));
        for color in &frame_buffer[..pixel_count] {
            self.rgba.extend_from_slice(&[color.r, color.g, color.b, color.a]);
        }
        self.width = frame_size.width;
        self.height = frame_size.height;
        self.target_fps = target_fps;
        self.pixel_aspect_ratio = options.pixel_aspect_ratio.map_or(1.0, |ratio| ratio.get());
        Ok(())
    }
}

#[derive(Default)]
struct AudioCollector {
    pcm16_stereo: Vec<i16>,
}

impl AudioOutput for AudioCollector {
    type Err = String;

    fn push_sample(&mut self, sample_l: f64, sample_r: f64) -> Result<(), Self::Err> {
        self.pcm16_stereo.push(float_to_pcm16(sample_l));
        self.pcm16_stereo.push(float_to_pcm16(sample_r));
        Ok(())
    }
}

fn float_to_pcm16(sample: f64) -> i16 {
    let clamped = sample.clamp(-1.0, 1.0);
    if clamped < 0.0 {
        (clamped * 32768.0).round() as i16
    } else {
        (clamped * 32767.0).round() as i16
    }
}

#[derive(Default)]
struct FixedInput {
    inputs: SnesInputs,
}

impl InputPoller<SnesInputs> for FixedInput {
    fn poll(&mut self) -> &SnesInputs {
        &self.inputs
    }
}

#[derive(Default)]
struct MemorySaveWriter {
    sram: Option<Vec<u8>>,
    auxiliary: Vec<(String, Vec<u8>)>,
}

impl MemorySaveWriter {
    fn put(&mut self, extension: &str, bytes: Vec<u8>) {
        if extension == "sav" {
            self.sram = Some(bytes);
        } else if let Some((_, current)) =
            self.auxiliary.iter_mut().find(|(name, _)| name == extension)
        {
            *current = bytes;
        } else {
            self.auxiliary.push((extension.to_string(), bytes));
        }
    }

    fn get(&self, extension: &str) -> Option<&[u8]> {
        if extension == "sav" {
            return self.sram.as_deref();
        }
        self.auxiliary
            .iter()
            .find(|(name, _)| name == extension)
            .map(|(_, bytes)| bytes.as_slice())
    }
}

impl SaveWriter for MemorySaveWriter {
    type Err = String;

    fn load_bytes(&mut self, extension: &str) -> Result<Vec<u8>, Self::Err> {
        self.get(extension)
            .map(<[u8]>::to_vec)
            .ok_or_else(|| format!("no in-memory save for extension {extension}"))
    }

    fn persist_bytes(&mut self, extension: &str, bytes: &[u8]) -> Result<(), Self::Err> {
        self.put(extension, bytes.to_vec());
        Ok(())
    }

    fn load_serialized<D: Decode<()>>(&mut self, extension: &str) -> Result<D, Self::Err> {
        let bytes = self
            .get(extension)
            .ok_or_else(|| format!("no in-memory save for extension {extension}"))?;
        let (value, _) = bincode::decode_from_slice(bytes, bincode_config())
            .map_err(|err| format!("failed to decode {extension}: {err}"))?;
        Ok(value)
    }

    fn persist_serialized<E: Encode>(&mut self, extension: &str, data: E) -> Result<(), Self::Err> {
        let bytes = bincode::encode_to_vec(data, bincode_config())
            .map_err(|err| format!("failed to encode {extension}: {err}"))?;
        self.put(extension, bytes);
        Ok(())
    }
}

fn bincode_config() -> impl bincode::config::Config {
    bincode::config::standard()
        .with_little_endian()
        .with_fixed_int_encoding()
}

// Opaque across the C/WASM boundary. Public visibility only prevents Rust from
// warning about exported functions that accept its pointer.
pub struct CoreHandle {
    emulator: Option<SnesEmulator>,
    rom: Vec<u8>,
    renderer: FrameCollector,
    audio: AudioCollector,
    input: FixedInput,
    saves: MemorySaveWriter,
    state_buffer: Vec<u8>,
}

impl Default for CoreHandle {
    fn default() -> Self {
        Self {
            emulator: None,
            rom: Vec::new(),
            renderer: FrameCollector::default(),
            audio: AudioCollector::default(),
            input: FixedInput::default(),
            saves: MemorySaveWriter::default(),
            state_buffer: Vec::new(),
        }
    }
}

impl CoreHandle {
    fn recreate_emulator(&mut self) -> Result<(), String> {
        if self.rom.is_empty() {
            return Err("no SFC ROM has been loaded".into());
        }

        let mut emulator = SnesEmulator::create(
            self.rom.clone(),
            SnesEmulatorConfig::default(),
            CoprocessorRoms::none(),
            &mut self.saves,
        )
        .map_err(|err| err.to_string())?;
        emulator.update_audio_output_frequency(AUDIO_SAMPLE_RATE.into());
        self.emulator = Some(emulator);
        self.renderer = FrameCollector::default();
        self.audio = AudioCollector::default();
        Ok(())
    }
}

fn core_mut<'a>(pointer: *mut CoreHandle) -> Result<&'a mut CoreHandle, i32> {
    if pointer.is_null() {
        return Err(set_error("null SFC core handle"));
    }
    Ok(unsafe { &mut *pointer })
}

fn joypad(mask: u16) -> SnesJoypadState {
    SnesJoypadState {
        b: mask & (1 << 0) != 0,
        y: mask & (1 << 1) != 0,
        select: mask & (1 << 2) != 0,
        start: mask & (1 << 3) != 0,
        up: mask & (1 << 4) != 0,
        down: mask & (1 << 5) != 0,
        left: mask & (1 << 6) != 0,
        right: mask & (1 << 7) != 0,
        a: mask & (1 << 8) != 0,
        x: mask & (1 << 9) != 0,
        l: mask & (1 << 10) != 0,
        r: mask & (1 << 11) != 0,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_abi_version() -> u32 {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_create() -> *mut CoreHandle {
    Box::into_raw(Box::new(CoreHandle::default()))
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_destroy(pointer: *mut CoreHandle) {
    if !pointer.is_null() {
        unsafe { drop(Box::from_raw(pointer)) };
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_alloc(length: usize) -> *mut u8 {
    Box::into_raw(vec![0_u8; length].into_boxed_slice()).cast()
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_free(pointer: *mut u8, length: usize) {
    if !pointer.is_null() {
        unsafe { drop(Box::from_raw(slice::from_raw_parts_mut(pointer, length))) };
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_load_rom(
    handle_pointer: *mut CoreHandle,
    rom_pointer: *mut u8,
    length: usize,
) -> i32 {
    let handle = match core_mut(handle_pointer) {
        Ok(handle) => handle,
        Err(code) => return code,
    };
    if rom_pointer.is_null() {
        return set_error("null ROM pointer");
    }
    if !(32 * 1024..=MAX_ROM_BYTES).contains(&length) {
        piq_sfc_free(rom_pointer, length);
        return set_error(format!("invalid SFC ROM length: {length}"));
    }

    handle.rom = unsafe { slice::from_raw_parts(rom_pointer, length) }.to_vec();
    piq_sfc_free(rom_pointer, length);
    match handle.recreate_emulator() {
        Ok(()) => 0,
        Err(err) => set_error(err),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_set_input(
    handle_pointer: *mut CoreHandle,
    player: u32,
    mask: u32,
) -> i32 {
    let handle = match core_mut(handle_pointer) {
        Ok(handle) => handle,
        Err(code) => return code,
    };
    let state = joypad(mask as u16);
    match player {
        0 => handle.input.inputs.p1 = state,
        1 => handle.input.inputs.p2 = SnesInputDevice::Controller(state),
        _ => return set_error(format!("invalid SFC player index: {player}")),
    }
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_run_frame(handle_pointer: *mut CoreHandle) -> i32 {
    let handle = match core_mut(handle_pointer) {
        Ok(handle) => handle,
        Err(code) => return code,
    };
    let Some(emulator) = handle.emulator.as_mut() else {
        return set_error("no SFC ROM has been loaded");
    };

    handle.audio.pcm16_stereo.clear();
    loop {
        match emulator.tick(
            &mut handle.renderer,
            &mut handle.audio,
            &mut handle.input,
            &mut handle.saves,
        ) {
            Ok(TickEffect::FrameRendered) => return 0,
            Ok(TickEffect::None) => {}
            Err(err) => return set_error(err.to_string()),
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_reset(handle_pointer: *mut CoreHandle, hard: u32) -> i32 {
    let handle = match core_mut(handle_pointer) {
        Ok(handle) => handle,
        Err(code) => return code,
    };
    if handle.emulator.is_none() {
        return set_error("no SFC ROM has been loaded");
    }

    if hard != 0 {
        if let Err(err) = handle.recreate_emulator() {
            return set_error(err);
        }
    } else if let Some(emulator) = handle.emulator.as_mut() {
        emulator.soft_reset();
        handle.renderer = FrameCollector::default();
        handle.audio = AudioCollector::default();
        handle.input = FixedInput::default();
    }
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_frame_width(pointer: *mut CoreHandle) -> u32 {
    core_mut(pointer).map_or(0, |handle| handle.renderer.width)
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_frame_height(pointer: *mut CoreHandle) -> u32 {
    core_mut(pointer).map_or(0, |handle| handle.renderer.height)
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_frame_stride(pointer: *mut CoreHandle) -> u32 {
    piq_sfc_frame_width(pointer).saturating_mul(4)
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_frame_ptr(pointer: *mut CoreHandle) -> *const u8 {
    core_mut(pointer).map_or(std::ptr::null(), |handle| handle.renderer.rgba.as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_frame_len(pointer: *mut CoreHandle) -> usize {
    core_mut(pointer).map_or(0, |handle| handle.renderer.rgba.len())
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_pixel_aspect_ratio(pointer: *mut CoreHandle) -> f64 {
    core_mut(pointer).map_or(1.0, |handle| handle.renderer.pixel_aspect_ratio)
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_target_fps(pointer: *mut CoreHandle) -> f64 {
    core_mut(pointer).map_or(0.0, |handle| handle.renderer.target_fps)
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_audio_ptr(pointer: *mut CoreHandle) -> *const i16 {
    core_mut(pointer).map_or(std::ptr::null(), |handle| {
        handle.audio.pcm16_stereo.as_ptr()
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_audio_sample_frames(pointer: *mut CoreHandle) -> usize {
    core_mut(pointer).map_or(0, |handle| handle.audio.pcm16_stereo.len() / 2)
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_audio_sample_rate() -> u32 {
    AUDIO_SAMPLE_RATE
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_save_state_size(pointer: *mut CoreHandle) -> usize {
    let Ok(handle) = core_mut(pointer) else { return 0 };
    let Some(emulator) = handle.emulator.as_ref() else {
        set_error("no SFC ROM has been loaded");
        return 0;
    };
    match bincode::encode_to_vec(emulator.to_save_state(), bincode_config()) {
        Ok(bytes) => {
            handle.state_buffer = bytes;
            handle.state_buffer.len()
        }
        Err(err) => {
            set_error(format!("failed to encode SFC state: {err}"));
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_save_state(
    pointer: *mut CoreHandle,
    output: *mut u8,
    capacity: usize,
) -> usize {
    let Ok(handle) = core_mut(pointer) else { return 0 };
    if output.is_null() {
        set_error("null save-state output pointer");
        return 0;
    }
    let count = cmp::min(capacity, handle.state_buffer.len());
    unsafe { slice::from_raw_parts_mut(output, count) }
        .copy_from_slice(&handle.state_buffer[..count]);
    count
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_load_state(
    pointer: *mut CoreHandle,
    input: *const u8,
    length: usize,
) -> i32 {
    let handle = match core_mut(pointer) {
        Ok(handle) => handle,
        Err(code) => return code,
    };
    if input.is_null() {
        return set_error("null save-state input pointer");
    }
    let Some(emulator) = handle.emulator.as_mut() else {
        return set_error("no SFC ROM has been loaded");
    };
    let bytes = unsafe { slice::from_raw_parts(input, length) };
    let (state, _): (SnesEmulator, usize) = match bincode::decode_from_slice(bytes, bincode_config())
    {
        Ok(decoded) => decoded,
        Err(err) => return set_error(format!("failed to decode SFC state: {err}")),
    };
    emulator.load_state(state);
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_sram_size(pointer: *mut CoreHandle) -> usize {
    core_mut(pointer).map_or(0, |handle| {
        handle.saves.sram.as_ref().map_or(0, Vec::len)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_save_sram(
    pointer: *mut CoreHandle,
    output: *mut u8,
    capacity: usize,
) -> usize {
    let Ok(handle) = core_mut(pointer) else { return 0 };
    if output.is_null() {
        set_error("null SRAM output pointer");
        return 0;
    }
    let Some(sram) = handle.saves.sram.as_ref() else { return 0 };
    let count = cmp::min(capacity, sram.len());
    unsafe { slice::from_raw_parts_mut(output, count) }.copy_from_slice(&sram[..count]);
    count
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_load_sram(
    pointer: *mut CoreHandle,
    input: *const u8,
    length: usize,
) -> i32 {
    let handle = match core_mut(pointer) {
        Ok(handle) => handle,
        Err(code) => return code,
    };
    if input.is_null() {
        return set_error("null SRAM input pointer");
    }
    handle.saves.sram = Some(unsafe { slice::from_raw_parts(input, length) }.to_vec());
    if handle.rom.is_empty() {
        return 0;
    }
    match handle.recreate_emulator() {
        Ok(()) => 0,
        Err(err) => set_error(err),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_last_error_ptr() -> *const u8 {
    LAST_ERROR.with(|cell| cell.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn piq_sfc_last_error_len() -> usize {
    LAST_ERROR.with(|cell| cell.borrow().len())
}
