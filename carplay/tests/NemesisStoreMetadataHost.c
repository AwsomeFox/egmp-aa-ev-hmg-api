/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "nemesis.h"
int read_file(const char *path,char *buf,size_t cap) {FILE *f=fopen(path,"rb");if(!f)return -1;size_t n=fread(buf,1,cap-1,f);buf[n]=0;fclose(f);return (int)n;}
static const char *core="1.0.0",*wireless="2.2.0";static int legacy=0;
static int lookup(const char *id,char *version,size_t cap) {
 const char *v=!strcmp(id,"hmgapi")?core:!strcmp(id,"hmgapi.wireless-carplay-aa")?wireless:!strcmp(id,"carplay.ev-routing")&&legacy?"0.4.0":0;
 if(!v)return 0;snprintf(version,cap,"%s",v);return 1;
}
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);return 1;}}while(0)
int main(int argc,char **argv){ini_file ini;pkg_meta m;char err[512],why[1024];CHECK(argc==2);CHECK(!ini_load(&ini,argv[1]));CHECK(!meta_parse(&ini,&m,err,sizeof err));CHECK(!strcmp(m.id,"hmgapi.egmp-carplay-ev-routing"));CHECK(!strcmp(m.pkg_ver,"0.4.0"));
 CHECK(meta_compat(&m,"NEEV23.USA.0000.V015.012.260507","NEEV23",lookup,why,sizeof why)==COMPAT_FULL);
 CHECK(meta_compat(&m,"CVEV.USA.0000.V015.012.260507","CVEV",lookup,why,sizeof why)==COMPAT_FULL);
 CHECK(meta_deps_unmet(&m,lookup,why,sizeof why)==0);core="0.99.6";CHECK(meta_deps_unmet(&m,lookup,why,sizeof why)>0);core="1.0.0";
 wireless=0;CHECK(meta_deps_unmet(&m,lookup,why,sizeof why)>0);
 wireless="2.1.0";CHECK(meta_deps_unmet(&m,lookup,why,sizeof why)>0);wireless="2.2.0";
 legacy=1;{char spec[64],have[32];CHECK(meta_conflict_spec(m.depends,"carplay.ev-routing",spec,sizeof spec));CHECK(lookup("carplay.ev-routing",have,sizeof have));CHECK(dep_spec_match(spec,have));}
 ini_free(&ini);puts("PASS actual NEMESIS metadata parser: both cars, core/wireless minimums, missing dependency and legacy-wrapper conflict");return 0;}
