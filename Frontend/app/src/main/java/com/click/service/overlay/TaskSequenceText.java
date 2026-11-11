package com.click.service.overlay;

import com.click.domain.goal.GoalCatalogClassifier;
import java.util.ArrayList;
import java.util.List;

/** Spoken task overview and one-task review copy; never describes queue bookkeeping. */
final class TaskSequenceText {
 private static String stem(String type){
  String target=type.startsWith("settings.font_size.")?"글자 크기":type.startsWith("settings.brightness.")?"디스플레이 밝기":type.startsWith("settings.volume.")?"소리":null;
  if(target==null)return null;
  if(type.endsWith(".increase"))return target+"를 높이";
  if(type.endsWith(".decrease"))return target+"를 낮추";
  return null;
 }
 static String introduction(String current,List<String> following,GoalCatalogClassifier catalog){
  if(following==null || following.isEmpty())return null;
  List<String> requests=new ArrayList<>();requests.add(current);requests.addAll(following);
  if(requests.size()>3)return "요청하신 순서대로 하나씩 도와드리겠습니다.";
  List<String> actions=new ArrayList<>();
  for(String request:requests){
   GoalCatalogClassifier.Classification match=catalog==null?null:catalog.classify(request,null);
   String action=match==null || !match.matched()?null:stem(match.match.goalType);
   if(action==null)return "요청하신 순서대로 하나씩 도와드리겠습니다.";
   actions.add(action);
  }
  StringBuilder result=new StringBuilder();
  for(int i=0;i<actions.size();i++)result.append(actions.get(i)).append(i==actions.size()-1?"겠습니다.":"고 ");
  return result.toString();
 }
 static String question(String goalType){
  String action=goalType==null?null:stem(goalType);
  if(action==null)return "다음 작업을 시작할까요?";
  return action.substring(0,action.length()-1)+(action.endsWith("이")?"일까요?":"출까요?");
 }
}
