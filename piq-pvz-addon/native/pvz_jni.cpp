// SPDX-License-Identifier: GPL-3.0-or-later
// Experimental in-process host. Calls and WGL ownership stay on one Java worker.
// Never changes cwd, stdio, the JVM DLL search policy, or the MC render context.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <GL/gl.h>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <string>
#include <stdexcept>
#include <cstring>
#include <algorithm>
#include "libretro.h"

namespace {
constexpr unsigned W=800,H=600,VIDEO=W*H*4,AUDIO=32768;
using Gen=void(APIENTRY*)(GLsizei,GLuint*);
using Bind=void(APIENTRY*)(GLenum,GLuint);
struct Session {
    jlong token;DWORD owner=GetCurrentThreadId();
    HMODULE core=nullptr,gl=nullptr;HWND window=nullptr;HDC dc=nullptr;HGLRC context=nullptr;
    GLuint fbo=0,texture=0,depth=0;Bind bind=nullptr;bool ownsClass=false,initialized=false,loaded=false,reset=false;
    retro_hw_render_callback hw{};retro_keyboard_callback keyboard{};
    std::string system,save,error;std::atomic<bool> shutdown{false},wrongThread{false};
    bool drawn=false;unsigned pad=0;int x=-32768,y=-32768,buttons=0,device=1;
    std::vector<int16_t> pcm;
#define DECL(name) decltype(&::name) name=nullptr;
    DECL(retro_api_version) DECL(retro_set_environment) DECL(retro_set_video_refresh)
    DECL(retro_set_audio_sample) DECL(retro_set_audio_sample_batch) DECL(retro_set_input_poll)
    DECL(retro_set_input_state) DECL(retro_init) DECL(retro_load_game) DECL(retro_run)
    DECL(retro_unload_game) DECL(retro_deinit) DECL(retro_set_controller_port_device)
#undef DECL
};
std::mutex guard;Session* active=nullptr;jlong nextToken=0;
constexpr wchar_t CLASS_NAME[]=L"PIQPvZJniPrivate1";
Session* current(){auto s=active;if(s&&s->owner!=GetCurrentThreadId()){s->wrongThread=true;return nullptr;}return s;}
void error(JNIEnv* env,const char* message){if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/io/IOException"),message);}
std::wstring wide(JNIEnv* env,jstring value){
    if(!value)throw std::runtime_error("Missing native path");
    auto n=env->GetStringLength(value);if(n<1||n>4096)throw std::runtime_error("Invalid native path length");
    auto chars=env->GetStringChars(value,nullptr);if(!chars)throw std::runtime_error("Cannot read native path");
    std::wstring result(reinterpret_cast<const wchar_t*>(chars),n);env->ReleaseStringChars(value,chars);
    if(result.find(L'\0')!=std::wstring::npos)throw std::runtime_error("NUL in native path");return result;
}
std::string utf8(const std::wstring& w){
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,w.data(),(int)w.size(),nullptr,0,nullptr,nullptr);
    if(!n)throw std::runtime_error("Invalid Unicode path");std::string s(n,0);
    WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,w.data(),(int)w.size(),s.data(),n,nullptr,nullptr);return s;
}
retro_proc_address_t proc(const char* name){auto s=current();if(!s||!name)return nullptr;
    PROC p=wglGetProcAddress(name);if(!p||p==(PROC)1||p==(PROC)2||p==(PROC)3||p==(PROC)-1)p=GetProcAddress(s->gl,name);
    return reinterpret_cast<retro_proc_address_t>(p);
}
template<class T>T glfun(const char* name){auto p=proc(name);if(!p)throw std::runtime_error(std::string("Missing GL function: ")+name);return reinterpret_cast<T>(p);}
uintptr_t framebuffer(){auto s=current();return s?s->fbo:0;}
void initGL(Session& s){
    // WGL contexts are thread-local. Refuse an already-owned context rather than displacing one.
    if(wglGetCurrentContext())throw std::runtime_error("JNI worker already owns an OpenGL context");
    s.gl=LoadLibraryExW(L"opengl32.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);if(!s.gl)throw std::runtime_error("OpenGL unavailable");
    WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=CLASS_NAME;wc.style=CS_OWNDC;
    if(!RegisterClassW(&wc))throw std::runtime_error("Cannot register private WGL window");s.ownsClass=true;
    s.window=CreateWindowW(CLASS_NAME,L"PIQ PvZ JNI worker",WS_POPUP,0,0,W,H,nullptr,nullptr,wc.hInstance,nullptr);
    if(!s.window)throw std::runtime_error("Cannot create private WGL window");
    s.dc=GetDC(s.window);if(!s.dc)throw std::runtime_error("Cannot acquire private WGL DC");
    PIXELFORMATDESCRIPTOR p{};p.nSize=sizeof(p);p.nVersion=1;p.dwFlags=PFD_DRAW_TO_WINDOW|PFD_SUPPORT_OPENGL|PFD_DOUBLEBUFFER;p.iPixelType=PFD_TYPE_RGBA;p.cColorBits=32;p.cDepthBits=24;p.cStencilBits=8;
    int format=ChoosePixelFormat(s.dc,&p);if(!format||!SetPixelFormat(s.dc,format,&p))throw std::runtime_error("OpenGL pixel format unavailable");
    s.context=wglCreateContext(s.dc);if(!s.context||!wglMakeCurrent(s.dc,s.context))throw std::runtime_error("OpenGL context unavailable");
    glfun<Gen>("glGenFramebuffers")(1,&s.fbo);s.bind=glfun<Bind>("glBindFramebuffer");s.bind(0x8D40,s.fbo);
    glGenTextures(1,&s.texture);glBindTexture(GL_TEXTURE_2D,s.texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glfun<void(APIENTRY*)(GLenum,GLenum,GLenum,GLuint,GLint)>("glFramebufferTexture2D")(0x8D40,0x8CE0,GL_TEXTURE_2D,s.texture,0);
    glfun<Gen>("glGenRenderbuffers")(1,&s.depth);glfun<Bind>("glBindRenderbuffer")(0x8D41,s.depth);
    glfun<void(APIENTRY*)(GLenum,GLenum,GLsizei,GLsizei)>("glRenderbufferStorage")(0x8D41,0x88F0,W,H);
    glfun<void(APIENTRY*)(GLenum,GLenum,GLenum,GLuint)>("glFramebufferRenderbuffer")(0x8D40,0x821A,0x8D41,s.depth);
    if(glfun<GLenum(APIENTRY*)(GLenum)>("glCheckFramebufferStatus")(0x8D40)!=0x8CD5)throw std::runtime_error("Incomplete framebuffer");
    glViewport(0,0,W,H);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
}
bool environment(unsigned cmd,void* data){auto s=current();if(!s)return false;
    if(cmd==RETRO_ENVIRONMENT_SHUTDOWN){s->shutdown=true;return true;}
    if(cmd==RETRO_ENVIRONMENT_GET_INPUT_BITMASKS)return true;if(!data)return false;
    switch(cmd){
        case RETRO_ENVIRONMENT_SET_HW_RENDER:{auto r=(retro_hw_render_callback*)data;if(r->context_type!=RETRO_HW_CONTEXT_OPENGL)return false;r->get_current_framebuffer=framebuffer;r->get_proc_address=proc;s->hw=*r;return true;}
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:*(const char**)data=s->system.c_str();return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:*(const char**)data=s->save.c_str();return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:return *(retro_pixel_format*)data==RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:*(bool*)data=true;return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:case RETRO_ENVIRONMENT_SET_MESSAGE:return true;
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:s->keyboard=*(retro_keyboard_callback*)data;return true;
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:*(uint64_t*)data=(1ULL<<RETRO_DEVICE_JOYPAD)|(1ULL<<RETRO_DEVICE_POINTER);return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:*(unsigned*)data=0;return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*(bool*)data=false;return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:{auto v=(retro_variable*)data;if(!v->key)return false;
            if(!strcmp(v->key,"pvz_draw_cursor"))v->value="always";else if(!strcmp(v->key,"pvz_cursor_speed"))v->value="6";else if(!strcmp(v->key,"pvz_gamepad_deadzone"))v->value="25%";else if(!strcmp(v->key,"pvz_cheat_keys"))v->value="disabled";else return false;return true;}
        default:return false;
    }
}
void video(const void* data,unsigned w,unsigned h,size_t){auto s=current();if(!s)return;if(w!=W||h!=H){s->error="Unexpected video dimensions";return;}if(data==RETRO_HW_FRAME_BUFFER_VALID)s->drawn=true;}
void audio(int16_t a,int16_t b){auto s=current();if(!s)return;if(s->pcm.size()+2>AUDIO/2){s->error="Audio limit";return;}s->pcm.push_back(a);s->pcm.push_back(b);}
size_t batch(const int16_t* data,size_t n){auto s=current();if(!s)return 0;if(!data||n>AUDIO/4||s->pcm.size()+n*2>AUDIO/2){s->error="Audio limit";return 0;}s->pcm.insert(s->pcm.end(),data,data+n*2);return n;}
void poll(){}
int16_t input(unsigned port,unsigned d,unsigned index,unsigned id){auto s=current();if(!s||port||index)return 0;
    if(d==RETRO_DEVICE_JOYPAD)return id==RETRO_DEVICE_ID_JOYPAD_MASK?(int16_t)s->pad:id<16?(s->pad>>id)&1:0;
    if(d==RETRO_DEVICE_POINTER){if(id==RETRO_DEVICE_ID_POINTER_X)return s->x;if(id==RETRO_DEVICE_ID_POINTER_Y)return s->y;if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return s->buttons&1;if(id==RETRO_DEVICE_ID_POINTER_COUNT)return 1;}
    if(d==RETRO_DEVICE_MOUSE&&id==RETRO_DEVICE_ID_MOUSE_RIGHT)return (s->buttons>>1)&1;return 0;
}
void dispose(Session& s){
    if(s.loaded){s.loaded=false;s.retro_unload_game();}
    if(s.reset&&s.hw.context_destroy){s.reset=false;s.hw.context_destroy();}
    if(s.initialized){s.initialized=false;s.retro_deinit();}
    if(s.core){FreeLibrary(s.core);s.core=nullptr;}
    // Destroying the private GL context releases all objects even on partial initialization.
    if(s.context){wglMakeCurrent(nullptr,nullptr);wglDeleteContext(s.context);s.context=nullptr;}
    if(s.dc){ReleaseDC(s.window,s.dc);s.dc=nullptr;}if(s.window){DestroyWindow(s.window);s.window=nullptr;}
    if(s.ownsClass){UnregisterClassW(CLASS_NAME,GetModuleHandleW(nullptr));s.ownsClass=false;}
    if(s.gl){FreeLibrary(s.gl);s.gl=nullptr;}
}
Session& require(jlong token){if(!active||active->token!=token||active->owner!=GetCurrentThreadId())throw std::runtime_error("Invalid JNI session or worker thread");return *active;}
}
extern "C" JNIEXPORT jlong JNICALL Java_cn_piq_pvz_runtime_PvzJniBridge_open(JNIEnv* env,jclass,jstring dll,jstring data,jstring saves){
    std::lock_guard<std::mutex> lock(guard);if(active){error(env,"Another JNI PvZ session is active");return 0;}
    Session* s=nullptr;
    try{
        auto core=wide(env,dll),system=wide(env,data),save=wide(env,saves);
        s=new Session{};active=s;s->token=++nextToken;s->system=utf8(system);s->save=utf8(save);s->pcm.reserve(AUDIO/2);
        s->core=LoadLibraryExW(core.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);if(!s->core)throw std::runtime_error("Core DLL could not be loaded");
#define API(name) s->name=reinterpret_cast<decltype(s->name)>(GetProcAddress(s->core,#name));if(!s->name)throw std::runtime_error(#name);
        API(retro_api_version) API(retro_set_environment) API(retro_set_video_refresh) API(retro_set_audio_sample) API(retro_set_audio_sample_batch) API(retro_set_input_poll) API(retro_set_input_state) API(retro_init) API(retro_load_game) API(retro_run) API(retro_unload_game) API(retro_deinit) API(retro_set_controller_port_device)
#undef API
        if(s->retro_api_version()!=RETRO_API_VERSION)throw std::runtime_error("Core API mismatch");
        initGL(*s);s->retro_set_environment(environment);s->retro_init();s->initialized=true;
        s->retro_set_video_refresh(video);s->retro_set_audio_sample(audio);s->retro_set_audio_sample_batch(batch);s->retro_set_input_poll(poll);s->retro_set_input_state(input);
        auto path=s->system+"/main.pak";retro_game_info game{};game.path=path.c_str();
        if(!s->retro_load_game(&game))throw std::runtime_error("Core rejected main.pak");s->loaded=true;
        if(!s->hw.context_reset)throw std::runtime_error("Missing GL reset callback");s->hw.context_reset();s->reset=true;
        s->retro_set_controller_port_device(0,RETRO_DEVICE_JOYPAD);return s->token;
    }catch(const std::exception& ex){if(s){dispose(*s);active=nullptr;delete s;}error(env,ex.what());return 0;}
}
extern "C" JNIEXPORT jint JNICALL Java_cn_piq_pvz_runtime_PvzJniBridge_step(JNIEnv* env,jclass,jlong token,jobject pixels,jobject sound,jint pad,jint x,jint y,jint buttons,jboolean pointer,jintArray keys){
    std::lock_guard<std::mutex> lock(guard);
    try{auto& s=require(token);
        if(!pixels||!sound||!keys)throw std::runtime_error("Missing output buffer or key list");
        auto v=(uint8_t*)env->GetDirectBufferAddress(pixels);auto a=env->GetDirectBufferAddress(sound);
        if(!v||!a||env->GetDirectBufferCapacity(pixels)!=VIDEO||env->GetDirectBufferCapacity(sound)!=AUDIO)throw std::runtime_error("Invalid direct output buffer");
        if(!keys)throw std::runtime_error("Missing key list");jsize n=env->GetArrayLength(keys);if(n>384||n%3)throw std::runtime_error("Key limit");
        jint values[384];env->GetIntArrayRegion(keys,0,n,values);if(env->ExceptionCheck())return 0;
        if(x<-32768||x>32767||y<-32768||y>32767||pad<0||pad>65535||buttons<0||buttons>3)throw std::runtime_error("Input outside bounds");
        if(s.shutdown)return -1;
        MSG msg;while(PeekMessageW(&msg,s.window,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}
        s.pad=pad;s.x=x;s.y=y;s.buttons=buttons;int device=pointer?RETRO_DEVICE_POINTER:RETRO_DEVICE_JOYPAD;
        if(s.device!=device){s.retro_set_controller_port_device(0,device);s.device=device;}
        for(int i=0;i<n;i+=3){if((values[i]!=0&&values[i]!=1)||values[i+1]<0||values[i+1]>512||values[i+2]<0||values[i+2]>0x10ffff)throw std::runtime_error("Invalid key event");if(s.keyboard.callback)s.keyboard.callback(values[i]!=0,values[i+1],values[i+2],0);}
        s.pcm.clear();s.drawn=false;s.retro_run();
        if(s.wrongThread)throw std::runtime_error("Core callback on unexpected thread");if(!s.error.empty())throw std::runtime_error(s.error);
        if(s.drawn){s.bind(0x8D40,s.fbo);glPixelStorei(GL_PACK_ALIGNMENT,1);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,v);
            if(s.hw.bottom_left_origin){uint8_t row[W*4];for(unsigned y=0;y<H/2;y++){auto p=v+y*W*4,q=v+(H-1-y)*W*4;memcpy(row,p,sizeof(row));memcpy(p,q,sizeof(row));memcpy(q,row,sizeof(row));}}
            for(unsigned i=3;i<VIDEO;i+=4)v[i]=255;
        }
        if(!s.pcm.empty())memcpy(a,s.pcm.data(),s.pcm.size()*2);
        return (s.drawn?1<<20:0)|(jint)(s.pcm.size()*2);
    }catch(const std::exception& ex){error(env,ex.what());return 0;}
}
extern "C" JNIEXPORT jstring JNICALL Java_cn_piq_pvz_runtime_PvzJniBridge_workingDirectory(JNIEnv* env,jclass){
    DWORD count=GetCurrentDirectoryW(0,nullptr);std::vector<wchar_t> path(count);
    DWORD length=GetCurrentDirectoryW(count,path.data());
    if(!length||length>=count){error(env,"Cannot read working directory");return nullptr;}
    return env->NewString(reinterpret_cast<const jchar*>(path.data()),length);
}
extern "C" JNIEXPORT void JNICALL Java_cn_piq_pvz_runtime_PvzJniBridge_close(JNIEnv* env,jclass,jlong token){
    std::lock_guard<std::mutex> lock(guard);try{auto& s=require(token);dispose(s);active=nullptr;delete &s;}catch(const std::exception& ex){error(env,ex.what());}
}
