#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare official NEMESIS wrappers around an existing signed mod; publishes nothing."""
from pathlib import Path
import argparse,configparser,hashlib,json,os,re,shutil,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[1]
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def pack(folder,target):
 with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
  for p in sorted(folder.rglob('*')):
   if not p.is_file():continue
   rel=p.relative_to(folder).as_posix();info=zipfile.ZipInfo(rel,(2026,1,1,0,0,0));info.create_system=3;info.external_attr=(0o100755 if rel.endswith('.sh') else 0o100644)<<16;info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,p.read_bytes())
def unpack(archive,destination):
 with zipfile.ZipFile(archive) as z:
  if sum(i.file_size for i in z.infolist())>64<<20:raise ValueError('Package exceeds 64 MiB')
  for i in z.infolist():
   path=Path(i.filename)
   if path.is_absolute() or '..' in path.parts or '\\' in i.filename or (i.external_attr>>16)&0o170000==0o120000:raise ValueError('Unsafe ZIP entry')
  z.extractall(destination)
def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--mod-zip',required=True,type=Path);ap.add_argument('--hmgapi-source',required=True,type=Path);ap.add_argument('--output',type=Path,default=R/'dist/nemesis');ap.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@17')));a=ap.parse_args()
 config=json.loads((R/'release.json').read_text());policy=json.loads((R/'packaging/nemesis-release.json').read_text());source=a.hmgapi_source.resolve();native=a.mod_zip.resolve();output=a.output.resolve();before=digest(native)
 if before!=policy['mod_sha256']:raise ValueError('Input differs from the published, HU-tested mod ZIP')
 engine=(source/'core/runtime/java/hmgapi/engine/Engine.java').read_text()
 if not re.search(r'HMGAPI_VERSION\s*=\s*"'+re.escape(policy['hmgapi_version'])+'"',engine):raise ValueError('Use the reviewed HMG-API '+policy['hmgapi_version']+' source')
 env=dict(os.environ);env['PATH']=str(a.java_home/'bin')+os.pathsep+env['PATH']
 with tempfile.TemporaryDirectory() as t:
  work=Path(t);mod=work/'mod';unpack(native,mod);meta=json.loads((mod/'mod.json').read_text())
  if meta['id']!=config['mod_id'] or meta['version']!=config['version']:raise ValueError('Unexpected mod id/version')
  classes=work/'engine';classes.mkdir()
  subprocess.run(['javac','--release','8','-Xlint:-options','-nowarn','-d',str(classes),*map(str,sorted((source/'core/runtime/java/hmgapi/engine').glob('*.java')))],env=env,check=True,stdout=subprocess.DEVNULL)
  subprocess.run(['java','-cp',str(classes),'hmgapi.engine.Signing','verify',str(mod),config['author_uuid']],env=env,check=True)
  generated=work/'upstream'
  subprocess.run(['sh','tools/mknemesismod.sh',str(native),str(generated)],cwd=source,env=env,check=True,stdout=subprocess.DEVNULL)
  stem=meta['id']+'-'+meta['version'];pkgname=stem+'-nemesis-package.zip';usbname=stem+'-nemesis.zip';package=work/'package';unpack(generated/pkgname,package)
  descriptor=package/'NEMESIS.data';text=descriptor.read_text();text,n=re.subn(r'^depends=.*$', 'depends="'+','.join(policy['depends'])+'"',text,flags=re.M)
  if n!=1:raise ValueError('Expected one upstream dependency declaration')
  descriptor.write_text(text)
  shutil.copyfile(source/'LICENSE',package/'LICENSE-HMG-API.txt')
  (package/'UPSTREAM-SOURCE.txt').write_text('NEMESIS lifecycle scripts are unmodified HMG-API '+policy['hmgapi_version']+' code, AGPL-3.0. Source: https://github.com/VysseCodes/HMG-API/tree/v'+policy['hmgapi_version']+'/install/nemesis/mod\nThe embedded EV-routing mod retains its own license and signature.\n')
  # Only metadata is customized. Every lifecycle script remains exactly upstream's.
  ini=configparser.ConfigParser();ini.read(descriptor);pid='hmgapi.'+meta['id']
  assert ini['nemesis']['id']==pid
  assert (package/'mod/package.zip').read_bytes()==native.read_bytes()
  for p in (package/'scripts').glob('*.sh'):assert p.read_bytes()==(source/'install/nemesis/mod/scripts'/p.name).read_bytes()
  output.mkdir(parents=True,exist_ok=True);pack(package,work/pkgname)
  usb=work/'usb';(usb/'packages').mkdir(parents=True);shutil.copyfile(work/pkgname,usb/'packages'/(pid+'.zip'))
  (usb/'NEMESIS.data').write_text('[nemesis]\nversion=1.0\ntype=install\npath=packages/'+pid+'.zip\n')
  (usb/'README.txt').write_text('Requires NEMESIS '+policy['minimum_nemesis']+'+, HMG-API 1.0+, and Wireless CarPlay + Android Auto 2.1+. Unzip onto USB root, install with NEMESIS, remove USB and fully reboot. For first-time EV routing setup, delete the iPhone/HU pairing on both devices and pair again. Installation guide: '+policy['repository']+'/blob/main/docs/INSTALLATION.md\n')
  pack(usb,work/usbname)
  files={}
  for name in [pkgname,usbname]:
   target=output/name
   if target.exists() and target.read_bytes()!=(work/name).read_bytes():raise ValueError('Output already exists with different bytes; choose a fresh output directory')
   shutil.copyfile(work/name,target);files[name]=digest(target)
  assert digest(native)==before
  (output/'SHA256SUMS').write_text(''.join(h+'  '+n+'\n' for n,h in files.items()))
  report={'status':policy.get('rollout_status','prepared; not uploaded or listed'),'hold_reason':policy.get('hold_reason'),'package_id':pid,'version':meta['version'],'hmgapi_packager_version':policy['hmgapi_version'],'minimum_nemesis':policy['minimum_nemesis'],'depends':policy['depends'],'os':ini['nemesis']['os'].strip('"'),'native_mod_sha256':before,'author_uuid':config['author_uuid'],'files':files,'store_upload':pkgname,'usb_download':usbname,'release_gate':'Lift any explicit rollout hold after upstream stability review and HU verification; then confirm core/wireless store listings before submission','upstream_scripts_sha256':{p.name:digest(p) for p in sorted((package/'scripts').glob('*.sh'))}}
  (output/'store-readiness.json').write_text(json.dumps(report,indent=2)+'\n');print('Prepared NEMESIS release:',output);print('Store upload:',pkgname)
if __name__=='__main__':main()
