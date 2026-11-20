Actual Android Settings — descriptive A/B cohort
Observed/scheduled episodes: 12/36; valid pairs: 6
Normal gate: PROCEED_TO_FROZEN_FAULT_STRATA
Measurement valid: True; issues: none

| Arm | Observed/planned | Joint success/evaluable | Unknown | Reached | Verified | Penalized mean ms | Known calls | Audited unknown calls | Audited wait ms | Server nav blocks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A | 6/18 | 4/6 | 0 | 4 | 4 | 27026.056 | 20 | 0 | 79146 | 0 |
| B | 6/18 | 2/6 | 0 | 3 | 2 | 27839.278 | 14 | 0 | 71634 | 0 |

| Condition | Arm | Joint/evaluable | Unknown | Client provider/plan errors | Server provider/plan errors | Server nav blocks | Audited calls/unknown | Audited wait ms | Realized faults |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| accepted_no_effect | A | 0/0 | 0 | 0/0 | 0/0 | 0 | 0/0 | 0 | 0 |
| accepted_no_effect | B | 0/0 | 0 | 0/0 | 0/0 | 0 | 0/0 | 0 | 0 |
| delayed_transition | A | 0/0 | 0 | 0/0 | 0/0 | 0 | 0/0 | 0 | 0 |
| delayed_transition | B | 0/0 | 0 | 0/0 | 0/0 | 0 | 0/0 | 0 | 0 |
| normal | A | 4/6 | 0 | 1/0 | 1/0 | 0 | 20/0 | 79146 | 0 |
| normal | B | 2/6 | 0 | 4/0 | 4/0 | 0 | 14/0 | 71634 | 0 |

Goal/time interpretation: INDETERMINATE_OR_INCOMPLETE
Model cost: RECORDED_COUNTS_COMPLETE
Next validation: NO_GO_OR_CHARACTERIZE_LIMITATIONS
Default B: NO_GO_ONE_APP_SMALL_SAMPLE

| Arm | First stable-goal median ms | First verified median ms | Oracle unknown/total polls | Cleanup goal present |
|---|---:|---:|---:|---:|
| A | 12176.5 | 17787.0 | 2/1019 | 4 |
| B | 6864 | 10525.0 | 2/1158 | 3 |

Descriptive only: shared completion/context/capability changes and observation-window changes are bundled; v1/v2 differences do not isolate a causal change.

- Primary success requires stable independent goal, runtime verified offer, settled native actions and joint time within budget.
- Classified provider/model/blocked-proposal failures remain product outcomes in assigned denominators.
- Unknown setup/oracle values remain unknown; rate bounds expose rather than erase them.
- Failure-inclusive time uses the common budget; early failure is never a speed gain.
- All assigned denominators remain thirty-six; unexecuted assignments remain unknown and receive the budget in conservative assigned-time summaries.
- Delayed fault exposure requires an accepted delayed native dispatch; scheduling alone is not realized exposure.
- Normal twelve are the first stratum of the frozen thirty-six, not a separately selected pilot.
- One Settings app and one repetition do not establish generality, significance or middle-aged user benefit.
- Nonjoint v2 episodes observe the independent oracle through the common budget despite terminal product status.
- First stable-goal, first runtime-verified, joint-success, and cleanup goal presence are distinct; cleanup presence is not first arrival.
- Transient unknown oracle polls reset stability and are disclosed; they are not known negatives.
- Optional context metadata proves technical forwarding only with an exactly joined observed model attempt.
