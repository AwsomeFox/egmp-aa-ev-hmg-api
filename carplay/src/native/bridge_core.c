/* SPDX-License-Identifier: GPL-3.0-or-later
 * Typed EV producer -> stock table-driven serializer -> stock control sender.
 * No transport byte rewriting. Caller holds the Java service's native-handle lock.
 */
#include "ev_extension.c"
typedef void *(*Context)(u32);
typedef int (*Mutex)(void *);
typedef u32 (*PacketBuilder)(u32,u32,u32,u32,u32);
typedef int (*SendCmd)(u32,u32,u32,PacketBuilder);
typedef struct {const Param *table;Lookup lookup;Builder build;Context context;Mutex lock,unlock;SendCmd send;} Api;
static Api api;
static void *keys[CONTEXTS];
static u32 handles[CONTEXTS];
/* This offset is ABI-specific, independently visible in the stock ARM builder.
 * A verified per-firmware library hash is mandatory before binding this API. */
#define STOCK_MUTEX_OFFSET 0x45580
int bridge_bind(const Api *a){
    if(!a || !a->table || !a->lookup || !a->build || !a->context || !a->lock || !a->unlock || !a->send)return -10;
    for(u32 i=0;i<CONTEXTS;i++)if(keys[i])return -17;
    api=*a;return 0;
}
static int slot_for(void *key){for(u32 i=0;i<CONTEXTS;i++)if(keys[i]==key)return (int)i;return -1;}
static u32 packet(u32 handle,u32 session,u32 a,u32 b,u32 command){
    (void)a;(void)b;
    void *key=api.context(handle);int slot=slot_for(key);
    if(!key || slot<0 || handles[slot]!=handle || command!=0xa101)return 0;
    void *lock=(char*)key+STOCK_MUTEX_OFFSET;
    if(api.lock(lock))return 0;
    u32 result=ev_build((u32)slot,handle,session,command);
    api.unlock(lock);return result;
}
/* session handle, NOT the Java client handle. The JNI adapter performs that
 * verified-build-specific conversion. Input JSON is already validated by Java. */
int bridge_send(u32 handle,const Sample *value,const char *charge,u32 charge_len,const char *consume,u32 consume_len){
    if(!api.context)return -10;
    if(!handle)return -11;
    void *key=api.context(handle);if(!key)return -11;
    int slot=slot_for(key);
    if(slot<0){for(u32 i=0;i<CONTEXTS;i++)if(!keys[i]){slot=(int)i;break;}}
    if(slot<0)return -13;
    void *lock=(char*)key+STOCK_MUTEX_OFFSET;
    if(api.lock(lock))return -15;
    if(!keys[slot]){
        int rc=ev_attach((u32)slot,key,api.table,12,api.lookup,api.build);
        if(rc){api.unlock(lock);return -16;}
        keys[slot]=key;handles[slot]=handle;
    }
    if(handles[slot]!=handle){api.unlock(lock);return -11;}
    int rc=ev_update((u32)slot,value,charge,charge_len,consume,consume_len);
    api.unlock(lock);
    if(rc)return -12;
    /* Stock SendCmd finds the control session, invokes packet synchronously and
     * sends the returned native packet through the existing link/transport. */
    return api.send(handle,0,0xa101,packet)?0:-14;
}
int bridge_stop(u32 handle){
    if(!api.context || !handle)return -11;
    void *key=api.context(handle);int slot=slot_for(key);
    if(!key || slot<0 || handles[slot]!=handle)return -11;
    void *lock=(char*)key+STOCK_MUTEX_OFFSET;
    if(api.lock(lock))return -15;
    ev_disable((u32)slot);states[slot].key=0;states[slot].original=0;states[slot].original_count=0;
    keys[slot]=0;handles[slot]=0;
    api.unlock(lock);return 0;
}
