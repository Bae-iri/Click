# Samsung mode choices and narration diagnostics — 2026-11-09

The reported dark-mode flow reached Display and could not select its next control. A previously recorded, filtered native screen from this Samsung phone contains separate Light/Dark cards: clickable `item1`/`item2` containers with `checkable=false`. The old approved graph only targeted a checked native switch. This is a concrete control-contract gap. The exact latest user incident was not captured: the connected phone was locked during the first follow-up observation.

The dark-mode graphs now have a separate, confirmed mode-choice transition. It requires the independent Display heading, both exact native card labels and approved resource IDs, unique non-overlapping choices in one Settings window, and native evidence. It keeps the existing switch route for devices that expose a real switch. The two graph releases advance to 1.1.0 at priority 110, above the existing 1.0.0 priority 100, so older active releases do not make new selection ambiguous and existing pinned sessions are not rewritten. Execution, graph-proposal validation and rollout audit use one supported-predicate contract. The first full backend run exposed nine audit failures from the previously separate capability lists; these were corrected before release.

Completion requires a correlated tap on the originally selected card, fresh post-action evidence, and an independently observed `UiModeManager.getNightMode()` value changing from explicit Light (1) to Dark (2), or back. `selected`, OCR, a missing system value, automatic/custom scheduling, unrelated targets and stale events cannot establish success. Only dark-mode observations export the finite mode value and fixed source label. Existing OpenAPI capability negotiation strips the fields for an older server.

A new backend deployment and a new task episode are required to execute the new graph. The Android build also needs the new system-mode collector. A cached older server contract is refreshed by restarting Click after deployment.

The TTS follow-up measured media volume 7/15, and a real Korean utterance completed. Earlier live guide logs contain `muted pending`; these do not prove that the user's current report is caused by current volume. The change adds text-free, deduplicated reasons for voice-reply gating, input focus, automatic-progress suppression, missing narrator, foreground ownership and duplicate sentences. It does not change device volume or claim that every missing task narration has been reproduced.

Validation and physical limits are recorded in the accompanying evidence JSON. The fixture preserves only reviewed static labels and native card ancestry from the earlier filtered capture; its system value in HTTP tests is controlled, not a recovered device measurement.


The approved mode-card route is a guided, confirmed native action; this change does not add the cards to the generic automatic-navigation tap allowlist. Physical automatic mode-card execution is not claimed. The matching user tap and system-value change still have to be observed for CHANGED.


After the user unlocked the phone, the service and native probes both read the current Display page with privacy filtering allowed, the Light/Dark card pair, and system mode 1. The new local contract selected the exact Dark card (`item2`, bounds `[540, 317, 1080, 873]`) from both observations. This is native observation plus local matching, not a real Jev selection or completed mode change. The server remained at `9bd993d`, before `c88274f`, on two checks. No card was tapped. The initial implicit Display intent resumed Bluetooth; the capture used the explicit Display Activity with NEW_TASK|CLEAR_TOP, so this setup is not evidence of agent navigation success.
