"""Observe foreground transitions during the unmodified Clock recovery study on a USB phone."""
import argparse
import json
import subprocess
import time


def foreground(adb):
    result = subprocess.run([adb, "-d", "shell", "dumpsys activity activities | grep topResumedActivity"],
                            capture_output=True, text=True, timeout=4)
    if not result.stdout.strip():
        return "no_resumed_activity"
    for package, name in [("com.sec.android.app.clockpackage/", "clock"),
                          ("com.android.settings/", "settings"),
                          ("com.sec.android.app.launcher/", "launcher"),
                          ("com.clueclick.app/", "click")]:
        if package in result.stdout:
            return name
    return "other"


def run(adb):
    start = time.monotonic()
    trace = []
    args = [adb, "-d", "shell", "am", "instrument", "-w", "-r", "-e", "class",
            "com.click.service.accessibility.GoalOutcomeDeviceTest#confirmsClockPageAndRecoversAcrossARealForegroundSwitch",
            "-e", "pageRecovery", "true", "com.click.qualityprobe.outcomeaudit.test/androidx.test.runner.AndroidJUnitRunner"]
    with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as process:
        while process.poll() is None:
            state = foreground(adb)
            if not trace or trace[-1]["foreground"] != state:
                trace.append(dict(ms=round((time.monotonic()-start)*1000), foreground=state))
            time.sleep(0.15)
        output = process.communicate()[0]
    verdict = "unclassified_failure"
    if "OK (1 test)" in output:
        verdict = "both_completed"
    elif "Public Clock tab not ready: 스톱워치" in output:
        verdict = "stopwatch_not_observed"
    elif "Missing native page proof" in output:
        verdict = "native_proof_missing"
    return dict(instrumentation_result=verdict, transitions=trace)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    print(json.dumps(run(args.adb), indent=2))
