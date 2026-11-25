#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise official wrapper hooks with our real signed mod and HMG-API host engine."""
from pathlib import Path
import argparse,configparser,hashlib,json,os,shutil,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--engine-classes',required=True,type=Path);p.add_argument('--java',default='java');a=p.parse_args();classes=a.engine_classes.resolve();java=shutil.which(a.java) or a.java
 config=json.loads((R/'release.json').read_text());policy=json.loads((R/'packaging/nemesis-release.json').read_text());mid=config['mod_id'];pid='hmgapi.'+mid;stem=mid+'-'+config['version'];folder=R/'dist/nemesis';package=folder/(stem+'-nemesis-package.zip');native=R/'dist'/('egmp-carplay-ev-routing-v'+config['version']+'.zip')
 with zipfile.ZipFile(package) as z:
  metadata=configparser.ConfigParser();metadata.read_string(z.read('NEMESIS.data').decode());n=metadata['nemesis'];assert n['id']==pid;assert n['depends'].strip('"')==','.join(policy['depends']);assert z.read('mod/package.zip')==native.read_bytes();assert hashlib.sha256(z.read('mod/package.zip')).hexdigest()==policy['mod_sha256']
 with zipfile.ZipFile(folder/(stem+'-nemesis.zip')) as z:assert z.read('packages/'+pid+'.zip')==package.read_bytes()
 for model,wireless in [('NEEV23',True),('CVEV',True),('CEEV',True),('NEEV23',False)]:
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);data=root/'data/hmgapi';system=root/'system';(system/'bin').mkdir(parents=True);(data/'mods').mkdir(parents=True);(data/'settings').mkdir();(root/'props.txt').write_text('ro.build.product='+model+'.USA.0000.V015.012.260507\n')
   bin=root/'bin';bin.mkdir();getprop=bin/'getprop';getprop.write_text('#!/bin/sh\necho 0\n');getprop.chmod(0o755)
   h=system/'bin/hmgapid';h.write_text('#!'+shutil.which('python3')+'\nimport os,sys,subprocess,json\nfrom pathlib import Path\na=sys.argv[1:]\nif a[0] in ("ping","daemon"):sys.exit(0)\nwith open(os.environ["ROOT"]+"/calls.jsonl","a") as f:f.write(json.dumps(a)+"\\n")\nsys.exit(subprocess.call(['+repr(java)+',"-cp",'+repr(str(classes))+',"hmgapi.engine.Engine"]+a[1:],env=dict(os.environ,HMGAPI_ROOT=os.environ["ROOT"],HMGAPI_HOST="1")))\n');h.chmod(0o755)
   # Existing OEM files must never change; our two new files must disappear on removal.
   (system/'app').mkdir();(system/'lib').mkdir();(system/'app/Iap2ServerApp.apk').write_bytes(b'original APK');(system/'lib/libiap2session.so').write_bytes(b'original native')
   def snapshot():return {str(p.relative_to(system)):p.read_bytes() for p in system.rglob('*') if p.is_file()}
   original=snapshot();stage=root/'data/local/tmp/nemesis/stage'/pid;stage.mkdir(parents=True)
   with zipfile.ZipFile(package) as z:z.extractall(stage)
   env=dict(os.environ,ROOT=str(root),PATH=str(bin)+os.pathsep+os.environ['PATH'],HMGAPI_NEMESIS_SYNC='',HMGAPI_USB_DIRS=str(root/'usb'))
   def hook(path,name,rc=0):
    result=subprocess.run(['sh','scripts/'+name],cwd=path,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);assert result.returncode==rc,result.stdout;return result.stdout
   hook(stage,'preinstall.sh',1);(data/'uninstall.sh').touch();hook(stage,'preinstall.sh')
   installed=root/'data/nemesis/apps'/pid;installed.parent.mkdir(parents=True);shutil.copytree(stage,installed);shutil.rmtree(stage)
   if wireless:
    w=data/'mods/wireless-carplay-aa';w.mkdir();(w/'mod.json').write_text('{"id":"wireless-carplay-aa","version":"2.2.0"}');(w/'patches.json').write_text('{"changes":[]}')
   hook(installed,'postinstall.sh',0 if wireless else 1);mod=data/'mods'/mid;active=system/'etc/carplay.ev-routing/profile.json'
   if not wireless:
    assert not mod.exists();assert snapshot()==original;print('PASS absent wireless: apply refused, new mod removed, original system intact');continue
   assert active.read_bytes()==(mod/'profiles'/(model.lower()+'.json')).read_bytes()
   settings=data/'settings'/(mid+'.json');settings.write_text('{"keep":true}');(mod/'.disabled').touch()
   stage.mkdir(parents=True);(stage/'NEMESIS.data').write_bytes((installed/'NEMESIS.data').read_bytes());assert 'update:' in hook(installed,'preuninstall.sh');assert mod.exists();shutil.rmtree(stage)
   hook(installed,'postinstall.sh');assert (mod/'.disabled').exists();assert settings.read_text()=='{"keep":true}'
   hook(installed,'preuninstall.sh');assert not mod.exists();assert snapshot()==original
   calls=[json.loads(s) for s in (root/'calls.jsonl').read_text().splitlines()]
   assert all('--from-nemesis' in c for c in calls if c[1] in ('install','remove'))
   print('PASS official wrapper:',model,'profile selection, update/disabled/settings retention, removal, no nested NEMESIS sync, original system intact')
 print('PASS package dependencies, unchanged signed payload and USB bundle')
if __name__=='__main__':main()
