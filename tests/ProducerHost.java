package org.egmp.carplay.ev.app;
// SPDX-License-Identifier: GPL-3.0-or-later
import org.json.*;
import java.nio.file.*;
import java.util.Arrays;
public final class ProducerHost {
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
    public static void main(String[] args) throws Exception {
        JSONArray fixtures=new JSONArray(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"));
        for(int i=0;i<fixtures.length();i++){
            JSONObject f=fixtures.getJSONObject(i);JSONArray s=f.getJSONArray("sample");int[] expected=new int[9];for(int n=0;n<9;n++)expected[n]=s.getInt(n);
            VehicleSource.Snapshot actual=VehicleSource.encode(expected[0],expected[1],f.getString("charging"),f.getString("consumption"),expected[7]==1?Double.valueOf(expected[3]/1000.0):null,expected[7]==1?Integer.valueOf(expected[4]):null,null,false);
            check(Arrays.equals(expected,actual.values),f.getString("name"));
            check(new JSONObject(new String(actual.charging,"UTF-8")).toString().equals(new JSONObject(f.getString("charging")).toString()),"charging");
            check(new JSONObject(new String(actual.consumption,"UTF-8")).toString().equals(new JSONObject(f.getString("consumption")).toString()),"consumption");
        }
        check(VehicleSource.auxiliary(0,31,0,0)==310,"observed auxiliary");
        check(VehicleSource.auxiliary(26,24,10,20)==2960,"mixed auxiliary");
        check(VehicleSource.auxiliary(0,0,0,0)==0,"zero auxiliary");
        check(VehicleSource.displayedSoc(0)==0 && VehicleSource.displayedSoc(0.5)==0,"floor zero half-step");
        check(VehicleSource.displayedSoc(74)==74 && VehicleSource.displayedSoc(74.5)==74,"floor display SOC");
        check(VehicleSource.displayedSoc(99.5)==99 && VehicleSource.displayedSoc(100)==100,"floor upper half-step");
        for(double bad:new double[]{-0.5,100.5,Double.NaN,Double.POSITIVE_INFINITY})try{VehicleSource.displayedSoc(bad);throw new AssertionError("accepted invalid SOC");}catch(IllegalArgumentException expected){}
        VehicleSource.AuxiliaryAverage avg=new VehicleSource.AuxiliaryAverage(400);
        for(int i=0;i<6;i++)check(avg.update(Integer.valueOf(300+i*10),i*10000000000L)==400,"hold auxiliary during first minute");
        check(avg.update(Integer.valueOf(1000),60000000000L)==325 && avg.hasAverage(),"publish rounded one-minute average");
        check(avg.update(null,70000000000L)==325,"hold published auxiliary on missing sample");
        check(avg.update(Integer.valueOf(1049),120000000000L)==1000,"publish available samples only");
        String profile=new String(Files.readAllBytes(Paths.get(args[1])),"UTF-8");
        new VehicleSource(null,profile);
        VehicleSource forced=new VehicleSource(null,profile,84000);
        check(forced.batterySelection().equals("settings_override_profile_84000"),"settings override selects compiled 84 kWh profile");
        check(forced.takeBatterySelectionEvent().indexOf("profile=84000 Wh")>=0 && forced.takeBatterySelectionEvent()==null,"settings override logs selection once");
        JSONObject p=new JSONObject(profile);
        String ch=p.getJSONObject("charging_contract").toString(),co=p.getJSONObject("consumption_contract").toString();
        JSONObject variants=p.getJSONObject("capacity_profiles");
        check(p.getJSONArray("supported_capacities_wh").toString().equals("[58000,72600,77400,84000]"),"Ioniq 5 compiled capacity list");
        JSONObject scaledValues=variants.getJSONObject("84000").getJSONObject("charging_contract").getJSONObject("vehicle_parameters");
        check(scaledValues.getJSONArray("vehicle_energy_axis_wh").getInt(10)==84000,"compiled capacity energy axis");
        check(scaledValues.getJSONArray("energy_w_per_h").getInt(20)==83620,"compiled secondary Wh axis");
        check(scaledValues.getJSONArray("vehicle_charge_axis_w").getInt(1)==235000,"compiled capacity does not scale charging power");
        JSONObject selected=new JSONObject(VehicleSource.selectCharging(variants,58000)).getJSONObject("vehicle_parameters");
        check(selected.getJSONArray("vehicle_energy_axis_wh").getInt(10)==58000,"runtime selects compiled 58 kWh profile");
        check(VehicleSource.selectCapacity(variants,77360)==77400,"capacity tolerance selects 77.4 kWh");
        check(VehicleSource.selectCapacity(variants,71600)==72600,"capacity tolerance accepts boundary");
        try{VehicleSource.selectCapacity(variants,71599);throw new AssertionError("accepted capacity outside tolerance");}catch(java.io.IOException expected){check(expected.getMessage().indexOf("difference=1001 Wh")>=0,"capacity tolerance diagnostic");}
        JSONObject tied=new JSONObject();tied.put("58000",new JSONObject());tied.put("60000",new JSONObject());
        try{VehicleSource.selectCapacity(tied,59000);throw new AssertionError("accepted tied capacity");}catch(java.io.IOException expected){check(expected.getMessage().indexOf("reported=59000 Wh")>=0 && expected.getMessage().indexOf("ambiguous tie")>=0,"capacity tie diagnostic");}
        try{VehicleSource.selectCharging(variants,63000);throw new AssertionError("accepted unsupported capacity");}catch(java.io.IOException expected){check(expected.getMessage().indexOf("capacity_wh=63000")>=0,"unsupported capacity diagnostic");}
        try{new VehicleSource(null,profile,63000);throw new AssertionError("accepted unsupported override");}catch(java.io.IOException expected){check(expected.getMessage().indexOf("unsupported battery capacity override")>=0,"unsupported override diagnostic");}
        String ev6Profile=new String(Files.readAllBytes(Paths.get(args[2])),"UTF-8");
        try{new VehicleSource(null,ev6Profile,72600);throw new AssertionError("accepted Ioniq 5-only override on EV6");}catch(java.io.IOException expected){check(expected.getMessage().indexOf("unsupported battery capacity override")>=0,"EV6 rejects 72.6 kWh override");}
        String ioniq6Profile=new String(Files.readAllBytes(Paths.get(args[4])),"UTF-8");
        VehicleSource ioniq6=new VehicleSource(null,ioniq6Profile,53000);
        check(ioniq6.batterySelection().equals("settings_override_profile_53000"),"Ioniq 6 selects compiled 53 kWh profile");
        JSONObject i6=new JSONObject(ioniq6Profile);
        check(i6.getJSONArray("supported_capacities_wh").toString().equals("[53000,77400]"),"Ioniq 6 compiled capacity list");
        check(i6.getJSONObject("capacity_profiles").getJSONObject("77400").getJSONObject("charging_contract").getJSONObject("vehicle_parameters").getJSONArray("vehicle_charge_axis_w").getInt(2)==233000,"Ioniq 6 charging peak");
        VehicleSource.Snapshot held=VehicleSource.encode(0,77400,ch,co,Double.valueOf(50),Integer.valueOf(206),Integer.valueOf(310),false);
        check(held.auxiliarySource.equals("profile_default") && held.auxiliaryW==310,"profile auxiliary before first average");
        check(VehicleSource.fresh(1,15000000001L) && !VehicleSource.fresh(1,15000000002L) && !VehicleSource.fresh(2,1),"sample freshness");
        Subscription gate=new Subscription();gate.connected(3);long a=gate.start();check(gate.current(a),"start");
        check(gate.start()==a,"duplicate start idempotent");check(!gate.disconnected(4) && gate.current(a),"unrelated component");
        gate.stop();check(!gate.current(a),"stop invalidates worker");long b=gate.start();check(b!=a && gate.current(b),"rapid resubscribe");
        gate.connected(5);check(!gate.current(b),"new transport invalidates old worker");long c=gate.start();check(gate.disconnected(5) && !gate.current(c),"matching disconnect");
        gate.close();check(gate.start()==0,"closed provider cannot restart");
        System.out.println("PASS producer fixtures, compiled capacity-profile selection, Hyundai SOC flooring, auxiliary averaging, and subscription lifecycle");
    }
}
