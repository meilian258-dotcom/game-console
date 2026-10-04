
#include "np_state.h"
void np_system(np_cursor *c) {
  /* Explicit scalar/array fields: do not export memory maps, callback pointers
   * or jmp_buf. m68k_run establishes a fresh address-error setjmp each call. */
  NP(m68k.poll); NP(m68k.cycles); NP(m68k.refresh_cycles); NP(m68k.cycle_end);
  NP(m68k.dar); NP(m68k.pc); NP(m68k.prev_pc); NP(m68k.prev_dr); NP(m68k.prev_ar);
  NP(m68k.sp); NP(m68k.ir); NP(m68k.t1_flag); NP(m68k.s_flag); NP(m68k.x_flag);
  NP(m68k.n_flag); NP(m68k.not_z_flag); NP(m68k.v_flag); NP(m68k.c_flag);
  NP(m68k.int_mask); NP(m68k.int_level); NP(m68k.stopped); NP(m68k.pref_addr);
  NP(m68k.pref_data); NP(m68k.instr_mode); NP(m68k.run_mode); NP(m68k.aerr_enabled);
  NP(m68k.aerr_address); NP(m68k.aerr_write_mode); NP(m68k.aerr_fc);
  NP(m68k.tracing); NP(m68k.address_space);
#ifdef M68K_OVERCLOCK_SHIFT
  NP(m68k.cycle_ratio);
#endif
  NP(mcycles_vdp); NP(SVP_cycles); NP(system_clock);
  NP(bitmap.viewport); NP(pause_b); NP(eq); NP(llp); NP(rrp);
  NP(input.system); NP(input.dev); NP(input.pad); NP(input.analog);
  NP(input.x_offset); NP(input.y_offset);
  NP(sram.detected); NP(sram.on); NP(sram.type); NP(sram.start); NP(sram.end);
  NP(sram.crc); NP(sram.sram);
  np_i2c(c); np_spi(c); np_93c(c);
  np_gamepad(c); np_sound(c);
  np_blip(c,snd.blips[0]);
}
