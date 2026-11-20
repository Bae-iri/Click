Evidence: live_model_synthetic_closed_loop (synthetic UI)
Episodes: 12; matched pairs: 6

| Assigned condition | Arm | Goal | Verified | Calls | Planner requests | Injected faults | UI median ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| normal | A | 4/6 | 1 | 9 | 9 | 0 | 650 |
| normal | B | 5/6 | 1 | 11 | 11 | 0 | 650 |

Default enablement: NO_GO_FOR_DEFAULT_ENABLEMENT
Next validation stage: NO_GO_FIX_OR_CHARACTERIZE_FAILURES
Complete frozen matrix: False; healthy data: False
Efficiency comparison: NOT_INTERPRETABLE_INCOMPLETE_OR_UNHEALTHY_DATA

| Assigned fault | Both exposed | A only | B only | Neither | Interpretation |
|---|---:|---:|---:|---:|---|
| accepted_no_effect | 0 | 0 | 0 | 0 | NOT_TESTED |
| delayed_transition | 0 | 0 | 0 | 0 | NOT_TESTED |

- Synthetic fixtures do not establish generality, real-app success, user benefit or wall-clock speed.
- Gemini decisions use evolving synthetic observations; findings remain synthetic and model variability is retained.
- Assigned fault conditions and actual first-primary fault exposure are reported separately.
- A next-stage GO requires the complete 72/36 matrix, healthy data in every condition, successful normal controls and matched fault exposure in both injected conditions.
- Modeled UI waits and actual cloud call latency use separate clocks and must not be described as observed real-app latency.
- All assigned matched episodes are included; model/provider/gateway failures are reported, not silently removed.
- Two repetitions of six constructed workflows do not support population-level significance claims.
