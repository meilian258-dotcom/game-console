pub mod register;
pub mod cpu;
pub mod ppu;
pub mod apu;
pub mod rom;
pub mod memory;
pub mod mapper;
mod mapper19;
pub mod button;
pub mod joypad;
pub mod input;
pub mod audio;
pub mod display;
pub mod default_input;
pub mod default_audio;
pub mod default_display;
#[cfg(feature = "zapper")]
pub mod zapper;

use cpu::Cpu;
use rom::Rom;
use button::Button;
use input::Input;
use display::Display;
use audio::Audio;

/// NES emulator.
///
/// ```ignore
/// use std::fs::File;
/// use std::io::Read;
/// use std::time::Duration;
/// use nes_rust::Nes;
/// use nes_rust::rom::Rom;
/// use nes_rust::default_input::DefaultInput;
/// use nes_rust::default_audio::DefaultAudio;
/// use nes_rust::default_display::DefaultDisplay;
///
/// let input = Box::new(DefaultInput::new());
/// let display = Box::new(DefaultDisplay::new());
/// let audio = Box::new(DefaultAudio::new());
/// let mut nes = Nes::new(input, display, audio);
///
/// // Load and set Rom from rom image binary
/// let filename = &args[1];
/// let mut file = File::open(filename)?;
/// let mut contents = vec![];
/// file.read_to_end(&mut contents)?;
/// let rom = Rom::new(contents);
/// nes.set_rom(rom);
///
/// // Go!
/// nes.bootup();
/// let mut rgba_pixels = [0; 256 * 240 * 4];
/// loop {
///   nes.step_frame();
///   nes.copy_pixels(rgba_pixels);
///   // Render rgba_pixels
///   // @TODO: Audio buffer sample code is T.B.D.
///   // Adjust sleep time for your platform
///   std::thread::sleep(Duration::from_millis(1));
/// }
/// ```
pub struct Nes {
	cpu: Cpu
}

impl Nes {
	#[cfg(feature = "zapper")]
	pub fn set_zapper(&mut self, x: i32, y: i32, offscreen: bool, trigger: bool) {
		self.cpu.set_zapper(x, y, offscreen, trigger);
	}
	/// Creates a new `Nes`.
    /// You need to pass [`input::Input`](./input/trait.Input.html),
    /// [`display::Display`](./display/trait.Display.html), and
    /// [`audio::Audio`](./audio/trait.Audio.html) traits for your platform
    /// specific Input/Output.
    ///
    /// # Arguments
    /// * `input` For pad input
    /// * `display` For screen output
    /// * `audio` For audio output
	pub fn new(input: Box<dyn Input>, display: Box<dyn Display>,
		audio: Box<dyn Audio>) -> Self {
		Nes {
			cpu: Cpu::new(
				input,
				display,
				audio
			)
		}
	}

	/// Sets up NES rom
	///
	/// # Arguments
	/// * `rom`
	pub fn set_rom(&mut self, rom: Rom) {
		self.cpu.set_rom(rom);
	}

	/// Boots up
	pub fn bootup(&mut self) {
		self.cpu.bootup();
	}

	/// Resets
	pub fn reset(&mut self) {
		self.cpu.reset();
	}

	/// Executes a CPU cycle
	pub fn step(&mut self) {
		self.cpu.step();
	}

	/// Executes a PPU (screen refresh) frame
	pub fn step_frame(&mut self) {
		self.cpu.step_frame();
	}

	/// Copies RGB pixels of screen to passed pixels.
	/// The length and result should be specific to `display` passed via the constructor.
	///
	/// # Arguments
	/// * `pixels`
	pub fn copy_pixels(&self, pixels: &mut [u8]) {
		self.cpu.get_ppu().get_display().copy_to_rgba_pixels(pixels);
	}

	/// Copies audio buffer to passed buffer.
	/// The length and result should be specific to `audio` passed via the constructor.
	///
	/// # Arguments
	/// * `buffer`
	pub fn copy_sample_buffer(&mut self, buffer: &mut [f32]) {
		self.cpu.get_mut_apu().get_mut_audio().copy_sample_buffer(buffer);
	}

	/// Returns the number of audio samples currently waiting to be copied.
	pub fn audio_sample_count(&mut self) -> usize {
		self.cpu.get_mut_apu().get_mut_audio().sample_count()
	}

	/// Copies the NES 2 KiB internal CPU RAM into the provided buffer.
	///
	/// This intentionally exposes only $0000-$07FF. Reading memory-mapped
	/// device registers can have side effects and must not be used by tooling
	/// such as score calibration.
	pub fn copy_cpu_ram(&mut self, buffer: &mut [u8]) {
		let length = std::cmp::min(buffer.len(), 0x800);
		for (address, value) in buffer.iter_mut().take(length).enumerate() {
			*value = self.cpu.load(address as u16);
		}
	}

	/// Presses a pad button
	///
	/// # Arguments
	/// * `button`
	pub fn press_button(&mut self, button: Button) {
		self.cpu.get_mut_input().press(button);
	}

	/// Releases a pad button
	///
	/// # Arguments
	/// * `buffer`
	pub fn release_button(&mut self, button: Button) {
		self.cpu.get_mut_input().release(button);
	}

	/// Checks if NES console is powered on
	pub fn is_power_on(&self) -> bool {
		self.cpu.is_power_on()
	}
}
