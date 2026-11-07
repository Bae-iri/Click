package com.click.service.overlay;
/** Android test bridge to the production navigation planner; no separate search algorithm. */
public final class NativeSettingsSearchPlanner {
    private final AutomaticScrollSearch search=new AutomaticScrollSearch();
    public Boolean next(String scope,String fingerprint){return search.next(scope,fingerprint);}
    public void reachedEnd(boolean forward){search.reachedEnd(forward);}
}
