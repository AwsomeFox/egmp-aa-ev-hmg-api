/* SPDX-License-Identifier: GPL-3.0-or-later
 * Offline ARM prototype: extend the stock table-driven serializer, not its TLV engine.
 * No stock context fields or global parameter tables are modified.
 */
typedef unsigned int u32;
typedef unsigned short u16;
typedef void *(*Lookup)(u16,u16,u16 *,void *,int *);
typedef u32 (*Builder)(u32,u32,u32,u32,u32,void *,u32,const char *,Lookup);
typedef struct {u16 used,id,type,pad;u32 init,length,done,children;} Param;
typedef struct {u32 min_wh,max_wh,current_wh,soc_e3,range_km,warning,full_range_km,has_live,has_full;} Sample;
#define MAX_ORIGINAL 24
#define NEW_COUNT 7
#define MAX_JSON 8192
#define CONTEXTS 2
static const u16 ids[NEW_COUNT]={21,22,23,24,26,27,30};
static const u16 types[NEW_COUNT]={6,6,6,6,10,10,5};
typedef struct {
    void *key; const Param *original;u32 original_count;Lookup old;Builder builder;
    Param table[MAX_ORIGINAL+NEW_COUNT];
    u32 values[NEW_COUNT],legacy[4];
    char charge[MAX_JSON],consumption[MAX_JSON];
    int enabled;
} State;
static State states[CONTEXTS];
static void copy(char *d,const char *s,u32 n){while(n--)*d++=*s++;}
static int own_legacy(u16 id){return id==3?0:id==6?1:id==11?2:id==15?3:-1;}
static State *find(void *key){if(!key)return 0;for(u32 i=0;i<CONTEXTS;i++)if(states[i].key==key)return &states[i];return 0;}
void *ev_lookup(u16 id,u16 type,u16 *length,void *key,int *more){
    State *s=find(key);
    if(!s)return 0;
    if(!s->enabled)return s->old(id,type,length,key,more);
    if(more)*more=0;
    for(u32 i=0;i<NEW_COUNT;i++)if(ids[i]==id){
        if(type!=types[i])return 0;
        return id==26?(void*)s->charge:id==27?(void*)s->consumption:(void*)&s->values[i];
    }
    int n=own_legacy(id);if(n>=0)return &s->legacy[n];
    return s->old(id,type,length,key,more);
}
int ev_attach(u32 slot,void *key,const Param *original,u32 count,Lookup old,Builder builder){
    if(slot>=CONTEXTS || !key || !original || !old || !builder || count>MAX_ORIGINAL)return -1;
    for(u32 i=0;i<CONTEXTS;i++)if(i!=slot && states[i].key==key)return -2;
    /* Refuse another mod's existing EV definitions instead of silently replacing them. */
    for(u32 i=0;i<count;i++)for(u32 j=0;j<NEW_COUNT;j++)if(original[i].id==ids[j])return -3;
    State *s=&states[slot];s->key=key;s->original=original;s->original_count=count;s->old=old;s->builder=builder;s->enabled=0;
    for(u32 i=0;i<count;i++)copy((char*)&s->table[i],(const char*)&original[i],sizeof(Param));
    for(u32 i=0;i<NEW_COUNT;i++){
        Param *p=&s->table[count+i];p->used=0;p->id=ids[i];p->type=types[i];p->pad=0;p->init=p->length=p->done=p->children=0;s->values[i]=0;
    }
    return 0;
}
static int string_ok(const char *s,u32 n){if(!s || n<1 || n>=MAX_JSON)return 0;for(u32 i=0;i<n;i++)if(!s[i])return 0;return 1;}
int ev_update(u32 slot,const Sample *v,const char *charge,u32 charge_len,const char *consumption,u32 consumption_len){
    if(slot>=CONTEXTS || !states[slot].key || !v)return -1;
    if(v->has_live>1 || v->has_full>1 || (!v->has_live && v->has_full) || v->min_wh>v->max_wh || !v->max_wh)return -2;
    if(v->has_live && (v->soc_e3>100000 || v->range_km>=2000 || v->warning>1 || v->current_wh<v->min_wh || v->current_wh>v->max_wh))return -2;
    if(v->has_full && v->full_range_km>=2000)return -2;
    if(!string_ok(charge,charge_len) || !string_ok(consumption,consumption_len))return -3;
    /* Validate before changing anything. The production caller must hold the session lock. */
    State *s=&states[slot];copy(s->charge,charge,charge_len);s->charge[charge_len]=0;
    copy(s->consumption,consumption,consumption_len);s->consumption[consumption_len]=0;
    s->values[0]=v->min_wh;s->values[1]=v->current_wh;s->values[2]=v->max_wh;s->values[3]=v->soc_e3;s->values[6]=v->full_range_km;
    s->legacy[0]=s->legacy[2]=v->range_km;s->legacy[1]=s->legacy[3]=v->warning;
    for(u32 i=0;i<s->original_count;i++){
        Param *p=&s->table[i];if(own_legacy(p->id)>=0)p->used=(u16)v->has_live;
        /* Stock RangeElectric entry is Boolean; EV ranges require Uint16. Scoped to the owned table. */
        if(p->id==11)p->type=5;
    }
    for(u32 i=0;i<NEW_COUNT;i++)s->table[s->original_count+i].used=(u16)((ids[i]==22 || ids[i]==24)?v->has_live:ids[i]==30?v->has_full:1);
    s->enabled=1;return 0;
}
void ev_disable(u32 slot){if(slot<CONTEXTS)states[slot].enabled=0;}
u32 ev_table(u32 slot){if(slot>=CONTEXTS)return 0;State *s=&states[slot];return (u32)(s->enabled?s->table:s->original);}
u32 ev_count(u32 slot){if(slot>=CONTEXTS)return 0;State *s=&states[slot];return s->original_count+(s->enabled?NEW_COUNT:0);}
u32 ev_build(u32 slot,u32 handle,u32 session,u32 command){
    if(slot>=CONTEXTS || !states[slot].key || !handle || command!=0xa101)return 0;
    State *s=&states[slot];
    return s->builder(handle,session,0,0,command,(void*)ev_table(slot),ev_count(slot),"EV table extension prototype",s->enabled?ev_lookup:s->old);
}
_Static_assert(sizeof(Param)==24,"native table ABI");
_Static_assert(sizeof(Sample)==36,"input sample ABI");
