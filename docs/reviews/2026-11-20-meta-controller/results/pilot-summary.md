# Complete MetaController native navigation pilot

Run: `native-v4-20261120`; instrumentation `native-navigation.v4`; **216/216** scheduled episodes, 12 tasks, four apps, A/B/C, two repetitions.
Budget: 15 seconds per episode. Scope: scripted approvals on one phone.

Deployment gate: **NO_GO**. Experiment continuation gate: **NO_GO_FIX_COMPLETION_GROUNDING**.

## All assigned episodes

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 7/72 | 14.43 | 22 | 21/22 | 59 (72/72) |
| B | 7/72 | 14.39 | 23 | 22/23 | 66 (72/72) |
| C | 7/72 | 14.40 | 22 | 21/22 | 64 (72/72) |

Actual goal observation, runtime verification offers and first action proposals are distinct:

| Arm | Goal-observed p50 s (measured/successes) | Verified-offer p50 s (measured/offers) | First-proposal p50 s (measured/N) | Runtime DONE count |
|---|---:|---:|---:|---:|
| A | 9.30 (7/7) | 4.50 (22/22) | 3.75 (33/72) | 0 |
| B | 9.66 (7/7) | 4.44 (23/23) | 3.66 (34/72) | 0 |
| C | 9.30 (7/7) | 4.24 (22/22) | 3.74 (31/72) | 0 |

Final-read availability is separate from earlier stable positive goal evidence:

| Arm | Final oracle observable | Final oracle unavailable | Coverage unreported | Positive goal evidence despite unavailable final read |
|---|---:|---:|---:|---:|
| A | 52 | 0 | 20 | 0 |
| B | 52 | 0 | 20 | 0 |
| C | 51 | 0 | 21 | 0 |

## Assigned condition: normal

All assigned episodes are included regardless of whether fault injection was reached.

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 3/24 | 13.96 | 8 | 7/8 | 19 (24/24) |
| B | 3/24 | 13.82 | 9 | 8/9 | 19 (24/24) |
| C | 3/24 | 13.84 | 8 | 7/8 | 19 (24/24) |

## Assigned condition: no_effect

All assigned episodes are included regardless of whether fault injection was reached.

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 0/24 | 15.00 | 7 | 7/7 | 19 (24/24) |
| B | 0/24 | 15.00 | 7 | 7/7 | 23 (24/24) |
| C | 0/24 | 15.00 | 7 | 7/7 | 22 (24/24) |

## Assigned condition: dispatch_delay

All assigned episodes are included regardless of whether fault injection was reached.

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 4/24 | 14.33 | 7 | 7/7 | 21 (24/24) |
| B | 4/24 | 14.34 | 7 | 7/7 | 24 (24/24) |
| C | 4/24 | 14.35 | 7 | 7/7 | 23 (24/24) |

## App: com.android.chrome

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 7/18 | 12.73 | 4 | 3/4 | 25 (18/18) |
| B | 7/18 | 12.55 | 5 | 4/5 | 32 (18/18) |
| C | 7/18 | 12.59 | 4 | 3/4 | 31 (18/18) |

## App: com.android.settings

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 0/18 | 15.00 | 18 | 18/18 | 18 (18/18) |
| B | 0/18 | 15.00 | 18 | 18/18 | 18 (18/18) |
| C | 0/18 | 15.00 | 18 | 18/18 | 18 (18/18) |

## App: com.google.android.youtube

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 0/18 | 15.00 | 0 | 0/0 | 0 (18/18) |
| B | 0/18 | 15.00 | 0 | 0/0 | 0 (18/18) |
| C | 0/18 | 15.00 | 0 | 0/0 | 0 (18/18) |

## App: com.sec.android.app.clockpackage

| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |
|---|---:|---:|---:|---:|---:|
| A | 0/18 | 15.00 | 0 | 0/0 | 16 (18/18) |
| B | 0/18 | 15.00 | 0 | 0/0 | 16 (18/18) |
| C | 0/18 | 15.00 | 0 | 0/0 | 15 (18/18) |

## Mechanism opportunities

| Arm | Dispatch-exposed episodes | Fault injected/assigned | False offers before any dispatch | Recovery count (measured/N) | Goal after recovery |
|---|---:|---:|---:|---:|---:|
| A | 33 | 8/48 | 18 | 0 (52/72) | 0 |
| B | 34 | 8/48 | 18 | 8 (52/72) | 4 |
| C | 31 | 7/48 | 19 | 7 (51/72) | 4 |

Pre-dispatch false offers are upstream failures without execution/recovery exposure. Goal after recovery is descriptive and cannot establish causal recovery effectiveness.

## Strict matched comparisons

**B − A**: 52 matched episode pairs, 9 task clusters, 20 rejected/incomplete pairs.
```json
{
  "difference": {
    "success_rate_pp": 0,
    "mean_penalized_time_ms": -60.44230769230769
  },
  "cluster_bootstrap_95ci": {
    "success_rate_pp": [
      0,
      0
    ],
    "mean_penalized_time_ms": [
      -179.01923076923077,
      0.0
    ]
  }
}
```

Rejected-pair reasons: `{"INCOMPLETE_OR_DIFFERENT_CONTEXT": 20}`.

**C − B**: 51 matched episode pairs, 9 task clusters, 21 rejected/incomplete pairs.
```json
{
  "difference": {
    "success_rate_pp": 0,
    "mean_penalized_time_ms": 11.27450980392157
  },
  "cluster_bootstrap_95ci": {
    "success_rate_pp": [
      0,
      0
    ],
    "mean_penalized_time_ms": [
      -60.333333333333336,
      95.82692307692308
    ]
  }
}
```

Rejected-pair reasons: `{"INCOMPLETE_OR_DIFFERENT_CONTEXT": 21}`.

Full rejected pair IDs, missing fields and context mismatches are retained in `pilot-summary.json` and `comparison.json`.

## Limits and unmeasured scope

- This is 216 scripted navigation episodes (144 A/B plus 72 C), not the originally proposed 252-trial product experiment.
- All assigned trials, including setup, transport and provider failures, remain in primary denominators.
- Assigned-condition tables use intention to treat; realized injection is an opportunity diagnostic, not an exclusion criterion.
- The dispatch-delay condition delays native execution by 5.5 seconds; it does not simulate UI rendering delay.
- Goal observation, runtime verified offer, first proposal and user-confirmed DONE are different events and clocks.
- Unavailable final oracle reads are unknown, not observed absence. Earlier stable positive goal evidence remains distinct from final-read availability.
- No user-result confirmation is simulated; zero false DONE alone is not evidence of correct completion grounding.
- False verified offers before any native dispatch do not test MC execution/recovery mechanisms.
- Recovery/dispatch counts expose whether the mechanism was exercised; subsequent success is not a causal recovery rate.
- Strict matching may exclude changing start-state hashes; excluded episodes remain in all-trial summaries.
- The 15-second budget, single phone, deterministic order and shallow public-navigation goals limit generalization.
- App names appearing in this pilot do not establish formal held-out generalization.
- This is a nonroot MC-v0 adaptation, not UIAnchor LSPosed, model training or its complete reflector architecture.
- Scripted approvals do not measure middle/older-adult performance, understanding, satisfaction or trust.
- Server provider work may extend beyond an episode; null latency/safety measurements are not zeros.
- Episode timing starts after scripted setup; setup/navigation preparation is not end-to-end user waiting time.
- Go/no-go flags are scoped data gates; research-question interpretations require reviewing exposure, uncertainty and limitations.
