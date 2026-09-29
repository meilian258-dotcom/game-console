// SPDX-License-Identifier: MIT
// Independent implementation from NESdev hardware descriptions, not emulator code.
// D3 is active-low light, D4 is active-high trigger. Sensor persistence is a
// pragmatic ~2000 CPU-cycle (6000 PPU-dot) approximation, not analog circuit emulation.
pub const HOLD_DOTS: u16 = 6000;
const RADIUS: i32 = 8;
const LUMA_THRESHOLD: u32 = 192;

pub struct Zapper {
    x: i32,
    y: i32,
    offscreen: bool,
    trigger: bool,
    remaining: u16,
}

impl Zapper {
    pub fn new() -> Self { Self { x: 0, y: 0, offscreen: true, trigger: false, remaining: 0 } }
    pub fn reset(&mut self) { *self = Self::new(); }
    pub fn set(&mut self, x: i32, y: i32, offscreen: bool, trigger: bool) {
        let outside = offscreen || x < 0 || x >= 256 || y < 0 || y >= 240;
        // Moving aim must not carry a previously lit target to a different location.
        if outside || x != self.x || y != self.y || outside != self.offscreen { self.remaining = 0; }
        self.x = x; self.y = y; self.offscreen = outside; self.trigger = trigger;
    }
    pub fn clock(&mut self) { self.remaining = self.remaining.saturating_sub(1); }
    pub fn pixel(&mut self, x: u16, y: u16, abgr: u32) {
        if self.offscreen || (x as i32 - self.x).abs() > RADIUS || (y as i32 - self.y).abs() > RADIUS { return; }
        // The actual final, priority-composited and emphasis-adjusted PPU color.
        let r = abgr & 255; let g = (abgr >> 8) & 255; let b = (abgr >> 16) & 255;
        if (299 * r + 587 * g + 114 * b) / 1000 >= LUMA_THRESHOLD { self.remaining = HOLD_DOTS; }
    }
    pub fn read(&self) -> u8 {
        (if self.offscreen || self.remaining == 0 { 8 } else { 0 }) | (if self.trigger { 16 } else { 0 })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn default_dark_no_trigger() { assert_eq!(Zapper::new().read(), 8); }
    #[test] fn trigger_independent_of_light() { let mut z=Zapper::new(); z.set(128,120,false,true); assert_eq!(z.read(),24); z.pixel(128,120,0xffffffff); assert_eq!(z.read(),16); }
    #[test] fn exact_bounded_clock_decay() { let mut z=Zapper::new(); z.set(10,10,false,false); z.pixel(10,10,0xffffffff); for _ in 0..HOLD_DOTS-1 { z.clock(); } assert_eq!(z.read(),0); z.clock(); assert_eq!(z.read(),8); }
    #[test] fn black_does_not_recharge() { let mut z=Zapper::new(); z.set(10,10,false,false); z.pixel(10,10,0xffffffff); for _ in 0..HOLD_DOTS {z.clock();z.pixel(10,10,0xff000000);} assert_eq!(z.read(),8); }
    #[test] fn offscreen_and_invalid_are_dark() { for &(x,y,off) in &[(-1,10,false),(256,10,false),(10,240,false),(10,-1,false),(10,10,true)] {let mut z=Zapper::new();z.set(x,y,off,true);z.pixel(10,10,0xffffffff);assert_eq!(z.read(),24);} }
    #[test] fn regional_not_whole_screen_light() { let mut z=Zapper::new();z.set(128,120,false,false);z.pixel(0,0,0xffffffff);assert_eq!(z.read(),8);z.pixel(136,128,0xffffffff);assert_eq!(z.read(),0);z.set(0,0,false,false);assert_eq!(z.read(),8); }
    #[test] fn trigger_change_keeps_same_aim_sensor() {let mut z=Zapper::new();z.set(1,1,false,false);z.pixel(1,1,0xffffffff);z.set(1,1,false,true);assert_eq!(z.read(),16);z.set(1,1,false,false);assert_eq!(z.read(),0);}
    #[test] fn color_luminance_and_reset() {let mut z=Zapper::new();z.set(1,1,false,true);for color in [0xff000000,0xff0000ff,0xff00ff00,0xffff0000] {z.pixel(1,1,color);assert_eq!(z.read(),24);}z.pixel(1,1,0xffffffff);z.reset();assert_eq!(z.read(),8);}
}
