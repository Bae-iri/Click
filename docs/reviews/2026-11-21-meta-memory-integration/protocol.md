# MetaController × memory interaction audit (frozen before live calls)

Base: origin/develop e4f5580. Device: SM-N981N, Android 13.

Factorial: CURRENT/META (current MCv2) × memory A off/B exact production memory.
Two tasks: settings_display and settings_notifications from the frozen memory catalogue.
For each task/cell: learn1 + learn2 under normal execution, then eval_normal,
eval_no_effect, eval_delayed. 40 scheduled episodes total. 25s episode budget.
A/B order uses existing rotation/reversal. CURRENT then META, separate fresh facade
and owner stores. This sequential factor order and evaluation order are limitations.
No normalization, no fabricated successful training. Production device route store,
AI2 analysis/grounding and Gemini AppTaskService are used; facade replaces DB only.

Fault: first allowlisted safe settings navigation tap in evaluation is acknowledged
without native dispatch, or dispatched after 5500ms under the original executor
fresh-frame policy. Log actual delayed acceptance separately. No setting values change.
Independent original UiAutomation goal oracle (700ms) gates simulated user confirmation.
Count setup failures and provider failures, audit actual memory proposal usage; if no
memory is used, do not interpret zero interaction as compatibility evidence.

Local conformance: 6 synthetic flows × 3 conditions × 2 repeats × 4 cells =144,
production controller + verifier + MCv2 with controlled planner and remembered-action
fixtures. This measures a conflict mechanism, not learning or model efficacy.
Preserve before/after rows and mark any corrected-code live run explicitly.

Execution note: corrected-code live runs use isolated package `com.click.metamemoryexperiment.uitestdriver`.
Original experiment package was preserved because its debug signing key differs on this Mac.
CURRENT server uses 8766; fresh META server uses 8767 behind device port 8766.
