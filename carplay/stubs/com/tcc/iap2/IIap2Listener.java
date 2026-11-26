package com.tcc.iap2;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IIap2Listener extends IInterface {
    void connected(int component, String serial) throws RemoteException;

    void disconnected(int component) throws RemoteException;

    void notify(int what, int arg1, int arg2, Iap2NotiMsg msg) throws RemoteException;

    abstract class Stub extends android.os.Binder implements IIap2Listener {
        public Stub() {}

        public IBinder asBinder() {
            return this;
        }
    }
}
