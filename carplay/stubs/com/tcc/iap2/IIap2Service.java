package com.tcc.iap2;
// Compile-only stock API signatures, verified against the API18 framework smali.
import android.os.*;
public interface IIap2Service extends IInterface {
    boolean Iap2SvcInit(IIap2Listener listener,Iap2ListenerInfo info) throws RemoteException;
    boolean Iap2SvcStopMain(IIap2Listener listener) throws RemoteException;
    abstract class Stub extends Binder implements IIap2Service {
        public static IIap2Service asInterface(IBinder binder){throw new UnsupportedOperationException("compile stub");}
    }
}
