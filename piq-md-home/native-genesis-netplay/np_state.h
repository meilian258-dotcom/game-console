/* PIQ GX Netplay snapshot extension, based on Genesis Plus GX c2838c7.
 * Distributed under the same non-commercial source-distribution conditions
 * as Genesis Plus GX; see upstream LICENSE.txt and each original source file.
 * A distinct snapshot schema: never used for the unchanged MEDIA/private core. */
#ifndef PIQ_NP_STATE_H
#define PIQ_NP_STATE_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>
typedef struct { unsigned char *data; size_t pos, limit; int mode, ok; } np_cursor;
/* mode 0=write, 1=restore, 2=validate without mutating emulator state. */
static void np_field(np_cursor *c, void *field, size_t n) {
  if (!c->ok || c->pos > c->limit || n > c->limit - c->pos) { c->ok=0; return; }
  if (c->mode == 0) memcpy(c->data+c->pos,field,n);
  else if (c->mode == 1) memcpy(field,c->data+c->pos,n);
  c->pos+=n;
}
#define NP(v) np_field(c,&(v),sizeof(v))
void np_system(np_cursor *c);
void np_sound(np_cursor *c);
void np_gamepad(np_cursor *c);
void np_i2c(np_cursor *c);
void np_spi(np_cursor *c);
void np_93c(np_cursor *c);
void np_blip(np_cursor *c, void *blip);
#endif
