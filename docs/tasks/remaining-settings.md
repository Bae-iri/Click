# Remaining settings: backend contracts

Range: `settings.magnification.enable` through `settings.device_name.change` (48 goals).

| Goal Type | slots |
| --- | --- |
| `settings.magnification.enable` | `{"setting": "magnification", "state": "on"}` |
| `settings.magnification.disable` | `{"setting": "magnification", "state": "off"}` |
| `settings.language.change` | `{"setting": "language", "action": "change"}` |
| `settings.date_time.automatic.enable` | `{"setting": "automatic_date_time", "state": "on"}` |
| `settings.date_time.automatic.disable` | `{"setting": "automatic_date_time", "state": "off"}` |
| `settings.keyboard.change` | `{"setting": "keyboard", "action": "change"}` |
| `settings.default_apps.change` | `{"setting": "default_apps", "action": "change"}` |
| `settings.wifi.share` | `{"setting": "wifi", "action": "share"}` |
| `settings.wifi.saved_networks.view` | `{"setting": "wifi_saved_networks", "action": "view"}` |
| `settings.nfc.enable` | `{"setting": "nfc", "state": "on"}` |
| `settings.nfc.disable` | `{"setting": "nfc", "state": "off"}` |
| `settings.do_not_disturb.enable` | `{"setting": "do_not_disturb", "state": "on"}` |
| `settings.do_not_disturb.disable` | `{"setting": "do_not_disturb", "state": "off"}` |
| `settings.auto_rotate.enable` | `{"setting": "auto_rotate", "state": "on"}` |
| `settings.auto_rotate.disable` | `{"setting": "auto_rotate", "state": "off"}` |
| `settings.eye_comfort.enable` | `{"setting": "eye_comfort", "state": "on"}` |
| `settings.eye_comfort.disable` | `{"setting": "eye_comfort", "state": "off"}` |
| `settings.adaptive_brightness.enable` | `{"setting": "adaptive_brightness", "state": "on"}` |
| `settings.adaptive_brightness.disable` | `{"setting": "adaptive_brightness", "state": "off"}` |
| `settings.data_saver.enable` | `{"setting": "data_saver", "state": "on"}` |
| `settings.data_saver.disable` | `{"setting": "data_saver", "state": "off"}` |
| `settings.data_roaming.enable` | `{"setting": "data_roaming", "state": "on"}` |
| `settings.data_roaming.disable` | `{"setting": "data_roaming", "state": "off"}` |
| `settings.vpn.connect` | `{"setting": "vpn", "action": "connect"}` |
| `settings.vpn.disconnect` | `{"setting": "vpn", "action": "disconnect"}` |
| `settings.private_dns.change` | `{"setting": "private_dns", "action": "change"}` |
| `settings.sim.manage` | `{"setting": "sim", "action": "manage"}` |
| `settings.hotspot.password.change` | `{"setting": "hotspot_password", "action": "change"}` |
| `settings.wallpaper.change` | `{"setting": "wallpaper", "action": "change"}` |
| `settings.notification_sound.change` | `{"setting": "notification_sound", "action": "change"}` |
| `settings.battery_percentage.enable` | `{"setting": "battery_percentage", "state": "on"}` |
| `settings.battery_percentage.disable` | `{"setting": "battery_percentage", "state": "off"}` |
| `settings.battery_status.view` | `{"setting": "battery_status", "action": "view"}` |
| `settings.app_cache.clear` | `{"setting": "app_cache", "action": "clear"}` |
| `settings.app_data.clear` | `{"setting": "app_data", "action": "clear"}` |
| `settings.notification_history.view` | `{"setting": "notification_history", "action": "view"}` |
| `settings.lockscreen_notifications.manage` | `{"setting": "lockscreen_notifications", "action": "manage"}` |
| `settings.color_inversion.enable` | `{"setting": "color_inversion", "state": "on"}` |
| `settings.color_inversion.disable` | `{"setting": "color_inversion", "state": "off"}` |
| `settings.captions.enable` | `{"setting": "captions", "state": "on"}` |
| `settings.captions.disable` | `{"setting": "captions", "state": "off"}` |
| `settings.mono_audio.enable` | `{"setting": "mono_audio", "state": "on"}` |
| `settings.mono_audio.disable` | `{"setting": "mono_audio", "state": "off"}` |
| `settings.backup.enable` | `{"setting": "backup", "state": "on"}` |
| `settings.backup.disable` | `{"setting": "backup", "state": "off"}` |
| `settings.software_update.check` | `{"setting": "software_update", "action": "check"}` |
| `settings.factory_reset` | `{"setting": "factory_reset", "action": "reset"}` |
| `settings.device_name.change` | `{"setting": "device_name", "action": "change"}` |

Endpoint: `POST /api/v1/agent/commands`, target app `android_settings`, catalog 2.0.0 or 2.1.0. Missing, mismatched, or extra slots and catalog 1.x are rejected. These are intent-only contracts; credentials, DNS hostnames, names, files, and target app identifiers are not accepted as extra slots.

Existing approved graph selection, hashing, and runtime forwarding are reused. No real device graph is supplied; missing graphs return HTTP 422 `INVALID_REQUEST` without calling Agent 1. The catalog remains `catalog_only`. Synthetic test graphs verify dispatch only.

Future execution must identify the actual setting and requested result, handle unsupported devices and confirmation screens, and verify postconditions. Factory reset and app data deletion require explicit destructive-action approval and observed completion. Roaming and SIM changes need cost/connectivity impact handling. Wi-Fi sharing and hotspot password changes must not store credentials in telemetry. Software update check is not installation; battery status view is not a setting mutation.
