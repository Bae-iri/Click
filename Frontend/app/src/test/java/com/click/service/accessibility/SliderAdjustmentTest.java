package com.click.service.accessibility;
import org.junit.Test;
import static org.junit.Assert.*;
public class SliderAdjustmentTest {
 @Test public void largeSamsungBrightnessRangeUsesASmallEffectiveStep() {
  float before=58720256f,max=67108864f;
  float after=SliderAdjustment.nextValue(before,0,max,1,true);
  assertTrue(after-before>=max*.009f);
  assertTrue(after-before<=max*.011f);
  assertEquals(max,SliderAdjustment.nextValue(Math.nextDown(max),0,max,1,true),0f);
 }
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
 @Test public void calculatesBoundedStepAndHorizontalGesture() {
  assertEquals(3f,SliderAdjustment.nextValue(2,0,7,1,true),0f);
  assertEquals(1f,SliderAdjustment.nextValue(2,0,7,-1,true),0f);
  float[] points=SliderAdjustment.dragPoints(new int[]{0,100,500,200},2,3,0,7);
  assertNotNull(points);assertTrue(points[2]>points[0]);
  assertNull(SliderAdjustment.dragPoints(new int[]{0,0,50,500},2,3,0,7));
 }
 @Test public void samsungMediaTicksMapToOneActualVolumeLevelInBothDirections() {
  assertEquals(700f,SliderAdjustment.nextMediaValue(600,0,1500,1,6,0,15),0f);
  assertEquals(500f,SliderAdjustment.nextMediaValue(600,0,1500,-1,6,0,15),0f);
  assertEquals(5f,SliderAdjustment.nextMediaValue(6,0,15,-1,6,0,15),0f);
  assertEquals(500f,SliderAdjustment.nextMediaValue(585,0,1500,-1,6,0,15),0f);
 }
 @Test public void mediaMappingRejectsWrongStreamRangeOrLimits() {
  assertTrue(Float.isNaN(SliderAdjustment.nextMediaValue(300,0,1500,-1,6,0,15)));
  assertTrue(Float.isNaN(SliderAdjustment.nextMediaValue(0,0,1500,-1,0,0,15)));
  assertTrue(Float.isNaN(SliderAdjustment.nextMediaValue(1500,0,1500,1,15,0,15)));
  assertTrue(Float.isNaN(SliderAdjustment.nextMediaValue(600,0,1500,1,6,0,0)));
 }
}
