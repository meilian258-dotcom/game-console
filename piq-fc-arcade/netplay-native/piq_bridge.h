/* PIQ RetroArch local frontend bridge. GPL-3.0-or-later.
 * Only the local Java owner opens these sockets. No LAN/public listener here.
 * Video/audio exchange is bounded; all emulation remains in RetroArch.
 */
#ifndef PIQ_RA_BRIDGE_H
#define PIQ_RA_BRIDGE_H
#include <winsock2.h>
#include <windows.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

static SOCKET piq_ipc = INVALID_SOCKET;
static unsigned piq_input;
static unsigned char piq_pcm[32768];
static unsigned piq_pcm_size;
static bool piq_dead;
static bool piq_fast;
static bool piq_rgb32;
static unsigned piq_rate=44100;
static double piq_fps=60.0988138974405;
static LARGE_INTEGER piq_frequency,piq_deadline;
static HANDLE piq_timer;
/* A display clock, not a second emulation scheduler. RetroArch disables it
 * through the driver's nonblock callback while catching up/rolling forward. */
static void piq_vblank(void)
{
   LARGE_INTEGER now,due;
   LONGLONG period;
   if(!piq_frequency.QuadPart){QueryPerformanceFrequency(&piq_frequency);piq_timer=CreateWaitableTimerExW(NULL,NULL,2,TIMER_ALL_ACCESS);}
   QueryPerformanceCounter(&now);
   period=(LONGLONG)(piq_frequency.QuadPart/piq_fps);
   if(piq_fast){piq_deadline.QuadPart=0;return;}
   if(!piq_deadline.QuadPart||now.QuadPart>piq_deadline.QuadPart+period*4)piq_deadline.QuadPart=now.QuadPart;
   piq_deadline.QuadPart+=period;
   while(now.QuadPart<piq_deadline.QuadPart){
      due.QuadPart=-(piq_deadline.QuadPart-now.QuadPart)*10000000/piq_frequency.QuadPart;
      if(piq_timer&&SetWaitableTimer(piq_timer,&due,0,NULL,NULL,FALSE))WaitForSingleObject(piq_timer,100);
      else Sleep(1);
      QueryPerformanceCounter(&now);
   }
}
static int piq_fds[16];
static unsigned char piq_roles[16];
static unsigned piq_fd_cursor;

static bool piq_transfer(SOCKET fd, void *data, unsigned size, bool sending)
{
   char *p = (char*)data;
   while (size) {
      int n = sending ? send(fd,p,(int)size,0) : recv(fd,p,(int)size,0);
      if(n<=0) return false;
      p+=n; size-=n;
   }
   return true;
}
static bool piq_connect(void)
{
   WSADATA wsa;
   struct sockaddr_in addr;
   const char *port=getenv("PIQ_IPC_PORT"), *token=getenv("PIQ_IPC_TOKEN");
   DWORD timeout=5000;
   int yes=1;
   if(piq_ipc!=INVALID_SOCKET) return true;
   if(!port||!token||strlen(token)!=32||WSAStartup(MAKEWORD(2,2),&wsa)) return false;
   memset(&addr,0,sizeof(addr)); addr.sin_family=AF_INET;
   addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK); addr.sin_port=htons((unsigned short)atoi(port));
   piq_ipc=socket(AF_INET,SOCK_STREAM,0);
   setsockopt(piq_ipc,IPPROTO_TCP,TCP_NODELAY,(char*)&yes,sizeof(yes));
   setsockopt(piq_ipc,SOL_SOCKET,SO_RCVTIMEO,(char*)&timeout,sizeof(timeout));
   setsockopt(piq_ipc,SOL_SOCKET,SO_SNDTIMEO,(char*)&timeout,sizeof(timeout));
   return !connect(piq_ipc,(struct sockaddr*)&addr,sizeof(addr)) && piq_transfer(piq_ipc,(void*)token,32,true);
}
static bool piq_frame(const void *pixels,unsigned width,unsigned height,unsigned pitch,uint64_t frame,double fps,float aspect)
{
   uint32_t header[8], input, row[1024];
   unsigned y,x;
   unsigned size=pixels&&width>0&&height>0&&width<=1024&&height<=1024&&pitch>=width*(piq_rgb32?4:2) ? width*height*4 : 0;
   if(fps>=40&&fps<=75)piq_fps=fps;
   if(piq_dead||!piq_connect()) { piq_dead=true; return false; }
   header[0]=htonl(0x504e5032); header[1]=htonl((uint32_t)frame);
   header[2]=htonl(size); header[3]=htonl(piq_pcm_size); header[4]=htonl(piq_rate);
   header[5]=htonl(width);header[6]=htonl(height);header[7]=htonl((uint32_t)((aspect>0&&aspect<8?aspect:4.0/3.0)*100000));
   if(!piq_transfer(piq_ipc,header,sizeof(header),true)) goto failed;
   if(size) for(y=0;y<height;y++) {
      const unsigned char *source=(const unsigned char*)pixels+y*pitch;
      if(!piq_rgb32)for(x=0;x<width;x++){unsigned p=((const uint16_t*)source)[x];unsigned r=(p>>11)&31,g=(p>>5)&63,b=p&31;row[x]=((r*255/31)<<16)|((g*255/63)<<8)|(b*255/31);}
      if(!piq_transfer(piq_ipc,(void*)(piq_rgb32?source:(const unsigned char*)row),width*4,true)) goto failed;
   }
   if(piq_pcm_size&&!piq_transfer(piq_ipc,piq_pcm,piq_pcm_size,true)) goto failed;
   piq_pcm_size=0;
   if(!piq_transfer(piq_ipc,&input,4,false)) goto failed;
   piq_input=ntohl(input)&65535;
   piq_vblank();
   return true;
failed:
   piq_dead=true; return false;
}
/* Host-side private preface: its Java relay supplies the current MC role.
 * This is NOT forwarded from the remote client. Retired peers cannot elevate
 * themselves by retaining an earlier Netplay password.
 */
static bool piq_accept_peer(int fd)
{
   unsigned char header[33];
   const char *token=getenv("PIQ_IPC_TOKEN");
   DWORD timeout=1000;
   u_long blocking=0;
   unsigned i;
   /* Accepted WinSock sockets inherit the listener's nonblocking flag. The
    * bounded local preface must wait for Java's write, not race WSAEWOULDBLOCK.
    * RetroArch sets nonblocking again immediately after this callback. */
   if(ioctlsocket((SOCKET)fd,FIONBIO,&blocking))return false;
   setsockopt((SOCKET)fd,SOL_SOCKET,SO_RCVTIMEO,(char*)&timeout,sizeof(timeout));
   if(!token||strlen(token)!=32||!piq_transfer((SOCKET)fd,header,33,false)
      ||memcmp(header,token,32)||header[32]>1) return false;
   for(i=0;i<16;i++) if(piq_fds[i]==fd) break;
   if(i==16) i=(piq_fd_cursor++)%16;
   piq_fds[i]=fd; piq_roles[i]=header[32];
   return true;
}
static bool piq_can_play(int fd)
{
   unsigned i; for(i=0;i<16;i++) if(piq_fds[i]==fd) return piq_roles[i]==1;
   return false;
}
static void *piq_audio_init(const char *device,unsigned rate,unsigned latency,unsigned block,unsigned *actual)
{ piq_rate=rate;if(actual) *actual=rate; return (void*)1; }
static ssize_t piq_audio_write(void *data,const void *samples,size_t length)
{
   if(length<=sizeof(piq_pcm)-piq_pcm_size) {
      memcpy(piq_pcm+piq_pcm_size,samples,length); piq_pcm_size+=(unsigned)length;
   }
   return (ssize_t)length;
}
static bool piq_audio_stop(void *d) { return true; }
static bool piq_audio_start(void *d,bool shutdown) { return true; }
static bool piq_audio_alive(void *d) { return !piq_dead; }
static void piq_audio_nonblock(void *d,bool nonblock) { }
static void piq_audio_free(void *d) { }
static bool piq_audio_float(void *d) { return false; }
#endif
