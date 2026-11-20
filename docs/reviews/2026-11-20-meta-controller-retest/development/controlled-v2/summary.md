Evidence: controlled_full_controller_conformance (synthetic UI)
Episodes: 72; matched pairs: 36

| Assigned condition | Arm | Goal | Verified | Calls | Planner requests | Injected faults | UI median ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| accepted_no_effect | A | 0/12 | 0 | 0 | 12 | 12 | None |
| accepted_no_effect | B | 12/12 | 12 | 0 | 24 | 12 | 5700.0 |
| delayed_transition | A | 10/12 | 10 | 0 | 14 | 12 | 5750.0 |
| delayed_transition | B | 12/12 | 12 | 0 | 12 | 12 | 6150.0 |
| normal | A | 12/12 | 12 | 0 | 12 | 0 | 650.0 |
| normal | B | 12/12 | 12 | 0 | 12 | 0 | 650.0 |

Default enablement: NO_GO_FOR_DEFAULT_ENABLEMENT
Next validation stage: GO_TO_BROADER_REAL_APP_VALIDATION

- Synthetic fixtures do not establish generality, real-app success, user benefit or wall-clock speed.
- Controlled planner knows fixture topology; its recovery benefit is constructed mechanism conformance.
- Assigned fault conditions and actual first-primary fault exposure are reported separately.
- Modeled UI waits and actual cloud call latency use separate clocks and must not be described as observed real-app latency.
- All assigned matched episodes are included; model/provider/gateway failures are reported, not silently removed.
- Two repetitions of six constructed workflows do not support population-level significance claims.
