package com.tcc.iap2;

public class Iap2ListenerInfo {
    int type;
    String name;

    public void onSetSupportType(int type) {
        this.type = type;
    }

    public void onSetListenerName(String name) {
        this.name = name;
    }
}
