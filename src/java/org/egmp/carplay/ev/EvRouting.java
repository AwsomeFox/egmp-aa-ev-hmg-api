package org.egmp.carplay.ev;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import hmgapi.hook.*;
import hmgapi.mods.wireless.api.CarPlayIdentification;
import org.json.*;
/** EV routing provider for HMG-API beta6 and newer. */
public final class EvRouting extends ModEntry {
    private static final String SESSION_MD5="fc7cb675b46a44bd893a1edc054c5e71";
    private String profile;
    private boolean librariesMatch;
    private String sessionMd5;
    private ModContext runningMod;
    private Class<?> part;
    private CarPlayIdentification identityApi;
    private CarPlayIdentification.VehicleIdentity identityContribution;
    private int batteryCapacityOverrideWh;
    public void zygoteInit(ModContext mod) throws Throwable {
        sessionMd5=ModContext.md5("/system/lib/libiap2session.so");
        librariesMatch=SESSION_MD5.equals(sessionMd5);
        // Read before dropping root: app processes cannot traverse the mod folder.
        File f=new File("/system/etc/carplay.ev-routing/profile.json");
        java.io.InputStream in=new java.io.FileInputStream(f);
        try {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>32768)throw new java.io.IOException("profile too large");out.write(b,0,n);}profile=new String(out.toByteArray(),"UTF-8");
        } finally {in.close();}
        identityApi=identityApi(mod);
        identityContribution=vehicleIdentity(new JSONObject(profile));
        batteryCapacityOverrideWh=batteryCapacityOverride(mod.settingString("battery_capacity_wh_override","auto"));
        mod.log("CarPlayEV battery_capacity_setting="+(batteryCapacityOverrideWh==0?"automatic_bms":batteryCapacityOverrideWh+"_wh_override"));
        if(identityApi!=null)identityApi.registerVehicle(mod.id,identityContribution);
    }
    private static int batteryCapacityOverride(String value) {
        if(value==null || "auto".equals(value))return 0;
        try {int wh=Integer.parseInt(value);if(wh>0)return wh;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("invalid battery_capacity_wh_override="+value);
    }
    private static CarPlayIdentification identityApi(ModContext mod) {
        Object published=mod.use("wireless-carplay-aa",CarPlayIdentification.SERVICE);
        if(!(published instanceof CarPlayIdentification)){
            mod.log("CarPlayEV: wireless-carplay-aa did not publish "+CarPlayIdentification.SERVICE
                +"; continuing without the vehicle identity contribution");
            return null;
        }
        return (CarPlayIdentification)published;
    }
    private static CarPlayIdentification.VehicleIdentity vehicleIdentity(JSONObject profile) throws Exception {
        String name=profile.getJSONObject("vehicle").getString("display_name");
        JSONArray source=profile.getJSONArray("connectors");
        CarPlayIdentification.Connector[] connectors=new CarPlayIdentification.Connector[source.length()];
        for(int i=0;i<source.length();i++){
            JSONObject connector=source.getJSONObject(i);String type=connector.getString("type");
            int kind;
            if("ccs1".equals(type) || "ccs2".equals(type))kind=CarPlayIdentification.VehicleIdentity.CONNECTOR_CCS;
            else if("j1772".equals(type) || "type2".equals(type))kind=CarPlayIdentification.VehicleIdentity.CONNECTOR_AC;
            else if("chademo".equals(type))kind=CarPlayIdentification.VehicleIdentity.CONNECTOR_CHADEMO;
            else throw new IllegalArgumentException("unsupported CarPlay connector type "+type);
            connectors[i]=new CarPlayIdentification.Connector(kind,connector.getInt("maximum_power_w"));
        }
        return new CarPlayIdentification.VehicleIdentity(name,CarPlayIdentification.VehicleIdentity.ENGINE_ELECTRIC,connectors);
    }
    public void appLoaded(final ModContext mod,final LoadedApp app) throws Throwable {
        if(!"com.tcc.iap2server".equals(app.packageName))return;
        if(!librariesMatch){mod.error("CarPlayEV: native hash mismatch; EV routing inactive"
            +" libiap2session.so_expected="+SESSION_MD5
            +" libiap2session.so_actual="+(sessionMd5==null?"unavailable":sessionMd5)
            +" libiap2session.so_status="+(SESSION_MD5.equals(sessionMd5)?"match":"MISMATCH"),null);return;}
        String[] points={"iap2.service.on_create","iap2.service.on_destroy","iap2.service.on_bind","iap2.service.native_handle","iap2.service.native_lock"};
        for(String id:points)if(!Points.has(id)){mod.error("CarPlayEV: missing hook map "+id+"; inactive",null);return;}
        runningMod=mod;part=mod.appPart(app,"org.egmp.carplay.ev.app.AppProvider");
        mod.hook(app.classLoader,"iap2.service.on_create",new MethodHook(){
            protected void afterHookedMethod(Param p) throws Throwable {
                if(p.hasThrowable())return;
                // All native calls must resolve in the app-part loader.
                Class<?> bridge=mod.appPart(app,"org.egmp.carplay.ev.app.NativeBridge");
                bridge.getMethod("load",String.class).invoke(null,"/system/lib/libcarplayev.so");
                part.getMethod("start",ModContext.class,LoadedApp.class,Object.class,String.class,int.class).invoke(null,mod,app,p.thisObject,profile,Integer.valueOf(batteryCapacityOverrideWh));
            }
        });
        mod.hook(app.classLoader,"iap2.service.on_destroy",new MethodHook(){
            protected void beforeHookedMethod(Param p) throws Throwable {stop();}
        });
    }
    private void stop() throws Throwable {if(part!=null)part.getMethod("stop").invoke(null);}
    public void onEnabled(ModContext mod,boolean enabled) throws Throwable {
        if(enabled){
            if(identityApi!=null && identityContribution!=null)
                identityApi.registerVehicle(mod.id,identityContribution);
        }else{
            if(identityApi!=null)identityApi.removeVehicle(mod.id);
            if(runningMod==mod)stop();
        }
        // Re-enable requires service restart so a new stock listener can register.
    }
}
