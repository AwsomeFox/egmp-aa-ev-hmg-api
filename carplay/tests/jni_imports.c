/* SPDX-License-Identifier: GPL-3.0-or-later: emulation-only imports, never shipped */
#include <jni.h>
void *dlopen(const char *s,int n){return 0;}
void *dlsym(void *h,const char *s){return 0;}
const unsigned jni_offsets[]={
 __builtin_offsetof(struct JNINativeInterface_,GetArrayLength),
 __builtin_offsetof(struct JNINativeInterface_,GetIntArrayRegion),
 __builtin_offsetof(struct JNINativeInterface_,GetByteArrayRegion),
 __builtin_offsetof(struct JNINativeInterface_,ExceptionCheck)
};
