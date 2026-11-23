#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build native HMG-API releases and optional NEMESIS test wrappers. No OEM inputs."""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[1]
def run(*args):subprocess.run(list(map(str,args)),check=True)
def md5(p):return hashlib.md5(p.read_bytes()).hexdigest()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def archive(folder,target):
 with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
  for p in sorted(folder.rglob('*')):
   if not p.is_file() or p.name=='.DS_Store' or p.name.startswith('._'):continue
   name=p.relative_to(folder).as_posix();info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.create_system=3;info.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16;info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,p.read_bytes())
def compile_java(files,out,classpath,java):
 out.mkdir(parents=True,exist_ok=True)
 run(java/'javac','--release','7','-Xlint:-options','-nowarn','-cp',':'.join(map(str,classpath)),'-d',out,*sorted(files))
def classjar(folder,path):
 with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(folder.rglob('*.class')):z.write(p,p.relative_to(folder).as_posix())
def version_key(value):
 m=re.fullmatch(r'(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?',value)
 if not m:raise SystemExit('Unsupported HMG-API version: '+value)
 major,minor,patch,stage,number=m.groups()
 order={None:3,'alpha':0,'beta':1,'rc':2}
 return (int(major),int(minor),int(patch),order[stage],int(number or 0))
def main():
 ap=argparse.ArgumentParser(description=__doc__)
 for arg in ('hmgapi-source','wireless-source','android-jar','d8-jar','json-jar','key'):ap.add_argument('--'+arg,required=True,type=Path)
 ap.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@17')))
 ap.add_argument('--clang',default=os.environ.get('CLANG','clang'));ap.add_argument('--lld',default=os.environ.get('LLD','ld.lld'))
 a=ap.parse_args();up=a.hmgapi_source.resolve();java=a.java_home/'bin';out=R/'out';dist=R/'dist';out.mkdir(exist_ok=True);dist.mkdir(exist_ok=True)
 config=json.loads((R/'release.json').read_text());version=config['version'];name='egmp-carplay-ev-routing-v'+version
 testing=dist/'testing';testing.mkdir(exist_ok=True)
 for p in (a.android_jar,a.d8_jar,a.json_jar,a.key):
  if not p.is_file():raise SystemExit('Missing build input: '+str(p))
 engine=up/'core/runtime/java/hmgapi/engine/Engine.java'
 match=re.search(r'HMGAPI_VERSION\s*=\s*"([^"]+)"',engine.read_text())
 if not match:raise SystemExit('Could not read HMG-API version from '+str(engine))
 if version_key(match.group(1)) < version_key(config['minimum_hmgapi']):
  raise SystemExit('HMG-API '+match.group(1)+' is older than required '+config['minimum_hmgapi'])
 native=R/'src/native';arm=['--target=armv7a-linux-androideabi','-mthumb','-march=armv7-a','-mfloat-abi=soft','-Os','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-unwind-tables','-fno-asynchronous-unwind-tables']
 jni_include=a.java_home/'include';jni_platform=next((p for p in jni_include.iterdir() if (p/'jni_md.h').is_file()),None)
 if jni_platform is None:raise SystemExit('Could not find jni_md.h under '+str(jni_include))
 jni_flags=['-I'+str(jni_include),'-I'+str(jni_platform)]
 imports=out/'link-stubs';imports.mkdir(exist_ok=True);(imports/'dl.c').write_text('void *dlopen(const char *p,int f){return 0;} void *dlsym(void *h,const char *s){return 0;}\n')
 run(a.clang,*arm,'-fPIC','-c',imports/'dl.c','-o',imports/'dl.o');run(a.lld,'-shared','-soname','libdl.so','-o',imports/'libdl.so',imports/'dl.o')
 run(a.clang,*arm,'-fPIC','-fvisibility=hidden','-I'+str(native/'compat'),*jni_flags,'-Wall','-Wextra','-Werror','-c',native/'bridge_jni.c','-o',out/'bridge_jni.o')
 run(a.lld,'-shared','--no-undefined','--hash-style=sysv','-z','now','-soname','libcarplayev.so','--build-id=none','-o',out/'libcarplayev.so',out/'bridge_jni.o',imports/'libdl.so')
 # ARM harness artifact is not shipped; tests can execute the real JNI code offline.
 run(a.clang,*arm,'-I'+str(native/'compat'),*jni_flags,'-c',R/'tests/jni_imports.c','-o',out/'jni_imports.o')
 run(a.lld,'-Ttext=0x02000000','-e','bridge_bind','-o',out/'bridge_jni_test.elf',out/'bridge_jni.o',out/'jni_imports.o')
 hmg=out/'hmg-classes';wireless_api=out/'wireless-api';oem=out/'oem-stubs';classes=out/'mod-classes';tests=out/'tests'
 for p in (hmg,wireless_api,oem,classes,tests):
  if p.exists():shutil.rmtree(p)
 compile_java(list((up/'core/runtime/java').rglob('*.java'))+list((up/'core/runtime/stubs').rglob('*.java')),hmg,[a.android_jar,a.json_jar],java)
 api_source=a.wireless_source/'wireless-carplay-aa/src/hmgapi/mods/wireless/api'
 api_files=list(api_source.rglob('*.java'))
 if not api_files:raise SystemExit('No Wireless CarPlay API sources under '+str(api_source))
 compile_java(api_files,wireless_api,[a.android_jar],java)
 compile_java(list((R/'stubs').rglob('*.java')),oem,[a.android_jar],java)
 compile_java(list((R/'src/java').rglob('*.java')),classes,[hmg,wireless_api,oem,a.android_jar,a.json_jar],java)
 compile_java(list((R/'tests').glob('*.java')),tests,[classes,hmg,wireless_api,a.android_jar,a.json_jar],java)
 run(java/'java','-cp',':'.join(map(str,[a.json_jar,classes,hmg,tests])),'org.egmp.carplay.ev.app.ProducerHost',R/'tests/fixtures/status.json',R/'vehicles'/config['profiles'][0]['path'])
 for folder,jar_name in [(hmg,'hmg-compile.jar'),(wireless_api,'wireless-api-compile.jar'),(oem,'oem-compile.jar'),(classes,'hooks-classes.jar')]:classjar(folder,out/jar_name)
 dex=out/'dex';dex.mkdir(exist_ok=True)
 run(java/'java','-cp',a.d8_jar,'com.android.tools.r8.D8','--min-api','18','--lib',a.android_jar,'--lib',(java/'javac').resolve().parents[1],'--classpath',out/'hmg-compile.jar','--classpath',out/'wireless-api-compile.jar','--classpath',out/'oem-compile.jar','--output',dex,out/'hooks-classes.jar')
 if not all(n.startswith('org/egmp/carplay/ev/') for n in zipfile.ZipFile(out/'hooks-classes.jar').namelist()):raise SystemExit('Compile-only classes leaked into mod')
 run(java/'java','-cp',hmg,'hmgapi.engine.ScriptCheck',up/'core/runtime/unit-commands.txt',R/'packaging/nemesis',R/'svc')
 stage=out/'nemesis-stage'
 if stage.exists():shutil.rmtree(stage)
 shutil.copytree(R/'packaging/nemesis',stage);(stage/'payload').mkdir();shutil.copyfile(R/'LICENSE',stage/'LICENSE');shutil.copyfile(R/'NOTICE.md',stage/'NOTICE.md')
 lines=[];evidence=[];mod=out/'mod'
 if mod.exists():shutil.rmtree(mod)
 mod.mkdir();meta=json.loads((R/'mod.json').read_text());meta['version']=version
 (mod/'mod.json').write_text(json.dumps(meta,indent=2)+'\n');shutil.copyfile(R/'patches.json',mod/'patches.json');shutil.copyfile(R/'LICENSE',mod/'LICENSE');shutil.copyfile(R/'NOTICE.md',mod/'NOTICE.md')
 for path,src in [('files/libcarplayev.so',out/'libcarplayev.so'),('svc/capture.sh',R/'svc/capture.sh')]:
  target=mod/path;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,target)
 for entry in config['profiles']:
  profile=R/'vehicles'/entry['path'];p=json.loads(profile.read_text());target=mod/('profiles/'+entry['model'].lower()+'.json');target.parent.mkdir(exist_ok=True);shutil.copyfile(profile,target)
  evidence.append({'model':entry['model'],'profile_id':p['profile_id'],'capacities_wh':p['supported_capacities_wh'],'profile_sha256':sha(profile),'status':entry['status']})
 with zipfile.ZipFile(mod/meta['hooks']['jar'],'w',zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
 run(java/'java','-cp',hmg,'hmgapi.engine.Signing','sign',mod,a.key);run(java/'java','-cp',hmg,'hmgapi.engine.Signing','verify',mod,config['author_uuid'])
 archive(mod,dist/(name+'.zip'));shutil.copyfile(dist/(name+'.zip'),stage/'payload/mod.zip')
 for entry in config['profiles']:
  lines.append(' '.join([entry['prefix'],entry['firmware'],'mod.zip',md5(stage/'payload/mod.zip')]))
 run(java/'java','-cp',':'.join(map(str,[hmg,tests])),'ReleasePlanHost',up,out,a.wireless_source)
 (stage/'payload/profiles.tsv').write_text('\n'.join(lines)+'\n');(stage/'payload/native-md5.txt').write_text(''.join(n+' '+h+'\n' for n,h in config['native_md5'].items()))
 run(os.sys.executable,R/'tests/test-hmgapi-package.py','--java',java/'java','--wireless-source',a.wireless_source)
 package=testing/(name+'-nemesis-package.zip');archive(stage,package)
 with tempfile.TemporaryDirectory() as t:
  usb=Path(t);(usb/'packages').mkdir();shutil.copyfile(package,usb/'packages'/package.name)
  (usb/'NEMESIS.data').write_text('[nemesis]\nversion=1.0\ntype=install\npath=packages/'+package.name+'\n')
  (usb/'README.txt').write_text('Install NEMESIS, then official HMG-API 1.1.0-rc.3 or newer through NEMESIS. Enable Wireless CarPlay + Android Auto 2.2.0-rc.4 or newer and check the map. Unzip this archive onto the USB root; insert after Android starts. Remove USB and fully reboot after successful installation. See the repository README.\n')
  archive(usb,testing/(name+'-nemesis-usb.zip'))
 # Explicit source roots; no tools, keys, host binaries, previous builds or captures.
 with tempfile.TemporaryDirectory() as t:
  source=Path(t)/name;source.mkdir()
  for rel in ['README.md','CHANGELOG.md','LICENSE','NOTICE.md','.gitignore','release.json','mod.json','patches.json','src','stubs','svc','vehicles','packaging','tools','tests','docs','images']:
   src=R/rel
   if src.is_dir():shutil.copytree(src,source/rel,ignore=shutil.ignore_patterns('.DS_Store','._*','__pycache__','*.pyc'))
   else:shutil.copyfile(src,source/rel)
  archive(Path(t),dist/(name+'-source.zip'))
 report={'version':version,'primary_route':'single native HMG-API mod','hmgapi_minimum':config['minimum_hmgapi'],'nemesis_dependency':'hmgapi@>='+config['minimum_hmgapi_package'],'author_uuid':config['author_uuid'],'profiles':evidence,'native_sha256':sha(out/'libcarplayev.so'),'tools':{p.name:sha(p) for p in [a.android_jar,a.d8_jar,a.json_jar]},'files':{p.relative_to(dist).as_posix():sha(p) for p in sorted(dist.rglob(name+'*.zip'))}}
 (out/'build-report.json').write_text(json.dumps(report,indent=2)+'\n');(dist/(name+'-SHA256SUMS')).write_text(''.join(h+'  '+n+'\n' for n,h in report['files'].items() if '/' not in n))
 print('Native HMG-API release prepared:',dist)
 print('Optional NEMESIS testing package:',package)
if __name__=='__main__':main()
