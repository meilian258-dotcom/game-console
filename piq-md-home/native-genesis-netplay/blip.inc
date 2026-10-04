
#include "np_state.h"
/* Full resampler history, not GX fast_savestates' one internal 16-sample slot.
 * No buffer addresses are written; the receiver uses its own allocation. */
void np_blip(np_cursor *c, void *opaque) {
  blip_t *m=(blip_t*)opaque;
  int size=m ? m->size : -1, recorded=size;
  NP(recorded);
  if(c->mode==2 && c->ok) memcpy(&recorded,c->data+c->pos-sizeof(recorded),sizeof(recorded));
  if(!m || recorded!=size || size<0 || size>48000) { c->ok=0; return; }
  NP(m->factor); NP(m->offset); NP(m->integrator);
#ifdef BLIP_MONO
  np_field(c,SAMPLES(m),(size+buf_extra)*sizeof(buf_t));
#else
  np_field(c,m->buffer[0],(size+buf_extra)*sizeof(buf_t));
  np_field(c,m->buffer[1],(size+buf_extra)*sizeof(buf_t));
#endif
}
