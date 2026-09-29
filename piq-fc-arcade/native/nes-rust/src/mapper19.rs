// Namco 163 (iNES mapper 19), implemented from the NESdev register description:
// https://www.nesdev.org/wiki/INES_Mapper_019
// https://www.nesdev.org/wiki/Namco_163_audio
// This is intentionally partial support: bank mapping, IRQ and chip RAM only.
// Expansion wavetable synthesis/phase updates and the diagnostic CHR pin mode
// are not implemented. In particular this is not full Namco 129/163 audio
// compatibility. No implementation from another emulator is incorporated here.

use mapper::{Mapper, MapperPpuAddress};
use rom::{Mirrorings, RomHeader};

pub struct Namco163Mapper {
	prg_banks: u32,
	chr_banks: u32,
	prg: [u8; 3],
	chr_and_nt: [u8; 12],
	ram_control: u8,
	chip_ram: [u8; 128],
	chip_address: u8,
	chip_auto_increment: bool,
	irq_counter: u16,
	irq_enabled: bool,
	irq_pending: bool
}

impl Namco163Mapper {
	pub fn new(header: &RomHeader) -> Self {
		// Hardware does not promise initialized mapper RAM/register contents.
		// Use deterministic zeroes, with IRQ off and external RAM protected.
		// The final 8 KiB PRG bank (including reset vectors) is always fixed.
		Namco163Mapper {
			prg_banks: (header.prg_rom_bank_num() as u32 * 2).max(1),
			chr_banks: (header.chr_rom_bank_num() as u32 * 8).max(8),
			prg: [0; 3],
			chr_and_nt: [0; 12],
			ram_control: 0,
			chip_ram: [0; 128],
			chip_address: 0,
			chip_auto_increment: false,
			irq_counter: 0,
			irq_enabled: false,
			irq_pending: false
		}
	}

	fn increment_chip_address(&mut self) {
		// NESdev's corrected hardware behavior: stop at $7F, do not wrap.
		if self.chip_auto_increment && self.chip_address < 0x7F {
			self.chip_address += 1;
		}
	}

	fn chr_offset(&self, bank: u8, offset: u16) -> u32 {
		(bank as u32 % self.chr_banks) * 0x400 + (offset & 0x3FF) as u32
	}
}

impl Mapper for Namco163Mapper {
	fn map(&self, address: u32) -> u32 {
		let slot = ((address - 0x8000) / 0x2000) as usize;
		let bank = if slot == 3 {
			self.prg_banks - 1
		} else {
			(self.prg[slot] & 0x3F) as u32 % self.prg_banks
		};
		bank * 0x2000 + (address & 0x1FFF)
	}

	fn map_for_chr_rom(&self, address: u32) -> u32 {
		self.chr_offset(self.chr_and_nt[(address / 0x400) as usize], address as u16)
	}

	fn map_ppu(&self, address: u16) -> Option<MapperPpuAddress> {
		if address >= 0x3F00 { return None; } // Palette RAM is never banked.
		let (slot, ciram_enabled) = if address < 0x2000 {
			let disable_mask = if address < 0x1000 { 0x40 } else { 0x80 };
			((address / 0x400) as usize, self.prg[1] & disable_mask == 0)
		} else {
			// $3000-$3EFF mirrors $2000-$2EFF, independently of iNES mirroring.
			(8 + ((address & 0xFFF) / 0x400) as usize, true)
		};
		let bank = self.chr_and_nt[slot];
		Some(if bank >= 0xE0 && ciram_enabled {
			MapperPpuAddress::Ciram(((bank as u16 & 1) * 0x400) | (address & 0x3FF))
		} else {
			MapperPpuAddress::Chr(self.chr_offset(bank, address))
		})
	}

	fn store(&mut self, address: u32, value: u8) {
		match address {
			0x4800..=0x4FFF => {
				self.chip_ram[self.chip_address as usize] = value;
				self.increment_chip_address();
			},
			0x5000..=0x57FF => {
				self.irq_counter = (self.irq_counter & 0x7F00) | value as u16;
				self.irq_pending = false;
			},
			0x5800..=0x5FFF => {
				self.irq_counter = (self.irq_counter & 0xFF) | ((value as u16 & 0x7F) << 8);
				self.irq_enabled = value & 0x80 != 0;
				self.irq_pending = false;
			},
			0x8000..=0xDFFF => {
				self.chr_and_nt[((address - 0x8000) / 0x800) as usize] = value;
			},
			0xE000..=0xF7FF => {
				// Keep all bits: E800 bits 6/7 control pattern-table CIRAM.
				// E000 bit 6 is sound-disable; audio synthesis is not provided.
				self.prg[((address - 0xE000) / 0x800) as usize] = value;
			},
			0xF800..=0xFFFF => {
				self.ram_control = value;
				self.chip_address = value & 0x7F;
				self.chip_auto_increment = value & 0x80 != 0;
			},
			_ => {}
		}
	}

	fn handles_extended_write(&self, address: u32) -> bool {
		address >= 0x4800 && address <= 0x5FFF
	}

	fn load_extended(&mut self, address: u32) -> Option<u8> {
		match address {
			0x4800..=0x4FFF => {
				let value = self.chip_ram[self.chip_address as usize];
				self.increment_chip_address();
				Some(value)
			},
			0x5000..=0x57FF => Some(self.irq_counter as u8),
			0x5800..=0x5FFF => Some((self.irq_counter >> 8) as u8 |
				if self.irq_enabled { 0x80 } else { 0 }),
			_ => None
		}
	}

	fn prg_ram_write_enabled(&self, address: u32) -> bool {
		if address < 0x6000 || address > 0x7FFF { return true; }
		self.ram_control & 0xF0 == 0x40 &&
			self.ram_control & (1 << ((address - 0x6000) / 0x800)) == 0
	}

	fn clock_cpu_cycle(&mut self) {
		if self.irq_enabled {
			if self.irq_counter < 0x7FFF { self.irq_counter += 1; }
			if self.irq_counter == 0x7FFF { self.irq_pending = true; }
		}
	}

	fn cpu_irq_pending(&self) -> bool { self.irq_pending }
	fn drive_irq_counter(&mut self) -> bool { false } // Not a PPU scanline IRQ.
	fn has_mirroring_type(&self) -> bool { false } // map_ppu owns all four pages.
	fn mirroring_type(&self) -> Mirrorings { Mirrorings::Vertical } // Unused.
}

#[cfg(test)]
mod tests {
	use super::*;

	fn mapper(prg_16k: u8, chr_8k: u8) -> Namco163Mapper {
		let mut header = vec![0; 16];
		header[4] = prg_16k;
		header[5] = chr_8k;
		Namco163Mapper::new(&RomHeader::new(header))
	}

	#[test]
	fn three_independent_prg_windows_keep_last_bank_fixed() {
		let mut m = mapper(32, 32);
		assert_eq!(0, m.map(0x8000));
		assert_eq!(0x7E000, m.map(0xE000));
		m.store(0xE7FF, 0xC5);
		m.store(0xEFFF, 0xD2);
		m.store(0xF7FF, 0xFF);
		assert_eq!(0xA123, m.map(0x8123));
		assert_eq!(0x24123, m.map(0xA123));
		assert_eq!(0x7FFFF, m.map(0xDFFF));
		assert_eq!(0x7FFFF, m.map(0xFFFF));
	}

	#[test]
	fn unconnected_prg_and_chr_bank_bits_mirror_available_chips() {
		let mut m = mapper(2, 2);
		m.store(0xE000, 0x3F);
		m.store(0x8000, 0xDF);
		assert_eq!(0x7FFF, m.map(0x9FFF));
		assert_eq!(Some(MapperPpuAddress::Chr(0x3FFF)), m.map_ppu(0x3FF));
	}

	#[test]
	fn eight_chr_windows_and_four_nametable_pages_select_independently() {
		let mut m = mapper(32, 32);
		for slot in 0..12 {
			m.store(0x87FF + slot * 0x800, (0x10 + slot) as u8);
			assert_eq!(Some(MapperPpuAddress::Chr((0x10 + slot) * 0x400 + 0x123)),
				m.map_ppu((slot * 0x400 + 0x123) as u16));
		}
		assert_eq!(m.map_ppu(0x2123), m.map_ppu(0x3123));
		assert_eq!(m.map_ppu(0x2EFF), m.map_ppu(0x3EFF));
		assert_eq!(None, m.map_ppu(0x3F00));
	}

	#[test]
	fn pattern_ciram_switches_have_independent_low_and_high_controls() {
		let mut m = mapper(32, 32);
		m.store(0x8000, 0xE0);
		m.store(0xA000, 0xFF);
		assert_eq!(Some(MapperPpuAddress::Ciram(0x123)), m.map_ppu(0x123));
		assert_eq!(Some(MapperPpuAddress::Ciram(0x523)), m.map_ppu(0x1123));
		m.store(0xE800, 0x40);
		assert_eq!(Some(MapperPpuAddress::Chr(0x38123)), m.map_ppu(0x123));
		assert_eq!(Some(MapperPpuAddress::Ciram(0x523)), m.map_ppu(0x1123));
		m.store(0xE800, 0x80);
		assert_eq!(Some(MapperPpuAddress::Ciram(0x123)), m.map_ppu(0x123));
		assert_eq!(Some(MapperPpuAddress::Chr(0x3FD23)), m.map_ppu(0x1123));
	}

	#[test]
	fn nametable_ciram_is_not_disabled_by_pattern_switches() {
		let mut m = mapper(32, 32);
		m.store(0xE800, 0xC0);
		for slot in 0..4 {
			m.store(0xC000 + slot * 0x800, (0xE0 + slot) as u8);
			assert_eq!(Some(MapperPpuAddress::Ciram(((slot & 1) * 0x400 + 0x155) as u16)),
				m.map_ppu((0x2000 + slot * 0x400 + 0x155) as u16));
		}
	}

	#[test]
	fn all_write_protection_values_and_each_two_kib_window() {
		let mut m = mapper(2, 1);
		assert!(!m.prg_ram_write_enabled(0x6000));
		for control in 0..=255 {
			m.store(0xF800, control as u8);
			for slot in 0..4 {
				let expected = control & 0xF0 == 0x40 && control & (1 << slot) == 0;
				assert_eq!(expected, m.prg_ram_write_enabled(0x6000 + slot * 0x800));
				assert_eq!(expected, m.prg_ram_write_enabled(0x67FF + slot * 0x800));
			}
		}
	}

	#[test]
	fn chip_ram_addresses_and_mirrored_data_port_read_write() {
		let mut m = mapper(2, 1);
		m.store(0xFFFF, 0x80);
		for value in 0..128 { m.store(0x4FFF, value); }
		m.store(0xF800, 0x80);
		for value in 0..128 { assert_eq!(Some(value), m.load_extended(0x4800)); }
		m.store(0xF800, 0x25);
		assert_eq!(Some(0x25), m.load_extended(0x4FFE));
		assert_eq!(Some(0x25), m.load_extended(0x4801));
		m.store(0x4800, 0xAB);
		assert_eq!(Some(0xAB), m.load_extended(0x4800));
	}

	#[test]
	fn chip_auto_increment_saturates_and_does_not_change_wram_protection() {
		let mut m = mapper(2, 1);
		m.store(0xF800, 0x40);
		m.store(0x4800, 0x42);
		assert!(m.prg_ram_write_enabled(0x6000));
		m.store(0xF800, 0xFF);
		m.store(0x4800, 0x77);
		m.store(0x4800, 0x88);
		assert_eq!(Some(0x88), m.load_extended(0x4800));
		assert!(!m.prg_ram_write_enabled(0x6000));
		m.store(0xF800, 0x00);
		assert_eq!(Some(0), m.load_extended(0x4800));
		m.store(0xF800, 0x40);
		assert_eq!(Some(0x42), m.load_extended(0x4800));
	}

	#[test]
	fn irq_counts_cpu_cycles_saturates_and_reads_do_not_acknowledge() {
		let mut m = mapper(2, 1);
		m.store(0x57FF, 0xFD);
		m.store(0x5FFF, 0xFF);
		m.clock_cpu_cycle();
		assert!(!m.cpu_irq_pending());
		assert_eq!(Some(0xFE), m.load_extended(0x5000));
		m.clock_cpu_cycle();
		assert!(m.cpu_irq_pending());
		for _ in 0..8 { m.clock_cpu_cycle(); }
		assert_eq!(Some(0xFF), m.load_extended(0x5000));
		assert_eq!(Some(0xFF), m.load_extended(0x5800));
		assert!(m.cpu_irq_pending());
		assert!(!m.drive_irq_counter());
		assert!(m.cpu_irq_pending());
	}

	#[test]
	fn either_irq_write_acknowledges_and_disable_stops_counter() {
		let mut m = mapper(2, 1);
		m.store(0x5000, 0xFF);
		m.store(0x5800, 0xFF);
		m.clock_cpu_cycle();
		assert!(m.cpu_irq_pending());
		m.store(0x5000, 0xFE);
		assert!(!m.cpu_irq_pending());
		m.clock_cpu_cycle();
		assert!(m.cpu_irq_pending());
		m.store(0x5800, 0x7F);
		assert!(!m.cpu_irq_pending());
		m.store(0x5000, 0x23);
		for _ in 0..500 { m.clock_cpu_cycle(); }
		assert_eq!(Some(0x23), m.load_extended(0x5000));
		assert_eq!(Some(0x7F), m.load_extended(0x5800));
		assert!(!m.cpu_irq_pending());
	}

	#[test]
	fn extended_bus_is_claimed_only_for_three_documented_registers() {
		let mut m = mapper(2, 1);
		for address in [0x4020, 0x47FF, 0x6000, 0x7FFF, 0x8000].iter() {
			assert!(!m.handles_extended_write(*address));
			assert_eq!(None, m.load_extended(*address));
		}
		for address in [0x4800, 0x4FFF, 0x5000, 0x57FF, 0x5800, 0x5FFF].iter() {
			assert!(m.handles_extended_write(*address));
			assert!(m.load_extended(*address).is_some());
		}
	}
}
