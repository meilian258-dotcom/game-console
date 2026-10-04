
#include "np_state.h"
void np_gamepad(np_cursor *c) {
  int i;
  for(i=0;i<MAX_DEVICES;i++) {
    NP(gamepad[i].State); NP(gamepad[i].Counter);
    NP(gamepad[i].Timeout); NP(gamepad[i].Latency);
  }
  NP(flipflop); NP(latch);
}
