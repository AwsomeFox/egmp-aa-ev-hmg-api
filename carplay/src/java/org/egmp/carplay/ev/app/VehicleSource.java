package org.egmp.carplay.ev.app;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.*;
import java.lang.reflect.*;
import org.json.*;
/** Read-only HSCAN/HEV adapter. No network, automatic profile updates or fake readings. */
public final class VehicleSource {
    static final long AUXILIARY_WINDOW_NS=60000000000L;
    static final int AUXILIARY_ROUND_W=25;
    static final int CAPACITY_TOLERANCE_WH=1000;
    public static final class Snapshot {
        public final int[] values;
        public final byte[] charging,consumption;
        public final String auxiliarySource;
        public final double auxiliaryW;
        public final String batterySource;
        Snapshot(int[] v,byte[] c,byte[] e,String source,double watts,String battery){values=v;charging=c;consumption=e;auxiliarySource=source;auxiliaryW=watts;batterySource=battery;}
    }
    private final Object service;
    private final int minWh;
    private final JSONObject capacityProfiles;
    private final String consumption;
    private int maxWh;
    private String charging,batterySource="unresolved";
    private String batterySelectionEvent;
    public final String displayName;
    public final int dcW,acW;
    private Object adapter,binder;
    private final AuxiliaryAverage auxiliaryAverage;
    static final class AuxiliaryAverage {
        private int published;
        private long started=-1,sum;
        private int count;
        private boolean averaged;
        AuxiliaryAverage(int initial){published=initial;}
        int update(Integer sample,long now){
            if(started<0)started=now;
            else if(now<started){started=now;sum=0;count=0;}
            if(now-started>=AUXILIARY_WINDOW_NS){
                if(count>0){published=(int)(Math.round(((double)sum/count)/AUXILIARY_ROUND_W)*AUXILIARY_ROUND_W);averaged=true;}
                started=now;sum=0;count=0;
            }
            if(sample!=null){int value=sample.intValue();if(value<0 || value>30000)throw new IllegalArgumentException("auxiliary sample");sum+=value;count++;}
            return published;
        }
        boolean hasAverage(){return averaged;}
    }
    public VehicleSource(Object service,String source) throws Exception {this(service,source,0);}
    public VehicleSource(Object service,String source,int batteryCapacityOverrideWh) throws Exception {
        this.service=service;JSONObject p=new JSONObject(source);
        if(p.getInt("schema_version")!=1)throw new IllegalArgumentException("profile schema");
        displayName=p.getJSONObject("vehicle").getString("display_name");
        if(displayName.length()==0 || displayName.length()>80 || displayName.indexOf(0)>=0)throw new IllegalArgumentException("display name");
        JSONArray connectors=p.getJSONArray("connectors");int dc=0,ac=0;
        if(connectors.length()!=2)throw new IllegalArgumentException("connectors");
        for(int i=0;i<2;i++){JSONObject c=connectors.getJSONObject(i);int w=c.getInt("maximum_power_w");String type=c.getString("type");
            if(w<1 || w>1000000)throw new IllegalArgumentException("connector power");
            if(type.equals("ccs1") && dc==0)dc=w;else if(type.equals("j1772") && ac==0)ac=w;else throw new IllegalArgumentException("connector type");}
        dcW=dc;acW=ac;
        JSONObject battery=p.getJSONObject("battery");minWh=battery.getInt("minimum_energy_wh");
        if(minWh!=0 || p.getInt("reference_capacity_wh")!=77400)throw new IllegalArgumentException("unsupported reference capacity");
        capacityProfiles=p.getJSONObject("capacity_profiles");if(capacityProfiles.length()==0)throw new IllegalArgumentException("capacity profiles");
        JSONObject ch=p.getJSONObject("charging_contract"),co=p.getJSONObject("consumption_contract");
        if(ch.getInt("model_id")!=12582922 || co.getInt("model_id")!=12582921)throw new IllegalArgumentException("contract roles");
        JSONObject cp=ch.getJSONObject("vehicle_parameters"),vp=co.getJSONObject("vehicle_parameters");
        if(cp.getJSONArray("vehicle_energy_axis_wh").length()!=11 || cp.getJSONArray("vehicle_charge_axis_w").length()!=11 || cp.getJSONArray("energy_w_per_h").length()!=21 || vp.getJSONArray("vehicle_consumption_values_wh_per_m").length()!=10)throw new IllegalArgumentException("contract shape");
        consumption=co.toString();bounded(consumption);
        double aux=co.getJSONObject("vehicle_parameters").getDouble("vehicle_auxiliary_power_w");
        if(Double.isNaN(aux) || Double.isInfinite(aux) || aux<0 || aux>30000)throw new IllegalArgumentException("auxiliary default");
        auxiliaryAverage=new AuxiliaryAverage((int)Math.round(aux));
        if(batteryCapacityOverrideWh!=0)resolveOverride(batteryCapacityOverrideWh);
    }
    static int selectCapacity(JSONObject profiles,int reported) throws Exception {
        JSONArray names=profiles.names();if(names==null || names.length()==0)throw new IOException("no capacity profiles");
        int best=0,bestDifference=Integer.MAX_VALUE;boolean tied=false;
        for(int i=0;i<names.length();i++){
            int candidate=Integer.parseInt(names.getString(i));int difference=Math.abs(candidate-reported);
            if(difference<bestDifference){best=candidate;bestDifference=difference;tied=false;}
            else if(difference==bestDifference)tied=true;
        }
        if(tied || bestDifference>CAPACITY_TOLERANCE_WH)throw new IOException("unexpected BMS energy capacity: reported="+reported+" Wh, nearest profile="+best+" Wh, difference="+bestDifference+" Wh, tolerance="+CAPACITY_TOLERANCE_WH+" Wh, supported="+names+(tied?", ambiguous tie":""));
        return best;
    }
    static String selectCharging(JSONObject profiles,int capacity) throws Exception {
        JSONObject selected=profiles.optJSONObject(String.valueOf(capacity));
        if(selected==null)throw new IOException("unsupported BMS_EVENERGYCAPA capacity_wh="+capacity+" supported="+profiles.names());
        if(selected.getInt("maximum_energy_wh")!=capacity)throw new IOException("capacity profile mismatch");
        JSONObject contract=selected.getJSONObject("charging_contract"),values=contract.getJSONObject("vehicle_parameters");
        if(contract.getInt("model_id")!=12582922 || values.getJSONArray("vehicle_energy_axis_wh").getInt(10)!=capacity)throw new IOException("invalid capacity profile "+capacity);
        String encoded=contract.toString();bounded(encoded);return encoded;
    }
    private void resolveBattery(int reported) throws Exception {
        int selected=selectCapacity(capacityProfiles,reported),difference=Math.abs(selected-reported);
        charging=selectCharging(capacityProfiles,selected);maxWh=selected;
        batterySource="bms_evenergycapa_reported_"+reported+"_profile_"+selected+"_difference_"+difference;
        batterySelectionEvent="battery profile selected: source=BMS reported="+reported+" Wh, profile="+selected+" Wh, difference="+difference+" Wh";
    }
    private void resolveOverride(int selected) throws Exception {
        try {charging=selectCharging(capacityProfiles,selected);}
        catch(IOException failure){throw new IOException("unsupported battery capacity override capacity_wh="+selected+" supported="+capacityProfiles.names());}
        maxWh=selected;batterySource="settings_override_profile_"+selected;
        batterySelectionEvent="battery profile selected: source=settings override, profile="+selected+" Wh";
    }
    String batterySelection(){return maxWh==0?"automatic_bms":batterySource;}
    synchronized String takeBatterySelectionEvent(){String event=batterySelectionEvent;batterySelectionEvent=null;return event;}
    public static String readFile(String path) throws Exception {
        InputStream in=new FileInputStream(path);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>32768)throw new IOException("file too large");out.write(b,0,n);}
            return new String(out.toByteArray(),"UTF-8");
        } finally {in.close();}
    }
    private static byte[] bounded(String value) throws Exception {
        byte[] b=value.getBytes("UTF-8");if(b.length==0 || b.length>=8192 || value.indexOf(0)>=0)throw new IllegalArgumentException("contract length/NUL");return b;
    }
    private int hev(int transaction,Integer flag) throws Exception {
        Class<?> pc=Class.forName("android.os.Parcel"),ib=Class.forName("android.os.IBinder");
        if(binder==null)binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"com.hkmc.micom.hev");
        if(binder==null)throw new IOException("HEV unavailable");
        Object data=pc.getMethod("obtain").invoke(null),reply=pc.getMethod("obtain").invoke(null);
        try {
            pc.getMethod("writeInterfaceToken",String.class).invoke(data,"com.hkmc.hev.IHEVService");
            if(flag!=null)pc.getMethod("writeInt",int.class).invoke(data,flag);
            if(!Boolean.TRUE.equals(ib.getMethod("transact",int.class,pc,pc,int.class).invoke(binder,transaction,data,reply,0)))throw new IOException("HEV refused");
            pc.getMethod("readException").invoke(reply);return ((Integer)pc.getMethod("readInt").invoke(reply)).intValue();
        } finally {pc.getMethod("recycle").invoke(data);pc.getMethod("recycle").invoke(reply);}
    }
    static int auxiliary(int c,int e,int b,int h){
        if(c<0 || c>=65535 || e<0 || e>=255 || b<0 || b>=255 || h<0 || h>=255)throw new IllegalArgumentException("auxiliary raw");
        int w=c*20+e*10+b*20+h*100;if(w>30000)throw new IllegalArgumentException("auxiliary watts");return w;
    }
    /** Match Hyundai's EV app: DISPLAY_SOC is shown as a floored whole percent. */
    static int displayedSoc(double percent){
        if(Double.isNaN(percent) || Double.isInfinite(percent) || percent<0 || percent>100)throw new IllegalArgumentException("SOC bounds");
        return (int)Math.floor(percent);
    }
    public synchronized Snapshot poll() throws Exception {
        Double soc=null;Integer range=null,auxiliarySample=null;long sampledNs=0;
        try {
            if(adapter==null){
                ClassLoader parent=(ClassLoader)service.getClass().getMethod("getClassLoader").invoke(service);
                ClassLoader hcan=(ClassLoader)Class.forName("dalvik.system.PathClassLoader").getConstructor(String.class,ClassLoader.class).newInstance("/system/framework/com.hkmc.ext.hcan.jar",parent);
                Class<?> cls=hcan.loadClass("com.hkmc.telematics.hscan.tm_interface.TM_HSCANAdapter");
                adapter=cls.getMethod("getDefaultAdapter",Class.forName("android.content.Context")).invoke(null,service);
            }
            Object s=adapter.getClass().getMethod("remainBatteryPercentage").invoke(adapter);
            Object r=adapter.getClass().getMethod("ableDriveDistance",byte.class).invoke(adapter,Byte.valueOf((byte)0));
            if(!(s instanceof Number) || !(r instanceof Integer))throw new IOException("HSCAN types");
            double pct=((Number)s).doubleValue();int km=((Integer)r).intValue();
            if(km<0 || km>=2000)throw new IOException("HSCAN bounds");
            soc=Double.valueOf(displayedSoc(pct));range=Integer.valueOf(km);sampledNs=System.nanoTime();
        } catch(Exception failure){adapter=null;}
        Exception capacityFailure=null;
        try {
            if(hev(27,null)!=2)throw new IOException("CAN generation");
            if(maxWh==0){int raw=hev(3,0x35);long wh=(long)raw*2L;if(wh<10000 || wh>200000)throw new IOException("unexpected BMS energy capacity: raw="+raw+", reported="+wh+" Wh");resolveBattery((int)wh);}
            auxiliarySample=Integer.valueOf(auxiliary(hev(3,0x4f),hev(3,0x24),hev(3,0x33),hev(3,0x43)));
        } catch(Exception failure){binder=null;capacityFailure=failure;}
        if(maxWh==0)throw new IOException(capacityFailure==null?"BMS energy capacity unavailable":capacityFailure.getMessage());
        long now=System.nanoTime();if(!fresh(sampledNs,now)){soc=null;range=null;}
        int publishedAuxiliary=auxiliaryAverage.update(auxiliarySample,now);
        return encode(minWh,maxWh,charging,consumption,soc,range,Integer.valueOf(publishedAuxiliary),auxiliaryAverage.hasAverage(),batterySource);
    }
    static boolean fresh(long sampled,long now){long age=now-sampled;return sampled!=0 && age>=0 && age<=15000000000L;}
    static Snapshot encode(int min,int max,String ch,String co,Double percent,Integer range,Integer aux,boolean liveAux) throws Exception {
        return encode(min,max,ch,co,percent,range,aux,liveAux,"test");
    }
    static Snapshot encode(int min,int max,String ch,String co,Double percent,Integer range,Integer aux,boolean liveAux,String batterySource) throws Exception {
        int[] values={min,max,0,0,0,0,0,0,0};
        if(percent!=null && range!=null){
            double p=percent.doubleValue();int r=range.intValue();
            if(Double.isNaN(p) || Double.isInfinite(p) || p<0 || p>100 || r<0 || r>=2000)throw new IllegalArgumentException("sample");
            int soc=(int)Math.round(p*1000.0);values[2]=(int)(((long)max*soc+50000L)/100000L);values[3]=soc;values[4]=r;values[5]=soc<=20000?1:0;values[7]=1;
            if(soc>0){long full=Math.round((double)r*100000.0/soc);if(full>=r && full<2000){values[6]=(int)full;values[8]=1;}}
        }
        JSONObject consumption=new JSONObject(co);
        if(aux!=null){if(aux.intValue()<0 || aux.intValue()>30000)throw new IllegalArgumentException("auxiliary watts");consumption.getJSONObject("vehicle_parameters").put("vehicle_auxiliary_power_w",aux.doubleValue());}
        return new Snapshot(values,bounded(ch),bounded(consumption.toString()),aux==null?"profile_default":liveAux?"one_minute_average":"profile_default",consumption.getJSONObject("vehicle_parameters").getDouble("vehicle_auxiliary_power_w"),batterySource);
    }
}
