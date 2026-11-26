/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <jni.h>
#include "bridge_core.c"
extern void *dlopen(const char *,int);
extern void *dlsym(void *,const char *);

/* No implicit NativeClient layout guessing on unknown builds: Java gates both
 * native files by exact hash before calling open. These offsets are ARM32-only. */
static u32 session_from_client(jlong client){
    u32 pointer=(u32)client;
    if(!pointer || (pointer&3) || (client!=(jlong)pointer && client!=(jlong)(int)pointer))return 0;
    u32 native_session=*(u32*)(pointer+0x14);
    if(!native_session || (native_session&3))return 0;
    return *(u32*)(native_session+0x0c);
}
JNIEXPORT jint JNICALL Java_org_egmp_carplay_ev_app_NativeBridge_open(JNIEnv *env,jclass cls){
    (void)env;(void)cls;
    if(api.context)return 0;
    void *lib=dlopen("/system/lib/libiap2session.so",2);
    void *c=dlopen("/system/lib/libc.so",2);
    if(!lib || !c)return -10;
    Api a;
    a.table=dlsym(lib,"iAP2VehicleStatusSendList");
    a.lookup=dlsym(lib,"iAP2ControlVehicleStatusGetUpdatesPointer");
    Builder entry=dlsym(lib,"iAP2SessionPacketMakePacketUsingParamListNew");
    a.build=entry;
    a.context=dlsym(lib,"iAP2SessionGetControlContext");
    a.send=dlsym(lib,"iAP2ControlSessionSendCmd");
    a.lock=dlsym(c,"pthread_mutex_lock");a.unlock=dlsym(c,"pthread_mutex_unlock");
    return bridge_bind(&a);
}
JNIEXPORT jint JNICALL Java_org_egmp_carplay_ev_app_NativeBridge_send(JNIEnv *env,jclass cls,jlong client,jintArray values,jbyteArray charging,jbyteArray consumption){
    (void)cls;
    if(!api.context)return -10;
    if(!values || !charging || !consumption)return -12;
    if((*env)->GetArrayLength(env,values)!=9)return -12;
    jsize n=(*env)->GetArrayLength(env,charging),m=(*env)->GetArrayLength(env,consumption);
    if(n<1 || n>=MAX_JSON || m<1 || m>=MAX_JSON)return -12;
    jint input[9];char charge[MAX_JSON],consume[MAX_JSON];
    (*env)->GetIntArrayRegion(env,values,0,9,input);
    if((*env)->ExceptionCheck(env))return -12;
    for(int i=0;i<9;i++)if(input[i]<0)return -12;
    (*env)->GetByteArrayRegion(env,charging,0,n,(jbyte*)charge);
    if((*env)->ExceptionCheck(env))return -12;
    (*env)->GetByteArrayRegion(env,consumption,0,m,(jbyte*)consume);
    if((*env)->ExceptionCheck(env))return -12;
    u32 handle=session_from_client(client);if(!handle)return -11;
    return bridge_send(handle,(const Sample*)input,charge,(u32)n,consume,(u32)m);
}
JNIEXPORT jint JNICALL Java_org_egmp_carplay_ev_app_NativeBridge_stop(JNIEnv *env,jclass cls,jlong client){
    (void)env;(void)cls;
    if(!api.context)return -10;
    return bridge_stop(session_from_client(client));
}
_Static_assert(sizeof(void*)==4,"HU ABI is ARM32");

/* Called only with the Java service lock held, after its subscription worker is
 * invalidated. This avoids dereferencing a client being torn down by the OEM.
 * The sender is synchronous; no sidecar callback remains queued on return. */
JNIEXPORT void JNICALL Java_org_egmp_carplay_ev_app_NativeBridge_reset(JNIEnv *env,jclass cls){
    (void)env;(void)cls;
    for(u32 i=0;i<CONTEXTS;i++){ev_disable(i);states[i].key=0;states[i].original=0;states[i].original_count=0;keys[i]=0;handles[i]=0;}
}

JNIEXPORT void JNICALL Java_org_egmp_carplay_ev_app_NativeBridge_shutdown(JNIEnv *env,jclass cls){
    Java_org_egmp_carplay_ev_app_NativeBridge_reset(env,cls);
}
/* ARM EABI helper emitted for zeroed table entries; no host libc dependency. */
void __aeabi_memclr(void *dest,u32 n){volatile unsigned char *p=dest;while(n--)*p++=0;}
