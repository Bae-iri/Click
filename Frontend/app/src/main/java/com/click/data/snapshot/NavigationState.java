package com.click.data.snapshot;

/** Local native collection evidence. Never serialized into a screen/provider payload. */
public final class NavigationState {
    public enum Axis { HORIZONTAL, VERTICAL, UNKNOWN }
    public final String containerId, fingerprint;
    public final Axis axis;
    public final Boolean backwardAvailable, forwardAvailable;
    public final int firstIndex, lastIndex, itemCount;
    public NavigationState(String containerId, Axis axis, Boolean backward, Boolean forward,
                           int first, int last, int count, String fingerprint) {
        this.containerId=containerId;this.axis=axis;backwardAvailable=backward;forwardAvailable=forward;
        firstIndex=first;lastIndex=last;itemCount=count;this.fingerprint=fingerprint;
    }
    public boolean atStart() { return firstIndex>=0 ? firstIndex==0 : Boolean.FALSE.equals(backwardAvailable); }
    public boolean atEnd() { return lastIndex>=0 && itemCount>0 ? lastIndex>=itemCount-1 : Boolean.FALSE.equals(forwardAvailable); }
}
