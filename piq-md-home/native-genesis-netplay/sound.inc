
#include "np_state.h"
void np_sound(np_cursor *c) {
  uint32_t used=(uint32_t)(fm_ptr-fm_buffer);
  NP(used);
  if(c->mode==2 && c->ok) memcpy(&used,c->data+c->pos-sizeof(used),sizeof(used));
  if(used>sizeof(fm_buffer)/sizeof(fm_buffer[0])) { c->ok=0; return; }
  NP(fm_buffer); NP(fm_last);
  NP(fm_cycles_ratio); NP(fm_cycles_start); NP(fm_cycles_count); NP(fm_cycles_busy);
#ifdef HAVE_YM3438_CORE
  NP(ym3438); NP(ym3438_accm); NP(ym3438_sample); NP(ym3438_cycles);
#endif
  /* OPLL is SMS-only (and contains a patch-ROM pointer). This core's snapshot
   * entrypoints reject non-MD hardware, so it is deliberately not a domain. */
  if(c->mode==1 && c->ok) fm_ptr=fm_buffer+used;
}
