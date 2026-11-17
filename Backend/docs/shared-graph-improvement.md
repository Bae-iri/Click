# Shared task-graph improvement

Agent 1 guides the current task using an approved, version-pinned graph. Agent 2
compares execution evidence across sessions and proposes changes to that shared
graph. It does not classify users or change instructions by comprehension level.

## Removal and rollout

Migration `20261104_16` drops `user_ui_profiles` and `ui_events`, and removes
`personalization` consent records, plus `users.age_group` and
`users.occupation_category`. Account identity and display name, other
consents, agent sessions, runtime events, approved graphs and graph release
history remain. Account code no longer creates or reads a UI profile.

The old profile and UI-event endpoints return 410 to authenticated owners. Old
profile metadata is stripped from cached responses. The new APK removes UI
behavior uploads and offers **안내 경로 개선 참여**, an optional `model_training`
consent for common route improvement. Existing personalization consent is never
converted into this new purpose. Withdrawal or account deletion invalidates
unpublished cross-user analysis, including runs anchored to another user's event.

Deploy the new backend and migration together, and distribute the rebuilt APK.
Take the normal database backup before the migration: deleted profile/event
contents cannot be recovered by downgrade. Downgrade restores empty event tables
and neutral profiles for existing accounts; retired demographic columns are
restored as null, without recovering their old values. Do not downgrade the database while
the newer application is running. No production database was changed by tests.

## Evidence and ranking

The durable `runtime_events` outbox records immutable graph references, hashed
guide/target/observation links, verification outcomes and bounded durations. Target
hashes use the same semantic binding as action verification. Repeated pending
guides retain that binding across fresh observations. Historical events with
incompatible hashes remain incomplete evidence and cannot establish success.

Only events after the latest explicit grant enter analysis. Evidence and derived
receipts expire after at most seven days; a combined receipt expires with its
earliest input. Missing observations, waiting, duplicate actions and uncorrelated
results are not failed attempts. Durations include guide-to-observation time;
they are operational measurements and never estimates of user comprehension.

Device cohorts contain only allowlisted manufacturer + Android major version,
such as `samsung:android-14`. Unknown values stay unknown. Names, demographics,
voice, arbitrary screen labels and user IDs are not proposal inputs.

The first supported proposal type changes priorities of existing outgoing edges:

1. Prefer the higher verified success rate.
2. At the same success rate, prefer lower mean successful duration when each
   route has enough timing samples.
3. Abstain if a device cohort with comparable evidence would regress.

The two-attempt minimum is a development heuristic, not proof of product quality.
New states, shortcuts, selectors, completion predicates and safety policies are
never invented by this pipeline. They need separate graph authoring and device
evidence. If the existing graph has no alternative route, analysis alone cannot
produce a route-priority patch.

## Operator workflow

Run from `Backend` with the intended `DATABASE_URL` in the process environment.
These commands never implicitly create a development database.

```powershell
python scripts/agent2.py process
python scripts/agent2.py audit
python scripts/agent2.py propose-routes --manifest review-manifest.json --cases device-holdout.json
```

`process` stores session analysis in expiring DB receipts. `propose-routes` combines
eligible sessions, using an explicit `ai2-dataset.v1` manifest to separate training,
validation and independently reviewed holdout episodes. Runtime review records
must refer to unexpired, still-consented evidence. Independent curated device-test
holdout cases may be supplied separately. Session and review-group leakage are
rejected. The manifest contract is in `ai_agent.graph_proposal.contracts` and
examples are covered by `test_shared_graph_routes.py` and `test_agent2_pipeline.py`.

The command prints status, `run_id` and candidate IDs; it does not print runtime
contents or export them to files. With missing evaluation evidence it returns
`NEEDS_DATA`. Supplying no cases can generate proposals, but cannot approve them.

For a selected candidate, replay both graph versions on independently reviewed
device holdout, covering the device cohorts represented by the proposal. Cases
can declare `device_cohort` using the same allowlisted format. If a new route would
change a captured action whose result was never observed, collect new device
evidence rather than assume its outcome.

```powershell
python scripts/agent2.py publish --run-id <run-id> --proposal-id <candidate-id> --base base-graph.json --manifest review-manifest.json --cases device-holdout.json --version <new-version> --reviewer <reviewer-id>
```

Publication requires a matching PASS, zero observed holdout violations, an exact
base hash and an explicit reviewer. The DB receipt is checked again at publication
and rejected if expired or invalidated. Existing sessions stay pinned to their
original graph; new sessions use the released version. The existing `rollback`
command restores a reviewed previous version with an audit trail.

Jev receives route priorities after identifying the observed step. When the local
matcher has equally strong evidence for a lower-priority-number route in that
step, the reviewed order is enforced locally. Priority never supplies missing UI
evidence or forces navigation away from an already reached goal page.

The existing route-priority pipeline still runs through explicit operator commands.
Public browsing now has an additional automatic Agent 2 path: after a verified
trace completes, it creates a new `browse-task-graph.v1` draft in the encrypted
`graph_proposals` table. A lifespan worker retries retained work every minute;
`agent2.py learn-browse` also backfills it. Drafts are tied to expiring runtime
receipts and disappear with consent withdrawal, source deletion or expiry.
These are new state/transition graphs, not priority changes to an existing base.
They remain drafts for independent device validation and review, and are never
automatically inserted into `approved_task_graphs`. See
[browsing graph generation](../../docs/coupang-browse.md#ai2-신규-그래프-자동-생성).

Registration no longer advertises, collects or persists age/occupation. Legacy
APKs can still register: the backend discards those two retired request keys
before validating an otherwise normal account request. User responses contain
only ID, optional display name and creation time. The rebuilt app also purges
old local age, job and gender preferences while retaining name/email/login state.
