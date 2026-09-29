// SPDX-License-Identifier: MIT
// Reuse the exact legacy raw ABI without modifying its source or released module.
include!("../../nes-rust/ffi/src/lib.rs");

#[no_mangle]
pub extern "C" fn nes_set_zapper(pointer: *mut Nes, x: i32, y: i32, offscreen: u32, trigger: u32) {
    nes_mut(pointer).set_zapper(x, y, offscreen != 0, trigger != 0);
}
