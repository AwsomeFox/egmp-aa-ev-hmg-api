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
    private final IIap2Listener listener=new IIap2Listener.Stub(){
        public void connected(int component,String serial){subscription.connected(component);}
        public void disconnected(int component){if(subscription.disconnected(component))release();}
        public void notify(int what,int a,int b,Iap2NotiMsg msg){
            if(what==15)begin();
            else if(what==16){subscription.stop();release();}
        }
    };
    private AppProvider(ModContext mod,LoadedApp app,Object service,String profile) throws Exception {
        this.mod=mod;this.app=app;this.service=service;source=new VehicleSource(service,profile);
    }
    public static synchronized void start(ModContext mod,LoadedApp app,Object service,String profile) throws Throwable {
        if(instance!=null)return;
        AppProvider next=new AppProvider(mod,app,service,profile);
        try {
        int opened=NativeBridge.open();if(opened!=0)throw new IllegalStateException("native open="+opened);
        Object raw=Points.call(app.classLoader,"iap2.service.on_bind",service,new Intent());
        next.binder=IIap2Service.Stub.asInterface((IBinder)raw);
        Iap2ListenerInfo info=new Iap2ListenerInfo();info.onSetListenerName("CarPlayEV");info.onSetSupportType(0x100);
        instance=next;
        if(!next.binder.Iap2SvcInit(next.listener,info))throw new IllegalStateException("listener refused");
        } catch(Throwable failure){instance=null;next.close();throw failure;}
        mod.log("CarPlayEV semantic listener registered");
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
                        Object lock=Points.get(app.classLoader,"iap2.service.native_lock",null);
                        synchronized(lock){
                            if(!subscription.current(token) || !mod.enabled())return;
                            long handle=((Number)Points.get(app.classLoader,"iap2.service.native_handle",null)).longValue();
                            int rc=NativeBridge.send(handle,snapshot.values,snapshot.charging,snapshot.consumption);
                            mod.log("CarPlayEV native_send="+rc+" soc_e3="+snapshot.values[3]+" range_km="+snapshot.values[4]+" current_wh="+snapshot.values[2]+" min_wh="+snapshot.values[0]+" max_wh="+snapshot.values[1]+" full_range_km="+(snapshot.values[8]==1?String.valueOf(snapshot.values[6]):"omitted")+" auxiliary_w="+snapshot.auxiliaryW+" auxiliary_source="+snapshot.auxiliarySource+" warning="+snapshot.values[5]+" live="+snapshot.values[7]+" charging_model=12582922 consumption_model=12582921");
                        }
                    } catch(Throwable error){mod.error("CarPlayEV send (will retry while subscribed)",error);}
                    Thread.sleep(10000);
                }
            } catch(InterruptedException ended){Thread.currentThread().interrupt();}
        }},"CarPlayEV");worker.setDaemon(true);worker.start();
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
