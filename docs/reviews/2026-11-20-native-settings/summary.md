Actual Android Settings — descriptive A/B cohort
Observed/scheduled episodes: 36/36; valid pairs: 18
Normal gate: PROCEED_TO_FROZEN_FAULT_STRATA
Measurement valid: True; issues: none

| Arm | Observed/planned | Joint success/evaluable | Unknown | Reached | Verified | Penalized mean ms | Known calls | Audited unknown calls | Audited wait ms | Server nav blocks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A | 18/18 | 0/18 | 0 | 6 | 0 | 30000 | 37 | 0 | 204562 | 1 |
| B | 18/18 | 0/18 | 0 | 6 | 0 | 30000 | 44 | 0 | 253023 | 5 |

| Condition | Arm | Joint/evaluable | Unknown | Client provider/plan errors | Server provider/plan errors | Server nav blocks | Audited calls/unknown | Audited wait ms | Realized faults |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| accepted_no_effect | A | 0/6 | 0 | 0/0 | 0/0 | 0 | 8/0 | 44142 | 6 |
| accepted_no_effect | B | 0/6 | 0 | 4/0 | 4/2 | 2 | 14/0 | 77868 | 5 |
| delayed_transition | A | 0/6 | 0 | 5/0 | 5/1 | 1 | 12/0 | 82805 | 4 |
| delayed_transition | B | 0/6 | 0 | 5/0 | 5/3 | 3 | 16/0 | 100701 | 5 |
| normal | A | 0/6 | 0 | 1/0 | 1/0 | 0 | 17/0 | 77615 | 0 |
| normal | B | 0/6 | 0 | 3/0 | 3/0 | 0 | 14/0 | 74454 | 0 |

Goal/time interpretation: DESCRIPTIVE_NATIVE_WALL_TIME_ONLY
Model cost: RECORDED_COUNTS_COMPLETE
Next validation: NO_GO_OR_CHARACTERIZE_LIMITATIONS
Default B: NO_GO_ONE_APP_SMALL_SAMPLE

- Primary success requires stable independent goal, runtime verified offer, settled native actions and joint time within budget.
- Classified provider/model/blocked-proposal failures remain product outcomes in assigned denominators.
- Unknown setup/oracle values remain unknown; rate bounds expose rather than erase them.
- Failure-inclusive time uses the common budget; early failure is never a speed gain.
- All assigned denominators remain thirty-six; unexecuted assignments remain unknown and receive the budget in conservative assigned-time summaries.
- Delayed fault exposure requires an accepted delayed native dispatch; scheduling alone is not realized exposure.
- Normal twelve are the first stratum of the frozen thirty-six, not a separately selected pilot.
- One Settings app and one repetition do not establish generality, significance or middle-aged user benefit.
