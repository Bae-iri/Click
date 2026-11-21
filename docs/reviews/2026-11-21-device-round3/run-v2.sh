#!/bin/bash
set -u
D=docs/reviews/2026-11-21-device-round3; out=$D/V2-diagnostic; S=R3CN8157M2Z; P=com.click.metamemoryexperiment.uitestdriver; PY=../Click/Backend/.venv/bin/python
$PY Backend/scripts/serve_memory_experiment.py --allow-live --arm-config $out/arm-config.json --env-file ../Click/Backend/.env --catalogue $D/catalogue.json --log-dir $out/facade-logs > $out/server.log 2>&1 & srv=$!
for i in $(seq 1 50); do curl -s -m 1 http://127.0.0.1:8766/health | grep -q ready && break; perl -e 'select(undef,undef,undef,.2)'; done
adb -s $S reverse tcp:8766 tcp:8766 >/dev/null
adb -s $S shell am instrument -w -r -e class com.click.service.accessibility.MemoryDeviceExperimentTest#runMemoryExperiment -e memoryExperiment true -e configuredExperiment true -e metaArm CURRENT -e arms A -e armSpeed A:PRODUCTION -e armLearning A:false -e schedule r1 -e tasks settings_sound,settings_font,settings_app_notifications -e budgetMs 30000 -e verifyWaitMs 1500 -e runId round3-v2 $P.test/androidx.test.runner.AndroidJUnitRunner > $out/instrumentation.txt 2>&1
curl -s -m 30 http://127.0.0.1:8766/health >/dev/null
adb -s $S exec-out run-as $P cat files/memory-reuse/episodes.jsonl > $out/episodes.jsonl
adb -s $S exec-out run-as $P cat files/memory-reuse/manifest.json > $out/manifest.json
kill $srv; wait $srv 2>/dev/null; adb -s $S reverse --remove tcp:8766
echo "episodes=$(wc -l < $out/episodes.jsonl) $(grep -o 'OK (1 test)' $out/instrumentation.txt) a11y=$(adb -s $S shell settings get secure enabled_accessibility_services)"
