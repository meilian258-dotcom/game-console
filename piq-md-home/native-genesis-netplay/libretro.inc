/* Replaces only this independent core's libretro snapshot entrypoints. */
#include "../core/np_state.h"
#define PIQ_NP_SIZE (32u + STATE_SIZE + 524288u)
static const unsigned char piq_np_magic[16]={'P','I','Q','G','X','N','P',1,0,0,0,0,0,0,0,0};
static void np_frontend(np_cursor *c) {
  NP(vwidth); NP(vheight); NP(vwoffset); NP(bmdoffset); NP(max_width); NP(max_height);
  NP(vaspect_ratio); NP(retro_fps); NP(reg0_prev); NP(is_running);
#ifdef HAVE_OVERCLOCK
  NP(overclock_delay);
#endif
}
static uint64_t np_checksum(const unsigned char *p,size_t n) {
  uint64_t h=UINT64_C(14695981039346656037);
  while(n--) { h^=*p++; h*=UINT64_C(1099511628211); }
  return h;
}
size_t retro_serialize_size(void) { return PIQ_NP_SIZE; }
extern int8 fast_savestates;
bool retro_serialize(void *data,size_t size) {
  uint64_t checksum; uint32_t used=0, reserved=0;
  np_cursor c={(unsigned char*)data,32u+STATE_SIZE,size,0,1};
  if(size!=PIQ_NP_SIZE || system_hw!=SYSTEM_MD) return false;
  memset(data,0,size); memcpy(data,piq_np_magic,16);
  fast_savestates=0;
  if(state_save((unsigned char*)data+32)<=0) return false;
  np_system(&c); np_frontend(&c); if(!c.ok) return false;
  used=(uint32_t)c.pos; memcpy((unsigned char*)data+16,&used,4);
  memcpy((unsigned char*)data+20,&reserved,4);
  checksum=np_checksum((unsigned char*)data+32,size-32);
  memcpy((unsigned char*)data+24,&checksum,8);
  return true;
}
bool retro_unserialize(const void *data,size_t size) {
  uint64_t checksum=0; uint32_t used=0,reserved=0;
  np_cursor c={(unsigned char*)data,32u+STATE_SIZE,size,2,1};
  if(size!=PIQ_NP_SIZE || system_hw!=SYSTEM_MD || memcmp(data,piq_np_magic,16)) return false;
  memcpy(&used,(unsigned char*)data+16,4); memcpy(&reserved,(unsigned char*)data+20,4);
  memcpy(&checksum,(unsigned char*)data+24,8);
  if(reserved || checksum!=np_checksum((unsigned char*)data+32,size-32)) return false;
  np_system(&c); np_frontend(&c); if(!c.ok || used!=c.pos) return false;
  fast_savestates=0;
  if(!state_load((unsigned char*)data+32)) return false;
  c.pos=32u+STATE_SIZE; c.mode=1; np_system(&c); np_frontend(&c);
  /* Cold observers have never seen the guest's first resolution transition. */
  if(c.ok) update_geometry();
  return c.ok!=0;
}
