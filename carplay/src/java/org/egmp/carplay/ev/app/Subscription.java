package org.egmp.carplay.ev.app;
// SPDX-License-Identifier: GPL-3.0-or-later
/** Semantic listener state. No raw iAP2 packet parsing. */
public final class Subscription {
    private int component;
    private boolean active,closed;
    private long epoch;
    public synchronized void connected(int next){
        if(closed)return;
        if(component!=next){active=false;epoch++;component=next;}
    }
    public synchronized long start(){if(closed)return 0;if(!active){active=true;epoch++;}return epoch;}
    public synchronized void stop(){active=false;epoch++;}
    public synchronized boolean disconnected(int which){
        if(component!=0 && which!=component)return false;
        component=0;stop();return true;
    }
    public synchronized void close(){closed=true;stop();}
    public synchronized boolean current(long token){return token!=0 && active && !closed && token==epoch;}
}
