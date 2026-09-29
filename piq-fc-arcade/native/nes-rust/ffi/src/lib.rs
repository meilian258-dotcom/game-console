use nes_rust::button::Button;
use nes_rust::default_audio::DefaultAudio;
use nes_rust::default_display::DefaultDisplay;
use nes_rust::default_input::DefaultInput;
use nes_rust::rom::Rom;
use nes_rust::Nes;
use std::{cmp, slice};

const FRAME_BYTES: usize = 256 * 240 * 4;
const CPU_RAM_BYTES: usize = 0x800;

fn nes_mut<'a>(pointer: *mut Nes) -> &'a mut Nes {
    assert!(!pointer.is_null());
    unsafe { &mut *pointer }
}

fn button_from_index(index: u32) -> Button {
    match index {
        0 => Button::Poweroff,
        1 => Button::Reset,
        2 => Button::Select,
        3 => Button::Start,
        4 => Button::Joypad1A,
        5 => Button::Joypad1B,
        6 => Button::Joypad1Up,
        7 => Button::Joypad1Down,
        8 => Button::Joypad1Left,
        9 => Button::Joypad1Right,
        10 => Button::Joypad2A,
        11 => Button::Joypad2B,
        12 => Button::Joypad2Up,
        13 => Button::Joypad2Down,
        14 => Button::Joypad2Left,
        15 => Button::Joypad2Right,
        _ => panic!("invalid NES button index"),
    }
}

#[no_mangle]
pub extern "C" fn nes_create() -> *mut Nes {
    let input = Box::new(DefaultInput::new());
    let display = Box::new(DefaultDisplay::new());
    let audio = Box::new(DefaultAudio::new());
    Box::into_raw(Box::new(Nes::new(input, display, audio)))
}

#[no_mangle]
pub extern "C" fn nes_destroy(pointer: *mut Nes) {
    if pointer.is_null() {
        return;
    }
    unsafe {
        drop(Box::from_raw(pointer));
    }
}

#[no_mangle]
pub extern "C" fn nes_alloc(length: usize) -> *mut u8 {
    Box::into_raw(vec![0_u8; length].into_boxed_slice()) as *mut u8
}

#[no_mangle]
pub extern "C" fn nes_dealloc(pointer: *mut u8, length: usize) {
    if pointer.is_null() {
        return;
    }
    unsafe {
        drop(Box::from_raw(slice::from_raw_parts_mut(pointer, length)));
    }
}

#[no_mangle]
pub extern "C" fn nes_set_rom(pointer: *mut Nes, rom_pointer: *mut u8, length: usize) {
    assert!(!rom_pointer.is_null());
    let contents = unsafe { slice::from_raw_parts(rom_pointer, length).to_vec() };
    nes_dealloc(rom_pointer, length);
    nes_mut(pointer).set_rom(Rom::new(contents));
}

#[no_mangle]
pub extern "C" fn nes_bootup(pointer: *mut Nes) {
    nes_mut(pointer).bootup();
}

#[no_mangle]
pub extern "C" fn nes_reset(pointer: *mut Nes) {
    nes_mut(pointer).reset();
}

#[no_mangle]
pub extern "C" fn nes_step_frame(pointer: *mut Nes) {
    nes_mut(pointer).step_frame();
}

#[no_mangle]
pub extern "C" fn nes_copy_frame(pointer: *mut Nes, output: *mut u8, length: usize) {
    assert!(!output.is_null());
    assert_eq!(FRAME_BYTES, length);
    let destination = unsafe { slice::from_raw_parts_mut(output, length) };
    nes_mut(pointer).copy_pixels(destination);
}

#[no_mangle]
pub extern "C" fn nes_copy_audio(pointer: *mut Nes, output: *mut f32, capacity: usize) -> usize {
    assert!(!output.is_null());
    let nes = nes_mut(pointer);
    let count = cmp::min(nes.audio_sample_count(), capacity);
    let destination = unsafe { slice::from_raw_parts_mut(output, count) };
    nes.copy_sample_buffer(destination);
    count
}

#[no_mangle]
pub extern "C" fn nes_copy_cpu_ram(pointer: *mut Nes, output: *mut u8, length: usize) {
    assert!(!output.is_null());
    assert_eq!(CPU_RAM_BYTES, length);
    let destination = unsafe { slice::from_raw_parts_mut(output, length) };
    nes_mut(pointer).copy_cpu_ram(destination);
}

#[no_mangle]
pub extern "C" fn nes_press_button(pointer: *mut Nes, button: u32) {
    nes_mut(pointer).press_button(button_from_index(button));
}

#[no_mangle]
pub extern "C" fn nes_release_button(pointer: *mut Nes, button: u32) {
    nes_mut(pointer).release_button(button_from_index(button));
}
