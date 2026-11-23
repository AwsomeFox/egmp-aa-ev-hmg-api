package org.egmp.carplay.ev.app;
// SPDX-License-Identifier: GPL-3.0-or-later
import android.os.IBinder;
import android.os.RemoteException;
import android.content.Intent;
import com.tcc.iap2.*;
import hmgapi.hook.*;
/** Loaded through ModContext.appPart: OEM listener classes resolve in Iap2ServerApp. */
public final class AppProvider {
    private static AppProvider instance;
    private final ModContext mod;
    private final LoadedApp app;
    private final Object service;
    private final VehicleSource source;
    private final Subscription subscription=new Subscription();
    private IIap2Service binder;
    private Thread worker;
    private long workerToken;
    private boolean closed;
    private String lastFailure;
    private final IIap2Listener listener=new IIap2Listener.Stub(){
        public void connected(int component,String serial){subscription.connected(component);}
        public void disconnected(int component){if(subscription.disconnected(component))release();}
        public void notify(int what,int a,int b,Iap2NotiMsg msg){
            if(what==15)begin();
            else if(what==16){subscription.stop();release();}
        }
    };
    private AppProvider(ModContext mod,LoadedApp app,Object service,String profile,int batteryCapacityOverrideWh) throws Exception {
        this.mod=mod;this.app=app;this.service=service;source=new VehicleSource(service,profile,batteryCapacityOverrideWh);
    }
    public static synchronized void start(ModContext mod,LoadedApp app,Object service,String profile,int batteryCapacityOverrideWh) throws Throwable {
        if(instance!=null)return;
        AppProvider next=new AppProvider(mod,app,service,profile,batteryCapacityOverrideWh);
        try {
        int opened=NativeBridge.open();if(opened!=0)throw new IllegalStateException("native open="+opened);
        Object raw=Points.call(app.classLoader,"iap2.service.on_bind",service,new Intent());
        next.binder=IIap2Service.Stub.asInterface((IBinder)raw);
        Iap2ListenerInfo info=new Iap2ListenerInfo();info.onSetListenerName("CarPlayEV");info.onSetSupportType(0x100);
        instance=next;
        if(!next.binder.Iap2SvcInit(next.listener,info))throw new IllegalStateException("listener refused");
        } catch(Throwable failure){instance=null;next.close();throw failure;}
        mod.log("CarPlayEV listener registered");
    }
    public static synchronized void stop(){if(instance!=null){instance.close();instance=null;}}
    private synchronized void begin(){
        final long token=subscription.start();if(token==0)return;
        if(worker!=null && worker.isAlive() && workerToken==token)return;
        if(worker!=null)worker.interrupt();workerToken=token;
        worker=new Thread(new Runnable(){public void run(){
            try {
                while(subscription.current(token) && mod.enabled()){
                    try {
                        VehicleSource.Snapshot snapshot=source.poll();
                        String selection=source.takeBatterySelectionEvent();
                        if(selection!=null)mod.log("CarPlayEV "+selection);
                        Object lock=Points.get(app.classLoader,"iap2.service.native_lock",null);
                        synchronized(lock){
                            if(!subscription.current(token) || !mod.enabled())return;
                            long handle=((Number)Points.get(app.classLoader,"iap2.service.native_handle",null)).longValue();
                            int rc=NativeBridge.send(handle,snapshot.values,snapshot.charging,snapshot.consumption);
                            mod.log(statusLine(rc,snapshot));
                        }
                        lastFailure=null;
                    } catch(Throwable error){
                        String message=message(error);
                        if(!message.equals(lastFailure)){
                            mod.error("CarPlayEV EV status unavailable; will retry: "+message,null);
                            lastFailure=message;
                        }
                    }
                    Thread.sleep(10000);
                }
            } catch(InterruptedException ended){Thread.currentThread().interrupt();}
        }},"CarPlayEV");worker.setDaemon(true);worker.start();
    }
    private static String message(Throwable error){
        Throwable cause=error;
        while(cause.getCause()!=null && cause.getCause()!=cause)cause=cause.getCause();
        return cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage();
    }
    private static String statusLine(int rc,VehicleSource.Snapshot snapshot){
        String live=snapshot.values[7]==1
            ?"soc="+(snapshot.values[3]/1000)+"% range="+snapshot.values[4]+" km energy="+snapshot.values[2]+" Wh"
            :"soc/range unavailable";
        String full=snapshot.values[8]==1?" full_range="+snapshot.values[6]+" km":"";
        return "CarPlayEV status sent: result="+rc+" "+live+full+" auxiliary="+snapshot.auxiliaryW+" W ("+snapshot.auxiliarySource+") low_battery="+(snapshot.values[5]==1);
    }
    private void release(){
        try {
            Object lock=Points.get(app.classLoader,"iap2.service.native_lock",null);
            synchronized(lock){NativeBridge.reset();}
        } catch(Throwable error){mod.error("CarPlayEV release",error);}
    }
    private synchronized void close(){
        if(closed)return;closed=true;subscription.close();if(worker!=null)worker.interrupt();release();
        try{Object lock=Points.get(app.classLoader,"iap2.service.native_lock",null);synchronized(lock){NativeBridge.shutdown();}}catch(Throwable error){mod.error("native shutdown",error);}
        if(binder!=null)try{binder.Iap2SvcStopMain(listener);}catch(Throwable error){mod.error("listener unregister",error);}
    }
}
