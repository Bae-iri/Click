# AI1 recovery replay

`navigation.json` is a synthetic regression fixture, not collected device data or independently reviewed product gold. It covers launcher → settings with nested titles/descriptions, off-screen targets, duplicate targets, popups, stale observations, missing action events and verified completion.

Run from `AI-Agent/`:

```sh
PYTHONPATH=src python3 -m ai_agent.runtime.replay data/evaluation/runtime_recovery/navigation.json
```

The runner uses the supplied graph and a deterministic keyword scorer, never a remote model. It checks the graph hash and reports wrong-target, false-termination and false-completion counts with their denominators, plus recovery/resumption counts. Every explicit expectation mismatch fails the run, including missing expected actions. These counts describe these fixtures only; they are not device success rates.

For an opt-in diagnostic capture:

```python
from ai_agent.runtime.replay import ReplayRecorder

recorder = ReplayRecorder(selected_graph, max_turns=100)
engine = ClickAgentEngine(task_graphs={selected_graph.definition.task_id: selected_graph},
                          replay_recorder=recorder)
# Call engine.handle_turn as usual, then explicitly export:
record = recorder.export()
```

The recorder keeps one episode in memory, bounds its size, pseudonymizes IDs, removes free text and sensitive inputs, and preserves only graph-owned/approved UI vocabulary, structural fields, geometry and numeric control values. Raw goals, screenshots and Vision payloads are not exported. Vocabulary redaction is lossy; custom class/resource names and omitted Vision may change reproduction. Exported `observed_result` is a diagnostic, **not** the expected answer. Inspect redaction and independently annotate `expected` before using a capture as an evaluation fixture. No persistence/upload or production recording is enabled by default.

Recovery messages use `render_policy.recovery` (`ai1-recovery.v1`): `decision`, `reason_code`, `next_action`, `waiting_for`, bounded observation attempts and user choices. Backend preserves this object through privacy filtering, caching and checkpoint restore. Navigation recovery remains `IN_PROGRESS`; safety blocks remain `BLOCKED`; verified completion and control limits may enter result confirmation.
