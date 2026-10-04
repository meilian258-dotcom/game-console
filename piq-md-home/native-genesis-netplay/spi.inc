
#include "np_state.h"
void np_spi(np_cursor *c) {
  NP(spi_eeprom.cs); NP(spi_eeprom.clk); NP(spi_eeprom.out); NP(spi_eeprom.status);
  NP(spi_eeprom.opcode); NP(spi_eeprom.buffer); NP(spi_eeprom.addr);
  NP(spi_eeprom.cycles); NP(spi_eeprom.state);
}
