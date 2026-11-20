Evidence: live_model_synthetic_closed_loop (synthetic UI)
Episodes: 72; matched pairs: 36

| Assigned condition | Arm | Goal | Verified | Calls | Planner requests | Injected faults | UI median ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| accepted_no_effect | A | 0/12 | 0 | 12 | 12 | 12 | None |
| accepted_no_effect | B | 9/12 | 2 | 30 | 31 | 12 | 5700.0 |
| delayed_transition | A | 9/12 | 2 | 25 | 25 | 11 | 5750.0 |
| delayed_transition | B | 12/12 | 2 | 29 | 30 | 12 | 6150.0 |
| normal | A | 11/12 | 4 | 21 | 22 | 0 | 650.0 |
| normal | B | 12/12 | 3 | 20 | 21 | 0 | 650 |

Default enablement: NO_GO_FOR_DEFAULT_ENABLEMENT
Next validation stage: NO_GO_FIX_OR_CHARACTERIZE_FAILURES
Complete frozen matrix: True; healthy data: False
Efficiency comparison: NOT_INTERPRETABLE_INCOMPLETE_OR_UNHEALTHY_DATA

| Assigned fault | Both exposed | A only | B only | Neither | Interpretation |
|---|---:|---:|---:|---:|---|
| accepted_no_effect | 12 | 0 | 0 | 0 | DESCRIPTIVE_SYNTHETIC_ONLY |
| delayed_transition | 11 | 0 | 1 | 0 | DESCRIPTIVE_SYNTHETIC_ONLY |

- Synthetic fixtures do not establish generality, real-app success, user benefit or wall-clock speed.
- Gemini decisions use evolving synthetic observations; findings remain synthetic and model variability is retained.
- Assigned fault conditions and actual first-primary fault exposure are reported separately.
- A next-stage GO requires the complete 72/36 matrix, healthy data in every condition, successful normal controls and matched fault exposure in both injected conditions.
- Modeled UI waits and actual cloud call latency use separate clocks and must not be described as observed real-app latency.
- All assigned matched episodes are included; model/provider/gateway failures are reported, not silently removed.
- Two repetitions of six constructed workflows do not support population-level significance claims.
