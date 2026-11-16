package com.click.service.overlay;

/** Data-only reconnect attempts are bounded; repeated capability events are not new evidence. */
final class ReceiptReconciliationGate {
    private String receipt,network;
    private int attempts;
    private long lastAt;
    boolean allow(String receipt,String network,long now) {
        if(receipt==null || network==null)return false;
        if(!receipt.equals(this.receipt)){this.receipt=receipt;this.network=null;attempts=0;lastAt=0;}
        if(network.equals(this.network) || attempts>=3 || attempts>0 && now-lastAt<5000)return false;
        this.network=network;lastAt=now;attempts++;return true;
    }
}
