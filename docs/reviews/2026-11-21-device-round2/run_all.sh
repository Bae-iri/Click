#!/bin/bash
# E1 -> E3 -> E2, one fresh facade each. Run from repo root.
set -u
D=docs/reviews/2026-11-21-device-round2; S=R3CN8157M2Z; PY=../Click/Backend/.venv/bin/python
P=com.click.metamemoryexperiment.uitestdriver
run(){ name=$1 arms=$2 speed=$3 learning=$4 schedule=$5 tasks=$6 budget=$7
  out=$D/$name; [ -s $out/episodes.jsonl ] && { echo "skip $name (measured)"; return; }
  $PY Backend/scripts/serve_memory_experiment.py --allow-live --arm-config $out/arm-config.json --env-file ../Click/Backend/.env \
     --catalogue $D/catalogue.json --log-dir $out/facade-logs > $out/server.log 2>&1 & srv=$!
  for i in $(seq 1 50); do curl -s -m 1 http://127.0.0.1:8766/health | grep -q ready && break; perl -e 'select(undef,undef,undef,.2)'; done
  adb -s $S reverse tcp:8766 tcp:8766 >/dev/null
  echo "start $name $(date +%H:%M:%S)"
  adb -s $S shell am instrument -w -r -e class com.click.service.accessibility.MemoryDeviceExperimentTest#runMemoryExperiment \
     -e memoryExperiment true -e configuredExperiment true -e metaArm CURRENT -e arms $arms -e armSpeed $speed -e armLearning $learning \
     -e schedule $schedule -e tasks $tasks -e budgetMs $budget -e verifyWaitMs 1500 -e runId round2-$name \
     $P.test/androidx.test.runner.AndroidJUnitRunner > $out/instrumentation.txt 2>&1
  curl -s -m 30 http://127.0.0.1:8766/health >/dev/null
  adb -s $S exec-out run-as $P cat files/memory-reuse/episodes.jsonl > $out/episodes.jsonl
  adb -s $S exec-out run-as $P cat files/memory-reuse/manifest.json > $out/manifest.json
  kill $srv; wait $srv 2>/dev/null
  echo "done $name $(date +%H:%M:%S) episodes=$(wc -l < $out/episodes.jsonl) $(grep -o 'OK (1 test)' $out/instrumentation.txt)"
}
run E1-provider A,B A:PRODUCTION,B:PRODUCTION A:false,B:false r1,r2,r3 settings_display,settings_sound,settings_notifications 25000
run E3-retry A,B A:PRODUCTION_NO_RETRY,B:PRODUCTION A:false,B:false r1,r2,r3 settings_display,settings_notifications 25000
run E2-memory B,C B:PRODUCTION,C:ALL B:true,C:true learn1,learn2,learn3,eval1,eval2,eval3 settings_font,settings_app_notifications 30000
adb -s $S reverse --remove tcp:8766; adb -s $S shell settings get secure enabled_accessibility_services
