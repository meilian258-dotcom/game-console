
#include "np_state.h"
void np_93c(np_cursor *c) {
  NP(eeprom_93c.enabled); NP(eeprom_93c.cs); NP(eeprom_93c.clk); NP(eeprom_93c.data);
  NP(eeprom_93c.cycles); NP(eeprom_93c.we); NP(eeprom_93c.opcode);
  NP(eeprom_93c.buffer); NP(eeprom_93c.state);
}
