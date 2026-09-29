// SPDX-License-Identifier: GPL-3.0-or-later
// Experimental Windows hardware-libretro host. No network listener; pipes belong to its parent.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <GL/gl.h>
#include <fcntl.h>
#include <io.h>
#include <cstdio>
#include <cstdint>
#include <atomic>
#include <thread>
#include <mutex>
#include <vector>
#include <string>
#include <chrono>
#include <algorithm>
#include <cstring>
#include "libretro.h"

static constexpr unsigned W=800,H=600;
static std::atomic<bool> alive{true},paused{false};
static std::atomic<unsigned> pad{0};
static std::atomic<int> px{-32768},py{-32768},buttons{0},device{1};
static retro_hw_render_callback hw{};
static std::string systemDir,saveDir;
static GLuint fbo=0,texture=0,depth=0;
static HMODULE glModule;
static std::vector<int16_t> pcm;
static bool drawn=false;
static std::mutex keysMutex;
struct Key {bool down; unsigned key,character;};
static std::vector<Key> keys;
static retro_keyboard_callback keyboard{};
static uint64_t sequence=0;

static void fail(const char* msg) { fprintf(stderr,"PVZ_HOST: %s\n",msg); fflush(stderr); ExitProcess(2); }
static retro_proc_address_t proc(const char* name) {
    PROC p=wglGetProcAddress(name);
    if(!p || p==(PROC)1 || p==(PROC)2 || p==(PROC)3 || p==(PROC)-1) p=GetProcAddress(glModule,name);
    return reinterpret_cast<retro_proc_address_t>(p);
}
static uintptr_t framebuffer() {return fbo;}
template<class T> static T glfun(const char* name) {auto p=proc(name);if(!p)fail(name);return reinterpret_cast<T>(p);}
using Gen=void(APIENTRY*)(GLsizei,GLuint*);
using Bind=void(APIENTRY*)(GLenum,GLuint);
using Attach=void(APIENTRY*)(GLenum,GLenum,GLenum,GLuint,GLint);
static Bind bindFramebuffer;
static void initGL() {
    glModule=LoadLibraryW(L"opengl32.dll");
    WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"PIQPvZHidden";wc.style=CS_OWNDC;
    RegisterClassW(&wc);
    HWND win=CreateWindowW(wc.lpszClassName,L"PIQ PvZ worker",WS_POPUP,0,0,W,H,nullptr,nullptr,wc.hInstance,nullptr);
    HDC dc=GetDC(win);PIXELFORMATDESCRIPTOR p{};p.nSize=sizeof(p);p.nVersion=1;p.dwFlags=PFD_DRAW_TO_WINDOW|PFD_SUPPORT_OPENGL|PFD_DOUBLEBUFFER;p.iPixelType=PFD_TYPE_RGBA;p.cColorBits=32;p.cDepthBits=24;p.cStencilBits=8;
    int format=ChoosePixelFormat(dc,&p);
    if(!format||!SetPixelFormat(dc,format,&p))fail("OpenGL pixel format unavailable");
    HGLRC context=wglCreateContext(dc);if(!context||!wglMakeCurrent(dc,context))fail("OpenGL context unavailable");
    fprintf(stderr,"GL: %s / %s\n",glGetString(GL_VERSION),glGetString(GL_RENDERER));
    auto gen=glfun<Gen>("glGenFramebuffers");bindFramebuffer=glfun<Bind>("glBindFramebuffer");
    gen(1,&fbo);bindFramebuffer(0x8D40,fbo);
    glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glfun<Attach>("glFramebufferTexture2D")(0x8D40,0x8CE0,GL_TEXTURE_2D,texture,0);
    glfun<Gen>("glGenRenderbuffers")(1,&depth);glfun<Bind>("glBindRenderbuffer")(0x8D41,depth);
    glfun<void(APIENTRY*)(GLenum,GLenum,GLsizei,GLsizei)>("glRenderbufferStorage")(0x8D41,0x88F0,W,H);
    glfun<void(APIENTRY*)(GLenum,GLenum,GLenum,GLuint)>("glFramebufferRenderbuffer")(0x8D40,0x821A,0x8D41,depth);
    if(glfun<GLenum(APIENTRY*)(GLenum)>("glCheckFramebufferStatus")(0x8D40)!=0x8CD5)fail("Incomplete framebuffer");
    glViewport(0,0,W,H);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
}
static bool environment(unsigned cmd,void* data) {
    switch(cmd) {
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto* request=(retro_hw_render_callback*)data;
            if(request->context_type!=RETRO_HW_CONTEXT_OPENGL)return false;
            request->get_current_framebuffer=framebuffer;request->get_proc_address=proc;hw=*request;return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char**)data=systemDir.c_str();return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)data=saveDir.c_str();return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:return *(retro_pixel_format*)data==RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data=true;return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE:fprintf(stderr,"CORE: %s\n",((retro_message*)data)->msg);return true;
        case RETRO_ENVIRONMENT_SHUTDOWN:alive=false;return true;
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:keyboard=*(retro_keyboard_callback*)data;return true;
        // No keyboard advertised: core fills Player on first launch. Explicit text events remain usable.
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:*(uint64_t*)data=(1ULL<<RETRO_DEVICE_JOYPAD)|(1ULL<<RETRO_DEVICE_POINTER);return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:*(unsigned*)data=0;return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*(bool*)data=false;return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* v=(retro_variable*)data;
            if(!strcmp(v->key,"pvz_draw_cursor"))v->value="always";
            else if(!strcmp(v->key,"pvz_cursor_speed"))v->value="6";
            else if(!strcmp(v->key,"pvz_gamepad_deadzone"))v->value="25%";
            else if(!strcmp(v->key,"pvz_cheat_keys"))v->value="disabled";
            else return false;
            return true;
        }
        default:return false;
    }
}
static void video(const void* data,unsigned width,unsigned height,size_t) {
    if(width!=W||height!=H)fail("Unexpected video dimensions");
    if(data==RETRO_HW_FRAME_BUFFER_VALID)drawn=true;
}
static void audio(int16_t a,int16_t b) {if(pcm.size()<16384){pcm.push_back(a);pcm.push_back(b);}}
static size_t batch(const int16_t* data,size_t n) {if(n>8192||pcm.size()+n*2>16384)fail("Audio limit");pcm.insert(pcm.end(),data,data+n*2);return n;}
static void poll() {}
static int16_t input(unsigned port,unsigned d,unsigned index,unsigned id) {
    if(port||index)return 0;
    if(d==RETRO_DEVICE_JOYPAD) return id==RETRO_DEVICE_ID_JOYPAD_MASK?(int16_t)pad.load():id<16?(pad.load()>>id)&1:0;
    if(d==RETRO_DEVICE_POINTER) {
        if(id==RETRO_DEVICE_ID_POINTER_X)return px;
        if(id==RETRO_DEVICE_ID_POINTER_Y)return py;
        if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return buttons.load()&1;
        if(id==RETRO_DEVICE_ID_POINTER_COUNT)return 1;
    }
    if(d==RETRO_DEVICE_MOUSE&&id==RETRO_DEVICE_ID_MOUSE_RIGHT)return (buttons.load()>>1)&1;
    return 0;
}
static void reader() {
    // Fixed 24-byte local commands, never deserialize arbitrary lengths.
    uint32_t m[6];
    while(fread(m,sizeof(m),1,stdin)==1&&alive) {
        if(m[0]!=0x315A5650)break;
        if(m[1]==0)break;
        if(m[1]==1){pad=m[2]&65535;px=(int16_t)m[3];py=(int16_t)m[4];buttons=m[5]&3;device=(m[5]&4)?6:1;}
        if(m[1]==2){paused=m[2]!=0;pad=0;buttons=0;}
        if(m[1]==3){std::lock_guard<std::mutex> g(keysMutex);if(keys.size()<128)keys.push_back({m[2]!=0,m[3],m[4]});}
    }
    alive=false;
}
static void output(const void* p,size_t n) {if(fwrite(p,1,n,stdout)!=n)alive=false;}
int wmain(int argc,wchar_t** argv) {
    if(argc!=4)fail("Expected core, data directory, save directory");
    auto utf8=[](const wchar_t* w){int n=WideCharToMultiByte(CP_UTF8,0,w,-1,nullptr,0,nullptr,nullptr);std::string s(n,0);WideCharToMultiByte(CP_UTF8,0,w,-1,s.data(),n,nullptr,nullptr);s.pop_back();return s;};
    systemDir=utf8(argv[2]);saveDir=utf8(argv[3]);
    _setmode(_fileno(stdin),_O_BINARY);_setmode(_fileno(stdout),_O_BINARY);
    setvbuf(stdout,nullptr,_IONBF,0); // One complete packet write; no per-row pipe writes.
    SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32|LOAD_LIBRARY_SEARCH_USER_DIRS);
    HMODULE core=LoadLibraryExW(argv[1],nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);if(!core)fail("Core DLL could not be loaded");
#define API(name) auto name=reinterpret_cast<decltype(&::name)>(GetProcAddress(core,#name));if(!name)fail(#name)
    API(retro_api_version);API(retro_set_environment);API(retro_set_video_refresh);API(retro_set_audio_sample);API(retro_set_audio_sample_batch);API(retro_set_input_poll);API(retro_set_input_state);API(retro_init);API(retro_load_game);API(retro_run);API(retro_unload_game);API(retro_deinit);API(retro_set_controller_port_device);
    if(retro_api_version()!=RETRO_API_VERSION)fail("Core API mismatch");
    initGL();retro_set_environment(environment);retro_init();retro_set_video_refresh(video);retro_set_audio_sample(audio);retro_set_audio_sample_batch(batch);retro_set_input_poll(poll);retro_set_input_state(input);
    std::string content=systemDir+"/main.pak";retro_game_info game{};game.path=content.c_str();
    if(!retro_load_game(&game))fail("Core rejected main.pak; see core message");
    if(!hw.context_reset)fail("Core has no GL reset callback");hw.context_reset();
    retro_set_controller_port_device(0,RETRO_DEVICE_JOYPAD);
    std::thread(reader).detach();
    std::vector<uint8_t> pixels(W*H*4);
    std::vector<uint8_t> packet(24+W*H*4+32768);
    auto next=std::chrono::steady_clock::now();int priorDevice=1;
    auto metricStart=next;double coreMs=0,readMs=0,pipeMs=0;unsigned metricRuns=0,metricPictures=0;
    while(alive) {
        MSG msg;while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}
        if(paused){std::this_thread::sleep_for(std::chrono::milliseconds(10));next=std::chrono::steady_clock::now();continue;}
        int dev=device.load();if(dev!=priorDevice){retro_set_controller_port_device(0,dev);priorDevice=dev;}
        {std::vector<Key> events;{std::lock_guard<std::mutex> g(keysMutex);events.swap(keys);}for(auto e:events)if(keyboard.callback)keyboard.callback(e.down,e.key,e.character,0);}
        auto began=std::chrono::steady_clock::now();
        pcm.clear();drawn=false;retro_run();++sequence;
        auto ran=std::chrono::steady_clock::now();
        bool capture=drawn;
        if(capture){bindFramebuffer(0x8D40,fbo);glPixelStorei(GL_PACK_ALIGNMENT,1);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());}
        uint32_t header[]={0x315A5650,(uint32_t)sequence,capture?W:0,capture?H:0,capture?(uint32_t)pixels.size():0,(uint32_t)pcm.size()};memcpy(packet.data(),header,24);size_t offset=24;
        if(capture){
            for(unsigned y=0;y<H;y++)memcpy(packet.data()+24+y*W*4,pixels.data()+(hw.bottom_left_origin?H-1-y:y)*W*4,W*4);
            // Preserve the v1 opaque screen contract without a per-pixel Java upload loop.
            for(size_t i=27;i<24+pixels.size();i+=4)packet[i]=255;
            offset+=pixels.size();metricPictures++;
        }
        if(!pcm.empty()){memcpy(packet.data()+offset,pcm.data(),pcm.size()*2);offset+=pcm.size()*2;}
        auto captured=std::chrono::steady_clock::now();output(packet.data(),offset);auto sent=std::chrono::steady_clock::now();
        coreMs+=std::chrono::duration<double,std::milli>(ran-began).count();readMs+=std::chrono::duration<double,std::milli>(captured-ran).count();pipeMs+=std::chrono::duration<double,std::milli>(sent-captured).count();metricRuns++;
        double elapsed=std::chrono::duration<double>(sent-metricStart).count();
        if(elapsed>=5){fprintf(stderr,"PVZ_PERF calls=%.1f/s frames=%.1f/s core=%.3fms capture=%.3fms pipe=%.3fms\n",metricRuns/elapsed,metricPictures/elapsed,coreMs/metricRuns,readMs/metricRuns,pipeMs/metricRuns);fflush(stderr);metricStart=sent;metricRuns=metricPictures=0;coreMs=readMs=pipeMs=0;}
        next+=std::chrono::microseconds(16667);auto now=std::chrono::steady_clock::now();if(next<now-std::chrono::milliseconds(200))next=now;std::this_thread::sleep_until(next);
    }
    retro_unload_game();if(hw.context_destroy)hw.context_destroy();retro_deinit();
    fprintf(stderr,"PVZ_HOST: clean shutdown\n");fflush(stderr);return 0;
}
