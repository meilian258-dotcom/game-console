
#include "np_state.h"
void np_i2c(np_cursor *c) {
  NP(eeprom_i2c.sda); NP(eeprom_i2c.scl); NP(eeprom_i2c.old_sda); NP(eeprom_i2c.old_scl);
  NP(eeprom_i2c.cycles); NP(eeprom_i2c.rw); NP(eeprom_i2c.device_address);
  NP(eeprom_i2c.word_address); NP(eeprom_i2c.buffer); NP(eeprom_i2c.state);
  NP(eeprom_i2c.spec.address_bits); NP(eeprom_i2c.spec.size_mask); NP(eeprom_i2c.spec.pagewrite_mask);
  NP(eeprom_i2c.scl_in_bit); NP(eeprom_i2c.sda_in_bit); NP(eeprom_i2c.sda_out_bit);
}
