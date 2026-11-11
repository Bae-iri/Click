# Common Settings application entry — 2026-11-09

The current server already uses Jev for request interpretation and screen decisions. The same recorded, filtered Samsung launcher selected Settings for font-size and Wi-Fi in six out of six isolated live calls. Font-size confidence was 0.89/0.89/0.92; Wi-Fi was 0.86/0.80/0.86. These are navigation selections without clicks, not completed tasks. The original failure is not reconstructed by this comparison.

Primary-model recovery could lose the platform Settings entry: a visible icon plus low model confidence did not trigger APP_NOT_OPEN, and recovery_command omitted its recovery_action. The app could then offer generic retry/home advice. The fix preserves the model's abstention and offers a user-activated Settings entry in the existing task panel. It submits manual_override with a fresh observation and retains the existing goal. There is no separate floating button window.

All 110 approved Settings graph identities are checked at this entry boundary. Actual OEM execution of all 110 tasks remains unverified. The client also understands bounded legacy-server model waits on the launcher, so installing the new APK can provide this recovery before the companion server change is deployed. Neither a privacy block nor an exhausted action budget authorizes this fallback.

Validation: Backend full suite 2,013 passed, final entry regressions 222 passed; AI-Agent 410 passed; Android full suite 527 passed before the last compatibility change, final release service/panel checks 152 passed, current workspace integration 156 passed. The integration APK preserves existing local UI edits. After USB reconnection the app and test APK were installed with app data preserved. The installed main APK hash matches the tested integration build. The existing accessibility grant was reconnected; both the service and native probe observed the Settings icon on the launcher, with privacy filtering allowed and masking ready. Physical guide activation and Wi-Fi completion remain unverified. No Wi-Fi state change was attempted.

See [bounded evidence](evidence/2026-11-09-common-settings-entry.json).
