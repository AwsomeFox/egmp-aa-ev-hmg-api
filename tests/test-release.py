#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package audit and actual NEMESIS lifecycle scripts against an isolated fake HU."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,tempfile,zipfile,unittest,configparser
R=Path(__file__).resolve().parents[1];CONFIG=json.loads((R/'release.json').read_text());STAGE=R/'out/nemesis-stage'
MOCK=r'''import sys,os,json,hashlib,zipfile,shutil
from pathlib import Path
sfile=Path(os.environ['EVTEST_STATE']);s=json.loads(sfile.read_text());root=Path(s['root']);name=Path(sys.argv[0]).name;a=sys.argv[1:]
def done(code=0,text=''):
 sfile.write_text(json.dumps(s));print(text);raise SystemExit(code)
if name=='getprop':done(text=s.get('product','NEEV23.USA.0000.V015.012.260507'))
if name=='md5':
 p=Path(a[0]);h=s.get('hashes',{}).get(p.name) if str(p).startswith(str(root/'system/lib')) else None
 if not p.is_file():done(1,'missing')
 done(text=(h or hashlib.md5(p.read_bytes()).hexdigest())+'  '+str(p))
if name=='sync':done()
s.setdefault('calls',[]).append(a)
mod='egmp-carplay-ev-routing';folder=root/'data/hmgapi/mods'/mod;helper=root/'system/lib/libcarplayev.so'
if a==['version']:done(text='hmgapid '+s.get('version','1.0.0-beta.6'))
if a==['mods']:
 text='wireless-carplay-aa\t'+s.get('wireless','enabled')
 if folder.exists():text+='\n'+mod+'\t'+('disabled' if (folder/'.disabled').exists() else 'enabled')
 done(text=text)
if a[0] in ('enable','disable'):
 if not folder.exists():done(1,'missing mod')
 flag=folder/'.disabled'
 if a[0]=='disable':flag.touch()
 else:flag.unlink(missing_ok=True)
 done()
if a[:2]==['engine','verify-map']:done(1 if s.get('bad_map') else 0,'map check failed' if s.get('bad_map') else '331 of 331 hook points resolve')
if a[:2]==['engine','install']:
 if s.get('fail_import_once'):s['fail_import_once']=False;done(1,'refused import')
 source=Path(a[2])
 if folder.exists():shutil.rmtree(folder)
 folder.mkdir(parents=True)
 if source.is_dir():shutil.copytree(source,folder,dirs_exist_ok=True)
 else:
  with zipfile.ZipFile(source) as z:z.extractall(folder)
 done(text='installed signed mod')
if a[:2]==['engine','plan']:
 if s.get('bad_plan') and folder.exists():done(text="hooks    "+mod+" won't load: conflicting hook")
 if s.get('pending_other'):done(text='change   '+str(root/'system/lib/other.so')+' [other]')
 done(text=('change   '+str(helper)+' ['+mod+']') if folder.exists() and not (folder/'.disabled').exists() else 'nothing to do')
if a[:2]==['engine','recover']:
 journal=root/'data/hmgapi/state/journal.json'
 if journal.exists():journal.unlink();helper.unlink(missing_ok=True)
 done(text='recovered')
if a[:2]==['engine','remove']:
 if folder.exists():shutil.rmtree(folder)
 done(text='removed')
if a[:2]==['engine','apply']:
 if s.get('fail_apply_once'):
  s['fail_apply_once']=False;helper.write_bytes(b'partial');(root/'data/hmgapi/state/journal.json').write_text('{}');done(1,'apply failed')
 if folder.exists() and not (folder/'.disabled').exists():shutil.copyfile(folder/'files/libcarplayev.so',helper)
 else:helper.unlink(missing_ok=True)
 selected=root/'system/etc/carplay.ev-routing/profile.json'
 if folder.exists() and not (folder/'.disabled').exists():
  model=s.get('product','NEEV23.USA.0000.260507').split('.')[0].lower();selected.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(folder/'profiles'/(model+'.json'),selected)
 else:selected.unlink(missing_ok=True)
 done(text='done')
done(1,'unknown mock operation '+str(a))
'''
class Lifecycle(unittest.TestCase):
 def tearDown(self):self.temp.cleanup()
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)/'hu';self.pkg=Path(self.temp.name)/'package';shutil.copytree(STAGE,self.pkg)
  self.statefile=Path(self.temp.name)/'state.json';self.state={'root':str(self.root),'hashes':CONFIG['native_md5']};self.save()
  self.data=self.root/'data/hmgapi';self.backup=self.root/'data/nemesis/backup/carplay.ev-routing';self.mod=self.data/'mods'/CONFIG['mod_id']
  for d in ['system/bin','system/lib','system/app','data/hmgapi/mods','data/hmgapi/state']:(self.root/d).mkdir(parents=True,exist_ok=True)
  self.oem={}
  for n in ['system/lib/libiap2session.so','system/lib/libiap2client.so','system/lib/wireless.so','system/app/Iap2ServerApp.apk','system/app/Iap2ServerApp.odex']:
   p=self.root/n;p.write_bytes(('original '+n).encode());self.oem[n]=p.read_bytes()
  tools=Path(self.temp.name)/'bin';tools.mkdir()
  for name in ['hmgapid','getprop','md5','sync']:
   p=tools/name;p.write_text('#!'+sys.executable+'\n'+MOCK);p.chmod(0o755)
  for f in (self.pkg/'scripts').glob('*.sh'):
   s=f.read_text().replace('/system/bin/hmgapid',str(tools/'hmgapid'))
   for path in ['/data/hmgapi','/data/nemesis','/data/local/tmp/nemesis','/system/lib']:
    s=s.replace(path,str(self.root)+path)
   f.write_text(s)
  self.env=dict(os.environ,EVTEST_STATE=str(self.statefile),PATH=str(tools)+':'+os.environ['PATH'])
 def save(self):self.statefile.write_text(json.dumps(self.state))
 def change(self,**kw):self.state.update(json.loads(self.statefile.read_text()));self.state.update(kw);self.save()
 def invoke(self,name,ok=True):
  p=subprocess.run(['/bin/bash',str(self.pkg/'scripts'/name)],env=self.env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  self.assertEqual(p.returncode==0,ok,p.stdout)
  for n,data in self.oem.items():self.assertEqual((self.root/n).read_bytes(),data,'OEM changed: '+n)
  return p.stdout
 def install(self):self.invoke('pre.sh');self.invoke('post.sh')
 def test_install_and_uninstall(self):
  self.install();self.assertTrue((self.backup/'managed').exists());self.assertTrue((self.root/'system/lib/libcarplayev.so').exists());self.invoke('preun.sh');self.assertFalse(self.mod.exists());self.assertFalse((self.root/'system/lib/libcarplayev.so').exists())
 def test_beta5_refused_before_import(self):
  self.change(version='1.0.0-beta.5');self.invoke('pre.sh',False);self.assertFalse(self.mod.exists())
 def test_newer_beta_and_stable_accepted(self):
  for v in ['1.0.0-beta.10','1.0.0','1.1.0']:
   self.change(version=v);self.invoke('pre.sh')
 def test_bad_version_refused(self):
  for v in ['1.0.0-beta.x','1.0.0-beta.0','1.0.0-rc.1','0.99.6','2.0.0']:
   self.change(version=v);self.invoke('pre.sh',False)
 def test_missing_wireless_refused(self):self.change(wireless='absent');self.invoke('pre.sh',False)
 def test_disabled_wireless_refused(self):self.change(wireless='disabled');self.invoke('pre.sh',False)
 def test_bad_map_refused(self):self.change(bad_map=True);self.invoke('pre.sh',False)
 def test_unknown_hash_refused(self):
  self.change(hashes={'libiap2session.so':'0'*32});output=self.invoke('pre.sh',False);self.assertIn('HASH_MISMATCH file=libiap2session.so expected='+CONFIG['native_md5']['libiap2session.so']+' actual='+'0'*32,output);self.assertIn('Native library hash mismatch',output);self.assertFalse(self.mod.exists())
 def test_unknown_model_refused(self):self.change(product='OTHER.USA.260507');self.invoke('pre.sh',False)
 def test_alternate_region_and_firmware_accepted(self):
  self.change(product='NEEV23.CAN.0000.260903');self.install();self.assertEqual(json.loads((self.root/'system/etc/carplay.ev-routing/profile.json').read_text())['vehicle']['model'],'Ioniq 5')
 def test_ev6_profile_selected(self):
  self.change(product='CVEV.USA.0000.260507');self.install();self.assertEqual(json.loads((self.root/'system/etc/carplay.ev-routing/profile.json').read_text())['vehicle']['model'],'EV6')
 def test_payload_tamper_refused(self):
  with (self.pkg/'payload/mod.zip').open('ab') as f:f.write(b'tamper')
  self.invoke('pre.sh',False)
 def test_existing_journal_refused(self):
  (self.data/'state/journal.json').touch();self.invoke('pre.sh',False)
 def test_unrelated_pending_changes_refused(self):self.change(pending_other=True);self.invoke('pre.sh',False)
 def test_signed_import_failure_restores_absence(self):
  self.change(fail_import_once=True);self.invoke('pre.sh');self.invoke('post.sh',False);self.assertFalse(self.mod.exists());self.assertFalse((self.backup/'transaction').exists())
 def test_hook_refusal_rolls_back(self):
  self.change(bad_plan=True);self.invoke('post.sh',False);self.assertFalse(self.mod.exists());self.assertFalse((self.root/'system/lib/libcarplayev.so').exists())
 def test_apply_failure_recovers(self):
  self.change(fail_apply_once=True);self.invoke('post.sh',False);self.assertFalse(self.mod.exists());self.assertFalse((self.root/'system/lib/libcarplayev.so').exists());self.assertFalse((self.data/'state/journal.json').exists())
 def test_update_retains_mod_until_replacement(self):
  self.install();stage=self.root/'data/local/tmp/nemesis/stage/carplay.ev-routing';stage.mkdir(parents=True);(stage/'hmgapi-wrapper-v1').touch();self.invoke('preun.sh');self.assertTrue(self.mod.exists());self.invoke('pre.sh');self.invoke('post.sh');self.assertFalse((self.backup/'previous-mod').exists())
 def test_failed_update_restores_disabled_mod(self):
  self.install();(self.mod/'.disabled').touch();before=(self.mod/'mod.json').read_bytes();self.change(fail_apply_once=True);self.invoke('post.sh',False);self.assertEqual((self.mod/'mod.json').read_bytes(),before);self.assertTrue((self.mod/'.disabled').exists())
 def test_successful_update_keeps_disabled_choice(self):
  self.install();(self.mod/'.disabled').touch();self.invoke('post.sh');self.assertTrue((self.mod/'.disabled').exists())
 def test_recovery_snapshot_blocks_uninstall(self):
  self.install();(self.backup/'transaction').write_text('pending');self.invoke('preun.sh',False);self.assertTrue(self.mod.exists())
 def test_committed_recovery_finishes_cleanup(self):
  self.install();(self.backup/'transaction').write_text('committed');(self.backup/'previous-mod').mkdir();self.invoke('recover.sh');self.assertTrue(self.mod.exists());self.assertFalse((self.backup/'transaction').exists())
 def test_interrupted_snapshot_copy_leaves_existing_mod_untouched(self):
  self.install();before=(self.mod/'mod.json').read_bytes();(self.backup/'transaction').write_text('preparing');(self.backup/'previous-mod').mkdir();(self.backup/'previous-mod/partial').touch();self.invoke('recover.sh');self.assertEqual((self.mod/'mod.json').read_bytes(),before);self.assertFalse((self.backup/'previous-mod').exists());self.assertFalse((self.backup/'transaction').exists())
 def test_pending_update_recovery_restores_snapshot(self):
  self.install();before=(self.mod/'mod.json').read_bytes();shutil.copytree(self.mod,self.backup/'previous-mod');(self.backup/'previous-state').write_text('enabled');(self.backup/'transaction').write_text('pending');(self.mod/'mod.json').write_text('{}');self.invoke('recover.sh');self.assertEqual((self.mod/'mod.json').read_bytes(),before);self.assertFalse((self.backup/'transaction').exists())
 def test_uninstall_does_not_remove_unmanaged_mod(self):
  self.invoke('preun.sh');self.assertFalse(self.backup.exists())
class Package(unittest.TestCase):
 def test_outer_metadata_and_no_oem(self):
  meta=configparser.ConfigParser();meta.read(STAGE/'NEMESIS.data');n=meta['nemesis'];self.assertEqual(n['depends'],'hmgapi@>=0.99.6');self.assertEqual(n['package-version'],CONFIG['version']);self.assertEqual(meta['app.0']['kind'],'sh')
  for p in STAGE.rglob('*'):
   if p.is_file():self.assertNotIn(p.suffix,['.apk','.odex','.key','.pem','.so'])
 def test_profiles_signed_and_hashes_match(self):
  for line in (STAGE/'payload/profiles.tsv').read_text().splitlines():
   prefix,firmware,name,expected=line.split();p=STAGE/'payload'/name;self.assertEqual(hashlib.md5(p.read_bytes()).hexdigest(),expected)
   with zipfile.ZipFile(p) as z:
    self.assertIsNone(z.testzip());meta=json.loads(z.read('mod.json'));self.assertEqual(meta['version'],CONFIG['version']);self.assertEqual(meta['hmgapi'],'>=1.1.0-rc.3 <2.0.0');self.assertEqual(meta['requires'],['wireless-carplay-aa >=2.2.0-rc.4 <3.0.0']);self.assertNotIn('hooks.jar',z.namelist());manifest=json.loads(z.read('META-HMGAPI/manifest.json'));self.assertEqual(manifest['uuid'],CONFIG['author_uuid'])
    for n,h in manifest['files'].items():self.assertEqual(hashlib.sha256(z.read(n)).hexdigest(),h)
    self.assertEqual(hashlib.sha256(z.read('files/libcarplayev.so')).hexdigest(),json.loads((R/'out/build-report.json').read_text())['native_sha256'])
 def test_single_native_download_contains_both_profiles(self):
  stem='egmp-carplay-ev-routing-v'+CONFIG['version'];native=R/'dist'/(stem+'.zip')
  self.assertEqual(native.read_bytes(),(STAGE/'payload/mod.zip').read_bytes())
  with zipfile.ZipFile(native) as z:
   self.assertIn('mod.json',z.namelist());self.assertIn('META-HMGAPI/signature',z.namelist());self.assertNotIn('NEMESIS.data',z.namelist())
   meta=json.loads(z.read('mod.json'));self.assertEqual(meta['system'],[{'model':['NEEV23','NEEV','CVEV']}])
   expected={'NEEV23':[58000,72600,77400,84000],'NEEV':[58000,72600,77400,84000],'CVEV':[58000,77400,84000]}
   for entry in CONFIG['profiles']:
    name='profiles/'+entry['model'].lower()+'.json';self.assertIn(name,z.namelist());profile=json.loads(z.read(name));self.assertEqual(profile['supported_capacities_wh'],expected[entry['model']]);self.assertEqual(sorted(map(int,profile['capacity_profiles'])),expected[entry['model']])
    for capacity in expected[entry['model']]:self.assertEqual(profile['capacity_profiles'][str(capacity)]['charging_contract']['vehicle_parameters']['vehicle_energy_axis_wh'][-1],capacity)
  for entry in CONFIG['profiles']:self.assertFalse((R/'dist'/(stem+'-'+entry['model'].lower()+'-usa-'+entry['firmware']+'.zip')).exists())
 def test_usb_and_source_allowlist(self):
  stem='egmp-carplay-ev-routing-v'+CONFIG['version']
  with zipfile.ZipFile(R/'dist/testing'/(stem+'-nemesis-usb.zip')) as z:
   self.assertIn('path=packages/'+stem+'-nemesis-package.zip',z.read('NEMESIS.data').decode());self.assertEqual(z.read('packages/'+stem+'-nemesis-package.zip'),(R/'dist/testing'/(stem+'-nemesis-package.zip')).read_bytes())
  with zipfile.ZipFile(R/'dist'/(stem+'-source.zip')) as z:
   for n in z.namelist():self.assertFalse(any(x in Path(n).parts for x in ['out','dist','private','analysis','.git','__pycache__']));self.assertFalse(n.endswith(('.key','.apk','.odex','.so','.jar','.log')))
if __name__=='__main__':unittest.main(verbosity=2)
