#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check signed release payloads with the real HMG-API host engine; no HU access."""
from pathlib import Path
import argparse,json,os,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java',default='java');p.add_argument('--wireless-source',required=True,type=Path);a=p.parse_args()
 uuid='b45fb2d9-e6e8-83a2-bce9-cd5c40d2232d';cp=str(R/'out/hmg-classes')
 version=json.loads((R/'release.json').read_text())['version']
 wireless_version=json.loads((a.wireless_source/'wireless-carplay-aa/mod.json').read_text())['version']
 for model in ['NEEV23','CVEV','NEEV']:
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);product=model+'.USA.'+('S5W_M' if model=='NEEV' else '0000')+'.V015.012.260507';(root/'props.txt').write_text('ro.build.product='+product+'\n')
   env=dict(os.environ,HMGAPI_ROOT=tmp,HMGAPI_HOST='1')
   payload=R/'dist'/('egmp-carplay-ev-routing-v'+version+'.zip')
   def engine(*args,rc=0,contains=None):
    result=subprocess.run([a.java,'-cp',cp,'hmgapi.engine.Engine',*map(str,args)],env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    assert result.returncode==rc,result.stdout
    if contains:assert contains in result.stdout,result.stdout
    return result.stdout
   engine('install',payload,'--uuid','00000000-0000-8000-8000-000000000000',rc=3,contains='not the expected')
   engine('install',payload,'--uuid',uuid,contains='installed egmp-carplay-ev-routing '+version)
   installed=root/'data/hmgapi/mods/egmp-carplay-ev-routing';assert (installed/'profiles/neev23.json').exists();assert (installed/'profiles/cvev.json').exists()
   # No real car files: the engine applies only our helper and selected JSON in this temporary root.
   wireless=root/'data/hmgapi/mods/wireless-carplay-aa';wireless.mkdir();(wireless/'mod.json').write_text(json.dumps({'id':'wireless-carplay-aa','version':wireless_version}));(wireless/'patches.json').write_text('{"changes":[]}')
   engine('plan');engine('apply')
   selected=root/'system/etc/carplay.ev-routing/profile.json'
   assert selected.read_bytes()==(installed/'profiles'/(model.lower()+'.json')).read_bytes()
   assert json.loads(selected.read_text())['vehicle']['model']==('Ioniq 5' if model in ['NEEV23','NEEV'] else 'EV6')
   assert sorted(str(p.relative_to(root/'system')) for p in (root/'system').rglob('*') if p.is_file())==['etc/carplay.ev-routing/profile.json','lib/libcarplayev.so']
   # Even an explicit compatibility override must not silently combine the two new files.
   settings=root/'data/hmgapi/settings/hmgapi.json';settings.parent.mkdir(parents=True,exist_ok=True)
   settings.write_text(json.dumps({'overrides':{'ignore_system':['egmp-carplay-ev-routing']}}))
   before=selected.read_bytes();engine('plan',rc=3,contains='add different files');assert selected.read_bytes()==before
   settings.write_text('{}')
   (installed/'.disabled').touch()
   engine('install',payload,'--uuid',uuid,contains='still turned off');assert (installed/'.disabled').exists()
   tampered=root/'tampered.zip'
   with zipfile.ZipFile(payload) as source,zipfile.ZipFile(tampered,'w') as target:
    for name in source.namelist():target.writestr(name,source.read(name)+(b' ' if name=='patches.json' else b''))
   engine('install',tampered,'--uuid',uuid,rc=3,contains='file changed after signing')
   engine('remove','egmp-carplay-ev-routing');engine('apply');assert not installed.exists();assert not selected.exists();assert not (root/'system/lib/libcarplayev.so').exists()
   (root/'props.txt').write_text('ro.build.product='+model+'.USA.0000.260903\n')
   engine('install',payload,'--uuid',uuid,rc=3,contains="isn't for this head unit")
   print('PASS real HMG-API package engine:',model,'same ZIP, automatic apply-time profile selection/removal, signed update, author/tamper/firmware refusal')
if __name__=='__main__':main()
