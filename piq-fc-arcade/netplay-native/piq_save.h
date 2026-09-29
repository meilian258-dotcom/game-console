/* SPDX-License-Identifier: GPL-3.0-or-later
 * Included in runloop.c, on the emulation owner only. Not callable by native peers.
 * A seed is consumed before NETPLAY_INIT; capture is after POST_FRAME/rollback,
 * never from the video callback while retro_run() is still on the stack.
 */
#ifndef PIQ_NETPLAY_SAVE_H
#define PIQ_NETPLAY_SAVE_H
#define PIQ_SAVE_STATE_MAX (16u*1024u*1024u)
#define PIQ_SAVE_RAM_MAX (4u*1024u*1024u)
#define PIQ_SAVE_RTC_MAX 65536u

static bool piq_save_host(void)
{
   const char *role=getenv("PIQ_SAVE_HOST");
   return role && !strcmp(role,"1");
}
static bool piq_restore_seed(runloop_state_t *st)
{
   FILE *file;
   uint32_t sizes[3];
   unsigned char *bytes=NULL;
   size_t ns,nr,nc,total;
   bool ok=false;
   const char *seed=getenv("PIQ_SAVE_SEED");
   struct retro_core_t *core=&st->current_core;
   if(!seed)return true;
   /* No arbitrary path or peer-provided state loader. Java creates this exact file. */
   if(!piq_save_host() || strcmp(seed,"checkpoint.seed"))return false;
   file=fopen("checkpoint.seed","rb");
   if(!file)return false;
   if(fread(sizes,1,sizeof(sizes),file)!=sizeof(sizes))goto done;
   ns=ntohl(sizes[0]);nr=ntohl(sizes[1]);nc=ntohl(sizes[2]);
   if(!ns||ns>PIQ_SAVE_STATE_MAX||nr>PIQ_SAVE_RAM_MAX||nc>PIQ_SAVE_RTC_MAX)goto done;
   total=ns+nr+nc;
   if(core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM)!=nr
         ||core->retro_get_memory_size(RETRO_MEMORY_RTC)!=nc)goto done;
   if((nr&&!core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM))
         ||(nc&&!core->retro_get_memory_data(RETRO_MEMORY_RTC)))goto done;
   bytes=(unsigned char*)malloc(total);
   if(!bytes||fread(bytes,1,total,file)!=total||fgetc(file)!=EOF)goto done;
   if(nr)memcpy(core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM),bytes+ns,nr);
   if(nc)memcpy(core->retro_get_memory_data(RETRO_MEMORY_RTC),bytes+ns+nr,nc);
   /* Exact state is authoritative, not an SRAM overlay after resume. */
   ok=core->retro_unserialize(bytes,ns);
done:
   if(bytes)free(bytes);
   fclose(file);
   if(!ok)RARCH_ERR("[PIQ save] Checkpoint rejected; refusing a fresh overwrite.\n");
   else RARCH_LOG("[PIQ save] Checkpoint restored before Netplay initialization.\n");
   return ok;
}
static void piq_capture_save(runloop_state_t *st)
{
   struct retro_core_t *core=&st->current_core;
   uint32_t header[7];
   size_t ns=0,nr=0,nc=0;
   void *state=NULL,*ram=NULL,*rtc=NULL;
   bool ok=false;
   ++piq_save_frame;
   if(!piq_save_request)return;
   header[0]=htonl(0x504e5356); /* PNSV, followed by request, completed frames, lengths */
   header[1]=htonl(piq_save_request);
   header[2]=htonl((uint32_t)(piq_save_frame>>32));header[3]=htonl((uint32_t)piq_save_frame);
   piq_save_request=0;
   if(!piq_save_host())goto reply;
   ns=core->retro_serialize_size();
   nr=core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
   nc=core->retro_get_memory_size(RETRO_MEMORY_RTC);
   if(!ns||ns>PIQ_SAVE_STATE_MAX||nr>PIQ_SAVE_RAM_MAX||nc>PIQ_SAVE_RTC_MAX)goto reply;
   ram=core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);rtc=core->retro_get_memory_data(RETRO_MEMORY_RTC);
   if((nr&&!ram)||(nc&&!rtc))goto reply;
   state=malloc(ns);
   if(!state||!core->retro_serialize(state,ns))goto reply;
   ok=true;
reply:
   if(!ok)ns=nr=nc=0;
   header[4]=htonl((uint32_t)ns);header[5]=htonl((uint32_t)nr);header[6]=htonl((uint32_t)nc);
   if(!piq_transfer(piq_ipc,header,sizeof(header),true)
         ||(ns&&!piq_transfer(piq_ipc,state,(unsigned)ns,true))
         ||(nr&&!piq_transfer(piq_ipc,ram,(unsigned)nr,true))
         ||(nc&&!piq_transfer(piq_ipc,rtc,(unsigned)nc,true)))piq_dead=true;
   if(state)free(state);
}
#endif
