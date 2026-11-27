/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "nemesis.h"
int read_file(const char *path,char *buf,size_t cap) {
    FILE *f=fopen(path,"rb"); if(!f)return -1;
    size_t n=fread(buf,1,cap-1,f);buf[n]=0;fclose(f);return (int)n;
}
static const char *installed="0.99.6";
static int lookup(const char *id,char *version,size_t cap) {
    if(strcmp(id,"hmgapi") || !installed)return 0;
    snprintf(version,cap,"%s",installed);return 1;
}
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);return 1;}}while(0)
int main(int argc,char **argv) {
    ini_file ini;pkg_meta m;char err[512],why[1024];
    CHECK(argc==2);CHECK(ini_load(&ini,argv[1])==0);CHECK(meta_parse(&ini,&m,err,sizeof err)==0);
    CHECK(!strcmp(m.id,"carplay.ev-routing"));CHECK(!strcmp(m.meta_ver,"1.1"));CHECK(!strcmp(m.pkg_ver,"0.5.3"));
    CHECK(meta_depends_on(m.depends,"hmgapi"));CHECK(!strcmp(ini_get(&ini,"app.0","kind"),"sh"));
    CHECK(meta_compat(&m,"NEEV23.USA.0000.V015.012.260507","NEEV23",lookup,why,sizeof why)==COMPAT_FULL);
    CHECK(meta_compat(&m,"CVEV.USA.0000.V015.012.260507","CVEV",lookup,why,sizeof why)==COMPAT_FULL);
    CHECK(meta_compat(&m,"NEEV23.CAN.0000.V016.010.260903","NEEV23",lookup,why,sizeof why)==COMPAT_FULL);
    CHECK(meta_compat(&m,"CVEV.EUR.0000.V016.010.270101","CVEV",lookup,why,sizeof why)==COMPAT_FULL);
    CHECK(meta_compat(&m,"CEEV.USA.0000.V015.012.260507","CEEV",lookup,why,sizeof why)==COMPAT_FULL);
    installed="0.99.5";CHECK(meta_deps_unmet(&m,lookup,why,sizeof why)==1);
    installed=0;CHECK(meta_deps_unmet(&m,lookup,why,sizeof why)==1);
    installed="1.0.0";CHECK(meta_deps_unmet(&m,lookup,why,sizeof why)==0);
    ini_free(&ini);puts("PASS native NEMESIS metadata, beta6 minimum, missing/old HMG-API refusal, profile compatibility and launcher");return 0;
}
