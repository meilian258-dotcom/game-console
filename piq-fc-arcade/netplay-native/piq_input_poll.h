/* SPDX-License-Identifier: GPL-3.0-or-later
 * One sample before Netplay PRE_FRAME, after the previous display wait.
 * Never sample during rollback: RetroArch replays its recorded inputs itself.
 * Only the authenticated local Java owner can answer; no new public socket.
 */
#ifndef PIQ_INPUT_POLL_H
#define PIQ_INPUT_POLL_H
static bool piq_poll_latest_input(void)
{
   uint32_t request=htonl(0x504e5049), input[PIQ_INPUT_WORDS]; /* PNPI */
   unsigned i;
   if(piq_dead || !piq_connect())goto failed;
   if(!piq_transfer(piq_ipc,&request,sizeof(request),true)
         ||!piq_transfer(piq_ipc,input,sizeof(input),false))goto failed;
   piq_input=ntohl(input[0])&65535;
   piq_gun_x=(int16_t)(ntohl(input[1])>>16);
   piq_gun_y=(int16_t)ntohl(input[1]);
   piq_gun_flags=ntohl(input[2])&3;
   for(i=0;i<4;i++)piq_cabinet_input[i]=ntohl(input[3+i])&4095;
#if PIQ_INPUT_WORDS == 8
   /* Do not erase a queued capture while Netplay is temporarily stalled. */
   if(ntohl(input[7]))piq_save_request=ntohl(input[7]);
#endif
   return true;
failed:
   piq_dead=true;
   return false;
}
#endif
