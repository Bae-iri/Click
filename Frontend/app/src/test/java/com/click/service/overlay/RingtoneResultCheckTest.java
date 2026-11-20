package com.click.service.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class RingtoneResultCheckTest {
    @Test public void onlyAStablePersistedChangeCanAskForAResult(){
        var check=new RingtoneResultCheck();check.bind("original");
        assertFalse(check.changed("original",100));assertFalse(check.changed("selected",200));
        assertFalse(check.changed("selected",400));assertTrue(check.changed("selected",600));
        check.consume();assertFalse(check.active());assertFalse(check.changed("selected",1000));
    }
    @Test public void readingFailureAndTemporarySelectionDoNotCountAsCompletion(){
        var check=new RingtoneResultCheck();check.bind("original");assertFalse(check.changed(null,0));
        assertFalse(check.changed("preview",100));assertFalse(check.changed("original",500));
        assertFalse(check.changed("preview",600));assertFalse(check.changed("preview",700));
        check.bind(null);assertFalse(check.active());assertFalse(check.changed("other",2000));
    }
}
