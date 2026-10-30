package com.click.service.accessibility;
import org.junit.Test;
import static org.junit.Assert.*;
public class SliderAdjustmentTest {
 @Test public void allowsOneDirectionWithinRange() {
  assertTrue(SliderAdjustment.canMove(2,0,7,1));
  assertTrue(SliderAdjustment.canMove(2,0,7,-1));
  assertFalse(SliderAdjustment.canMove(7,0,7,1));
  assertFalse(SliderAdjustment.canMove(0,0,7,-1));
 }
 @Test public void rejectsInvalidAndUnspecifiedRanges() {
  assertFalse(SliderAdjustment.canMove(Float.NaN,0,7,1));
  assertFalse(SliderAdjustment.canMove(2,0,Float.POSITIVE_INFINITY,1));
  assertFalse(SliderAdjustment.canMove(8,0,7,1));
  assertFalse(SliderAdjustment.canMove(2,7,0,1));
  assertFalse(SliderAdjustment.canMove(2,0,7,0));
 }
 @Test public void onlyConfirmsObservedMovementInRequestedDirection() {
  assertTrue(SliderAdjustment.moved(2,3,1));
  assertTrue(SliderAdjustment.moved(2,1,-1));
  assertFalse(SliderAdjustment.moved(2,2,1));
  assertFalse(SliderAdjustment.moved(2,1,1));
  assertFalse(SliderAdjustment.moved(2,Float.NaN,1));
 }
}
