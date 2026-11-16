from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator, model_validator

from app.schemas.guidance import GuideCommandResponse
from app.schemas.execution import ExecutionContext, ExecutionState
from ai_agent.runtime.execution_contract import validate_context

VisionFailureCode = Literal["masking_failed", "capture_failed", "ocr_failed", "screen_changed", "unsupported"]


def validate_vision_failure(request):
    context=getattr(request,"execution_context",None)
    if context is not None:
        validate_context(context.model_dump(), session_id=request.session_id,request_id=request.request_id,
            episode_id=getattr(request,"episode_id",None),version=request.expected_version or 0,
            observation_id=request.accessibility_tree.observation_id if request.accessibility_tree else None)
    provenance=getattr(request,"approval_provenance",None)
    if provenance is not None and (request.user_confirmation!="accepted" or request.confirmation_id is None):
        raise ValueError("Approval provenance requires an accepted confirmation")
    if request.vision_failure is not None and (request.user_confirmation != "rejected" or request.vision_snapshot is not None):
        raise ValueError("vision_failure requires rejected confirmation and no vision payload")


class AccessibilityNode(BaseModel):
    """Android AccessibilityNode payload accepted at the BE/Agent boundary."""

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    node_id: str
    window_id: int | None = None
    parent_id: str | None = Field(default=None, description="Parent node_id within this snapshot; null for a root or legacy flat payload")
    visible_to_user: bool = Field(default=True, description="Android isVisibleToUser; hidden nodes are not guide targets")
    package_name: str | None = None
    class_name: str | None = None
    text: str | None = None
    content_description: str | None = None
    resource_id: str | None = None
    bounds_in_screen: tuple[int, int, int, int] = Field(
        default=(0, 0, 0, 0),
        validation_alias=AliasChoices("bounds_in_screen", "bounds"),
        description="Screen pixel rectangle [left, top, right, bottom]; the client converts to overlay-local coordinates when drawing",
    )
    clickable: bool = False
    enabled: bool = True
    selected: bool = False
    scrollable: bool = False
    checkable: bool = False
    role: str | None = None
    extras: dict[str, Any] = Field(default_factory=dict)

    @field_validator("bounds_in_screen")
    @classmethod
    def validate_bounds(cls, value: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
        left, top, right, bottom = value
        if right < left or bottom < top:
            raise ValueError("bounds_in_screen must be [left, top, right, bottom]")
        return value


class AppliedSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source: Literal["android.provider.Settings.System.FONT_SCALE"] | None = None
    font_scale: float | None = Field(default=None, gt=0, le=10, allow_inf_nan=False)
    brightness_source: Literal["android.provider.Settings.System.SCREEN_BRIGHTNESS"] | None = None
    screen_brightness: int | None = Field(default=None, ge=0, le=65535)
    media_volume_source: Literal["android.media.AudioManager.STREAM_MUSIC"] | None = None
    media_volume: int | None = Field(default=None, ge=0, le=1000)
    wifi_state: int | None = Field(default=None, strict=True, ge=0, le=4)
    wifi_source: Literal["android.net.wifi.WifiManager.getWifiState"] | None = None
    night_mode: int | None = Field(default=None, strict=True, ge=0, le=3)
    night_mode_source: Literal["android.app.UiModeManager.getNightMode"] | None = None

    ringer_mode: int | None = Field(default=None, strict=True, ge=0, le=2)
    ringer_mode_source: Literal["android.media.AudioManager.getRingerMode"] | None = None

    display_density_dpi: int | None = Field(default=None, strict=True, ge=72, le=2000)
    display_density_source: Literal["android.content.res.Configuration.densityDpi"] | None = None

    @model_validator(mode="after")
    def require_value_sources(self):
        for value, source in ((self.font_scale, self.source), (self.screen_brightness, self.brightness_source),
                              (self.media_volume, self.media_volume_source), (self.wifi_state, self.wifi_source),
                              (self.night_mode, self.night_mode_source), (self.ringer_mode, self.ringer_mode_source),
                              (self.display_density_dpi, self.display_density_source)):
            if (value is None) != (source is None):
                raise ValueError("Applied setting value and source must be supplied together")
        return self


class NativeWindowContext(BaseModel):
    model_config = ConfigDict(extra="forbid")
    settings_activity: Literal["UNKNOWN", "HOME", "SUBPAGE"] = "UNKNOWN"
    window_kind: Literal["UNKNOWN", "APPLICATION", "INPUT_METHOD", "SYSTEM", "OVERLAY", "DIALOG"] = "UNKNOWN"
    keyboard_visible: bool = Field(default=False, strict=True)


class AccessibilityTree(BaseModel):
    model_config = ConfigDict(extra="allow")

    package_name: str | None = None
    active_window_id: int | None = None
    window_context: NativeWindowContext | None = None
    screen_revision: str | None = Field(default=None, min_length=1, max_length=100)
    captured_at: datetime | None = None
    observation_id: str | None = Field(default=None, min_length=1, max_length=200)
    captured_at_ms: int | None = Field(default=None, ge=0)
    applied_settings: AppliedSettings | None = None
    screen_width: int = Field(default=1, gt=0)
    screen_height: int = Field(default=1, gt=0)
    nodes: list[AccessibilityNode] = Field(max_length=2000)

    @model_validator(mode="after")
    def require_package_name(self) -> "AccessibilityTree":
        if self.package_name is None:
            self.package_name = next(
                (node.package_name for node in self.nodes if node.package_name),
                None,
            )
        if self.package_name is None and self.nodes:
            raise ValueError("package_name is required on the tree or at least one node")
        return self


class VisionElement(BaseModel):
    element_id: str
    text: str = Field(min_length=1, max_length=200)
    bounds_in_screen: tuple[int, int, int, int] | None = Field(
        default=None,
        validation_alias=AliasChoices("bounds_in_screen", "bounds"),
    )
    role: str | None = None
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    actionable: bool = True

    @field_validator("bounds_in_screen")
    @classmethod
    def validate_bounds(
        cls,
        value: tuple[int, int, int, int] | None,
    ) -> tuple[int, int, int, int] | None:
        if value is None:
            return None
        left, top, right, bottom = value
        if right < left or bottom < top:
            raise ValueError("bounds_in_screen must be [left, top, right, bottom]")
        return value


class VisionSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_consented: bool = False
    screen_revision: str | None = Field(default=None, min_length=1, max_length=100)
    redaction_applied: bool = Field(default=False, description="Client hint only; does not authorize external image processing")
    provider: str | None = None
    screenshot_base64: str | None = Field(default=None, max_length=8_000_000, description="Rejected for vision use; raw image egress is disabled. Send on-device OCR elements after per-use confirmation.")
    mime_type: str = "image/png"
    ocr_text: str | None = Field(default=None, max_length=10_000)
    elements: list[VisionElement] = Field(default_factory=list, max_length=200)
    package_name: str | None = None
    screen_width: int | None = Field(default=None, gt=0)
    screen_height: int | None = Field(default=None, gt=0)
    captured_at_ms: int | None = None
    observation_id: str | None = Field(default=None, min_length=1, max_length=200)
    active_window_id: int | None = None
    processing_time_ms: int | None = Field(default=None, ge=0, le=60_000)

    @model_validator(mode="after")
    def reject_raw_screenshots(self) -> "VisionSnapshot":
        if self.screenshot_base64 is not None:
            raise ValueError("raw screenshots must not be sent; send on-device OCR elements")
        return self


class ApprovalProvenance(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source: Literal["user", "delegated_goal"]
    goal_type: str | None = Field(default=None, max_length=100)
    granted_at_ms: int | None = Field(default=None, ge=0)
    expires_at_ms: int | None = Field(default=None, ge=0)
    max_actions: int | None = Field(default=None, strict=True, ge=1, le=1)
    max_delta_fraction: float | None = Field(default=None, gt=0, le=0.2, allow_inf_nan=False)

    @model_validator(mode="after")
    def bounded(self):
        values=(self.goal_type,self.granted_at_ms,self.expires_at_ms,self.max_actions,self.max_delta_fraction)
        if self.source=="delegated_goal":
            if any(v is None for v in values) or not 0<self.expires_at_ms-self.granted_at_ms<=300000:
                raise ValueError("Delegation requires a single bounded five-minute grant")
        elif any(v is not None for v in values):
            raise ValueError("Direct approval does not carry delegation")
        return self


class AgentTurnRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=200)
    session_id: str = Field(min_length=1, max_length=200)
    user_id: str | None = Field(default=None, min_length=36, max_length=36)
    expected_version: int | None = Field(default=None, ge=0)
    raw_goal: str | None = Field(default=None, min_length=1, max_length=500)
    accessibility_tree: AccessibilityTree | None = None
    vision_snapshot: VisionSnapshot | None = None
    vision_failure: VisionFailureCode | None = None
    user_confirmation: Literal["accepted", "rejected"] | None = None
    confirmation_id: str | None = Field(default=None, min_length=36, max_length=36)
    approval_provenance: ApprovalProvenance | None = None
    execution_context: ExecutionContext | None = None
    user_action_event: "UserActionEventRequest | None" = None

    @model_validator(mode="after")
    def require_screen_observation(self) -> "AgentTurnRequest":
        validate_vision_failure(self)
        if (self.accessibility_tree is None and self.vision_snapshot is None
                and not (self.user_confirmation == "rejected" and self.vision_failure is None)):
            raise ValueError("accessibility_tree or vision_snapshot is required")
        return self


class UserActionEventRequest(BaseModel):
    session_id: str | None = None
    episode_id: str | None = None
    event_id: str | None = Field(default=None, min_length=1, max_length=200)
    action_type: Literal[
        "tap_completed",
        "drag_completed",
        "scroll_completed",
        "scroll_failed",
        "back_navigated",
        "home_navigated",
        "manual_override",
        "observe_only",
    ]
    guide_command_id: str | None = None
    target_node_id: str | None = None
    target_bounds: tuple[int, int, int, int] | None = None
    occurred_at_ms: int | None = Field(default=None, ge=0)


class AgentTurnResponse(BaseModel):
    execution_state: ExecutionState | None = None
    request_id: str
    session_id: str
    episode_id: str | None = None
    session_version: int = 0
    task_id: str
    step: int
    total_steps: int
    state_id: str
    state_confidence: float
    task_status: Literal["IN_PROGRESS", "WAITING_CONFIRMATION", "WAITING_RESULT", "COMPLETED", "CANCELLED", "BLOCKED"]
    guide_command: GuideCommandResponse
    metadata: dict[str, Any] = Field(default_factory=dict)


class TypedGoalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    catalog_version: Literal["1.0.0", "1.1.0", "2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"]
    goal_type: Literal["settings.font_size.increase", "settings.font_size.decrease",
                       "settings.wifi.enable", "settings.wifi.disable", "settings.wifi.connect",
                       "settings.bluetooth.enable", "settings.bluetooth.disable", "settings.bluetooth.connect",
                       "settings.mobile_data.enable", "settings.mobile_data.disable",
                       "settings.airplane_mode.enable", "settings.airplane_mode.disable",
                       "settings.hotspot.enable", "settings.hotspot.disable",
                       "settings.brightness.increase", "settings.brightness.decrease",
                       "settings.dark_mode.enable", "settings.dark_mode.disable",
                       "settings.screen_timeout.set",
                       "settings.display_size.increase", "settings.display_size.decrease",
                       "settings.volume.increase", "settings.volume.decrease",
                       "settings.mute.enable", "settings.mute.disable",
                       "settings.ringtone.change",
                       "settings.vibrate.enable", "settings.vibrate.disable",
                       "settings.notifications.enable", "settings.notifications.disable",
                       "settings.location.enable", "settings.location.disable",
                       "settings.power_saving.enable", "settings.power_saving.disable",
                       "settings.screen_lock.change",
                       "settings.biometric.setup", "settings.apps.uninstall",
                       "settings.app_permissions.manage", "settings.storage.cleanup",
                       "settings.talkback.enable", "settings.talkback.disable",
                       "settings.magnification.enable",
                       "settings.magnification.disable",
                       "settings.language.change",
                       "settings.date_time.automatic.enable",
                       "settings.date_time.automatic.disable",
                       "settings.keyboard.change",
                       "settings.default_apps.change",
                       "settings.wifi.share",
                       "settings.wifi.saved_networks.view",
                       "settings.nfc.enable",
                       "settings.nfc.disable",
                       "settings.do_not_disturb.enable",
                       "settings.do_not_disturb.disable",
                       "settings.auto_rotate.enable",
                       "settings.auto_rotate.disable",
                       "settings.eye_comfort.enable",
                       "settings.eye_comfort.disable",
                       "settings.adaptive_brightness.enable",
                       "settings.adaptive_brightness.disable",
                       "settings.data_saver.enable",
                       "settings.data_saver.disable",
                       "settings.data_roaming.enable",
                       "settings.data_roaming.disable",
                       "settings.vpn.connect",
                       "settings.vpn.disconnect",
                       "settings.private_dns.change",
                       "settings.sim.manage",
                       "settings.hotspot.password.change",
                       "settings.wallpaper.change",
                       "settings.notification_sound.change",
                       "settings.battery_percentage.enable",
                       "settings.battery_percentage.disable",
                       "settings.battery_status.view",
                       "settings.app_cache.clear",
                       "settings.app_data.clear",
                       "settings.notification_history.view",
                       "settings.lockscreen_notifications.manage",
                       "settings.color_inversion.enable",
                       "settings.color_inversion.disable",
                       "settings.captions.enable",
                       "settings.captions.disable",
                       "settings.mono_audio.enable",
                       "settings.mono_audio.disable",
                       "settings.backup.enable",
                       "settings.backup.disable",
                       "settings.software_update.check",
                       "settings.factory_reset",
                       "settings.device_name.change",
                       "settings.navigation.settings", "settings.navigation.display", "settings.navigation.sound", "settings.navigation.connections", "settings.navigation.accessibility", "settings.navigation.wifi", "settings.navigation.bluetooth", "settings.navigation.font_size", "settings.navigation.screen_timeout", "settings.navigation.display_size", "settings.navigation.volume", "settings.navigation.ringtone", "settings.navigation.notification_sound", "settings.navigation.do_not_disturb", "settings.navigation.visibility", "settings.navigation.hearing", "settings.navigation.interaction", "settings.navigation.talkback", "settings.navigation.magnification", "settings.navigation.captions", "settings.navigation.eye_comfort", "coupang.product.search", "coupang.product.details", "coupang.cart.add", "coupang.cart.view", "coupang.cart.remove", "coupang.order.checkout", "coupang.order.list", "coupang.order.track", "coupang.order.cancel", "coupang.order.return", "coupang.order.exchange", "coupang.review.write", "coupang.review.view", "coupang.wishlist.add", "coupang.wishlist.remove", "coupang.wishlist.view", "coupang.address.add", "coupang.address.change", "coupang.payment_method.add", "coupang.payment_method.change", "coupang.coupon.view", "coupang.coupon.apply", "coupang.rocket_delivery.filter", "coupang.category.browse", "coupang.seller.info", "coupang.customer_service.open", "coupang.rocket_wow.manage", "coupang.order.reorder", "coupang.receipt.view", "coupang.search.filter", "coupang.search.sort", "coupang.delivery_date.check", "coupang.order.gift", "coupang.cash_receipt.manage", "coupang.product.price.check", "coupang.product.stock.check", "coupang.product.compare", "coupang.product.share", "coupang.product.option.select", "coupang.product.restock_notification", "coupang.product.inquiry.write", "coupang.product.inquiry.view", "coupang.cart.quantity.increase", "coupang.cart.quantity.decrease", "coupang.cart.select", "coupang.cart.unselect", "coupang.order.details", "coupang.order.address.change", "coupang.delivery.instructions.change", "coupang.order.cancel_status", "coupang.order.refund_status", "coupang.order.return_status", "coupang.order.exchange_status", "coupang.order.return_pickup.track", "coupang.review.edit", "coupang.review.delete", "coupang.review.helpful", "coupang.address.remove", "coupang.payment_method.remove", "coupang.coupon.download", "coupang.search.recent.view", "coupang.search.recent.clear", "coupang.account.login", "coupang.account.logout", "coupang.profile.change", "coupang.rocket_wow.benefits", "coupang.recurring_delivery.manage", "coupang.rocket_fresh.filter", "coupang.coupay.balance.view", "coupang.coupay.money.add", "coupang.points.view"]
    target_app: Literal["android_settings", "coupang"]
    slots: dict[str, str]

    @model_validator(mode="after")
    def slots_match_goal(self) -> "TypedGoalRequest":
        if self.goal_type.startswith('coupang.'):
            import json
            from pathlib import Path
            catalog = json.loads((Path(__file__).parents[1] / 'task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
            entry = next((g for g in catalog['goals'] if g['goal_type'] == self.goal_type), None)
            if (self.catalog_version != '2.4.0' or self.target_app != 'coupang'
                    or entry is None or self.slots != entry['slots']):
                raise ValueError('Coupang goals require catalog 2.4.0 and matching slots')
            return self
        if self.target_app != 'android_settings':
            raise ValueError('Settings goals require the settings app')
        # Request validation must work before an execution graph is available.
        expected_slots = {
            "settings.font_size.increase": {"setting": "font_size", "direction": "increase"},
            "settings.font_size.decrease": {"setting": "font_size", "direction": "decrease"},
            "settings.wifi.enable": {"setting": "wifi", "state": "on"},
            "settings.wifi.disable": {"setting": "wifi", "state": "off"},
            "settings.wifi.connect": {"setting": "wifi", "action": "connect"},
            "settings.bluetooth.enable": {"setting": "bluetooth", "state": "on"},
            "settings.bluetooth.disable": {"setting": "bluetooth", "state": "off"},
            "settings.bluetooth.connect": {"setting": "bluetooth", "action": "connect"},
            "settings.mobile_data.enable": {"setting": "mobile_data", "state": "on"},
            "settings.mobile_data.disable": {"setting": "mobile_data", "state": "off"},
            "settings.airplane_mode.enable": {"setting": "airplane_mode", "state": "on"},
            "settings.airplane_mode.disable": {"setting": "airplane_mode", "state": "off"},
            "settings.hotspot.enable": {"setting": "hotspot", "state": "on"},
            "settings.hotspot.disable": {"setting": "hotspot", "state": "off"},
            "settings.brightness.increase": {"setting": "brightness", "direction": "increase"},
            "settings.brightness.decrease": {"setting": "brightness", "direction": "decrease"},
            "settings.dark_mode.enable": {"setting": "dark_mode", "state": "on"},
            "settings.dark_mode.disable": {"setting": "dark_mode", "state": "off"},
            "settings.screen_timeout.set": {"setting": "screen_timeout", "action": "set"},
            "settings.display_size.increase": {"setting": "display_size", "direction": "increase"},
            "settings.display_size.decrease": {"setting": "display_size", "direction": "decrease"},
            "settings.volume.increase": {"setting": "volume", "direction": "increase"},
            "settings.volume.decrease": {"setting": "volume", "direction": "decrease"},
            "settings.mute.enable": {"setting": "mute", "state": "on"},
            "settings.mute.disable": {"setting": "mute", "state": "off"},
            "settings.ringtone.change": {"setting": "ringtone", "action": "change"},
            "settings.vibrate.enable": {"setting": "vibrate", "state": "on"},
            "settings.vibrate.disable": {"setting": "vibrate", "state": "off"},
            "settings.notifications.enable": {"setting": "notifications", "state": "on"},
            "settings.notifications.disable": {"setting": "notifications", "state": "off"},
            "settings.location.enable": {"setting": "location", "state": "on"},
            "settings.location.disable": {"setting": "location", "state": "off"},
            "settings.power_saving.enable": {"setting": "power_saving", "state": "on"},
            "settings.power_saving.disable": {"setting": "power_saving", "state": "off"},
            "settings.screen_lock.change": {"setting": "screen_lock", "action": "change"},
            "settings.biometric.setup": {"setting": "biometric", "action": "setup"},
            "settings.apps.uninstall": {"setting": "apps", "action": "uninstall"},
            "settings.app_permissions.manage": {"setting": "app_permissions", "action": "manage"},
            "settings.storage.cleanup": {"setting": "storage", "action": "cleanup"},
            "settings.talkback.enable": {"setting": "talkback", "state": "on"},
            "settings.talkback.disable": {"setting": "talkback", "state": "off"},
            "settings.magnification.enable": {"setting": "magnification", "state": "on"},
            "settings.magnification.disable": {"setting": "magnification", "state": "off"},
            "settings.language.change": {"setting": "language", "action": "change"},
            "settings.date_time.automatic.enable": {"setting": "automatic_date_time", "state": "on"},
            "settings.date_time.automatic.disable": {"setting": "automatic_date_time", "state": "off"},
            "settings.keyboard.change": {"setting": "keyboard", "action": "change"},
            "settings.default_apps.change": {"setting": "default_apps", "action": "change"},
            "settings.wifi.share": {"setting": "wifi", "action": "share"},
            "settings.wifi.saved_networks.view": {"setting": "wifi_saved_networks", "action": "view"},
            "settings.nfc.enable": {"setting": "nfc", "state": "on"},
            "settings.nfc.disable": {"setting": "nfc", "state": "off"},
            "settings.do_not_disturb.enable": {"setting": "do_not_disturb", "state": "on"},
            "settings.do_not_disturb.disable": {"setting": "do_not_disturb", "state": "off"},
            "settings.auto_rotate.enable": {"setting": "auto_rotate", "state": "on"},
            "settings.auto_rotate.disable": {"setting": "auto_rotate", "state": "off"},
            "settings.eye_comfort.enable": {"setting": "eye_comfort", "state": "on"},
            "settings.eye_comfort.disable": {"setting": "eye_comfort", "state": "off"},
            "settings.adaptive_brightness.enable": {"setting": "adaptive_brightness", "state": "on"},
            "settings.adaptive_brightness.disable": {"setting": "adaptive_brightness", "state": "off"},
            "settings.data_saver.enable": {"setting": "data_saver", "state": "on"},
            "settings.data_saver.disable": {"setting": "data_saver", "state": "off"},
            "settings.data_roaming.enable": {"setting": "data_roaming", "state": "on"},
            "settings.data_roaming.disable": {"setting": "data_roaming", "state": "off"},
            "settings.vpn.connect": {"setting": "vpn", "action": "connect"},
            "settings.vpn.disconnect": {"setting": "vpn", "action": "disconnect"},
            "settings.private_dns.change": {"setting": "private_dns", "action": "change"},
            "settings.sim.manage": {"setting": "sim", "action": "manage"},
            "settings.hotspot.password.change": {"setting": "hotspot_password", "action": "change"},
            "settings.wallpaper.change": {"setting": "wallpaper", "action": "change"},
            "settings.notification_sound.change": {"setting": "notification_sound", "action": "change"},
            "settings.battery_percentage.enable": {"setting": "battery_percentage", "state": "on"},
            "settings.battery_percentage.disable": {"setting": "battery_percentage", "state": "off"},
            "settings.battery_status.view": {"setting": "battery_status", "action": "view"},
            "settings.app_cache.clear": {"setting": "app_cache", "action": "clear"},
            "settings.app_data.clear": {"setting": "app_data", "action": "clear"},
            "settings.notification_history.view": {"setting": "notification_history", "action": "view"},
            "settings.lockscreen_notifications.manage": {"setting": "lockscreen_notifications", "action": "manage"},
            "settings.color_inversion.enable": {"setting": "color_inversion", "state": "on"},
            "settings.color_inversion.disable": {"setting": "color_inversion", "state": "off"},
            "settings.captions.enable": {"setting": "captions", "state": "on"},
            "settings.captions.disable": {"setting": "captions", "state": "off"},
            "settings.mono_audio.enable": {"setting": "mono_audio", "state": "on"},
            "settings.mono_audio.disable": {"setting": "mono_audio", "state": "off"},
            "settings.backup.enable": {"setting": "backup", "state": "on"},
            "settings.backup.disable": {"setting": "backup", "state": "off"},
            "settings.software_update.check": {"setting": "software_update", "action": "check"},
            "settings.factory_reset": {"setting": "factory_reset", "action": "reset"},
            "settings.device_name.change": {"setting": "device_name", "action": "change"},
        }
        expected_slots.update({
            "settings.navigation.settings": {"setting": "navigation_settings", "action": "open"},
            "settings.navigation.display": {"setting": "navigation_display", "action": "open"},
            "settings.navigation.sound": {"setting": "navigation_sound", "action": "open"},
            "settings.navigation.connections": {"setting": "navigation_connections", "action": "open"},
            "settings.navigation.accessibility": {"setting": "navigation_accessibility", "action": "open"},
            "settings.navigation.wifi": {"setting": "navigation_wifi", "action": "open"},
            "settings.navigation.bluetooth": {"setting": "navigation_bluetooth", "action": "open"},
            "settings.navigation.font_size": {"setting": "navigation_font_size", "action": "open"},
            "settings.navigation.screen_timeout": {"setting": "navigation_screen_timeout", "action": "open"},
            "settings.navigation.display_size": {"setting": "navigation_display_size", "action": "open"},
            "settings.navigation.volume": {"setting": "navigation_volume", "action": "open"},
            "settings.navigation.ringtone": {"setting": "navigation_ringtone", "action": "open"},
            "settings.navigation.notification_sound": {"setting": "navigation_notification_sound", "action": "open"},
            "settings.navigation.do_not_disturb": {"setting": "navigation_do_not_disturb", "action": "open"},
            "settings.navigation.visibility": {"setting": "navigation_visibility", "action": "open"},
            "settings.navigation.hearing": {"setting": "navigation_hearing", "action": "open"},
            "settings.navigation.interaction": {"setting": "navigation_interaction", "action": "open"},
            "settings.navigation.talkback": {"setting": "navigation_talkback", "action": "open"},
            "settings.navigation.magnification": {"setting": "navigation_magnification", "action": "open"},
            "settings.navigation.captions": {"setting": "navigation_captions", "action": "open"},
            "settings.navigation.eye_comfort": {"setting": "navigation_eye_comfort", "action": "open"},
        })
        if self.goal_type.startswith("settings.navigation.") and self.catalog_version not in {"2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Settings navigation requires catalog version 2.2.0")
        if self.slots != expected_slots[self.goal_type]:
            raise ValueError("goal_type and slots must match")
        if self.goal_type == "settings.font_size.decrease" and self.catalog_version == "1.0.0":
            raise ValueError("font-size decrease requires catalog version 1.1.0 or newer")
        if self.goal_type.startswith("settings.wifi.") and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Wi-Fi goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith("settings.bluetooth.") and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Bluetooth goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith("settings.mobile_data.") and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Mobile data goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.airplane_mode.", "settings.hotspot.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Airplane mode and hotspot goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.brightness.", "settings.dark_mode.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Brightness and dark mode goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.screen_timeout.", "settings.display_size.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Screen timeout and display size goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.volume.", "settings.mute.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Volume and mute goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.ringtone.", "settings.vibrate.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Ringtone and vibrate goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.notifications.", "settings.location.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Notifications and location goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.power_saving.", "settings.screen_lock.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Power saving and screen lock goals require catalog version 2.0.0 or newer")
        if self.goal_type.startswith(("settings.biometric.", "settings.apps.", "settings.app_permissions.")) and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Biometric and app management goals require catalog version 2.0.0 or newer")
        if self.goal_type == "settings.storage.cleanup" and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Storage cleanup requires catalog version 2.0.0 or newer")
        if self.goal_type.startswith("settings.talkback.") and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("TalkBack goals require catalog version 2.0.0 or newer")
        remaining_settings = {
            "settings.magnification.enable",
            "settings.magnification.disable",
            "settings.language.change",
            "settings.date_time.automatic.enable",
            "settings.date_time.automatic.disable",
            "settings.keyboard.change",
            "settings.default_apps.change",
            "settings.wifi.share",
            "settings.wifi.saved_networks.view",
            "settings.nfc.enable",
            "settings.nfc.disable",
            "settings.do_not_disturb.enable",
            "settings.do_not_disturb.disable",
            "settings.auto_rotate.enable",
            "settings.auto_rotate.disable",
            "settings.eye_comfort.enable",
            "settings.eye_comfort.disable",
            "settings.adaptive_brightness.enable",
            "settings.adaptive_brightness.disable",
            "settings.data_saver.enable",
            "settings.data_saver.disable",
            "settings.data_roaming.enable",
            "settings.data_roaming.disable",
            "settings.vpn.connect",
            "settings.vpn.disconnect",
            "settings.private_dns.change",
            "settings.sim.manage",
            "settings.hotspot.password.change",
            "settings.wallpaper.change",
            "settings.notification_sound.change",
            "settings.battery_percentage.enable",
            "settings.battery_percentage.disable",
            "settings.battery_status.view",
            "settings.app_cache.clear",
            "settings.app_data.clear",
            "settings.notification_history.view",
            "settings.lockscreen_notifications.manage",
            "settings.color_inversion.enable",
            "settings.color_inversion.disable",
            "settings.captions.enable",
            "settings.captions.disable",
            "settings.mono_audio.enable",
            "settings.mono_audio.disable",
            "settings.backup.enable",
            "settings.backup.disable",
            "settings.software_update.check",
            "settings.factory_reset",
            "settings.device_name.change",
        }
        if self.goal_type in remaining_settings and self.catalog_version not in {"2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.3.1", "2.3.2", "2.4.0"}:
            raise ValueError("Remaining settings goals require catalog version 2.0.0 or newer")
        return self


class DeviceCapabilities(BaseModel):
    model_config = ConfigDict(extra="forbid")
    accessibility_tree: bool = True
    guide_overlay: bool = True
    automatic_scroll: bool = False


class DeviceContextRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    platform: Literal["android"]
    locale: str = Field(min_length=2, max_length=35)
    manufacturer: str | None = Field(default=None, max_length=100)
    os_version: str | None = Field(default=None, max_length=100)
    foreground_package: str | None = Field(default=None, max_length=300)
    app_version: str | None = Field(default=None, max_length=100)
    capabilities: DeviceCapabilities = Field(default_factory=DeviceCapabilities)


class ClassificationMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid")
    resolved_by: str | None = Field(default=None, max_length=50)
    method: str | None = Field(default=None, max_length=100)
    goal_confidence: float | None = Field(default=None, ge=0, le=1)


from app.schemas.goal_preparation import ClarificationAnswer, GoalPreparation


class AgentCommandRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    contract_version: Literal["agent-fe-contract.v0.2", "agent-fe-contract.v0.3"]
    request_id: str = Field(min_length=1, max_length=200)
    session_id: str = Field(min_length=1, max_length=200)
    expected_version: int = Field(ge=0)
    episode_id: str | None = Field(default=None, min_length=1, max_length=200)
    command_type: Literal["resolve_goal", "cancel_goal", "start_task", "pause_task", "resume_task", "cancel_task", "replace_task", "observe", "respond_confirmation", "respond_result", "adjust_task", "request_result"]
    goal_resolution_id: str | None = Field(default=None, max_length=200)
    clarification_choice: Literal["unsure", "different"] | None = None
    preparation_version: Literal["goal-inputs.v1"] | None = None
    preparation_reset: bool = False
    clarification_answer: ClarificationAnswer | None = None
    workflow_version: Literal["interactive.v1"] | None = None
    result_prompt_id: str | None = Field(default=None, min_length=1, max_length=200)
    user_result: Literal["satisfied", "needs_help", "stopped", "no_response"] | None = None
    adjustment_reason: Literal["too_small", "too_large", "other", "unsure"] | None = None
    goal: TypedGoalRequest | None = None
    device_context: DeviceContextRequest | None = None
    accessibility_tree: AccessibilityTree | None = None
    vision_snapshot: VisionSnapshot | None = None
    vision_failure: VisionFailureCode | None = None
    user_action_event: UserActionEventRequest | None = None
    user_confirmation: Literal["accepted", "rejected"] | None = None
    confirmation_id: str | None = Field(default=None, min_length=36, max_length=36)
    approval_provenance: ApprovalProvenance | None = None
    execution_context: ExecutionContext | None = None
    source_text: str | None = Field(default=None, max_length=500)
    classification: ClassificationMetadata | None = None
    result_response: Literal["accepted", "rejected"] | None = Field(default=None, exclude=True)
    user_id: str | None = Field(default=None, exclude=True)

    @model_validator(mode="after")
    def validate_command_shape(self) -> "AgentCommandRequest":
        validate_vision_failure(self)
        if self.command_type in {"resolve_goal", "cancel_goal"} \
                and self.contract_version != "agent-fe-contract.v0.3":
            raise ValueError("This command requires v0.3")
        if self.preparation_version and self.contract_version != "agent-fe-contract.v0.3":
            raise ValueError("goal input preparation requires v0.3")
        if self.preparation_version and self.command_type not in {"resolve_goal", "start_task", "cancel_goal"}:
            raise ValueError("preparation is only used before task execution")
        if (self.clarification_answer is not None or self.preparation_reset) and (
                self.command_type != "resolve_goal" or not self.preparation_version):
            raise ValueError("clarification fields require prepared resolve_goal")
        if self.clarification_answer and (not self.goal_resolution_id or self.preparation_reset):
            raise ValueError("answer must reference its current resolution")
        if self.command_type in {"resolve_goal", "cancel_goal"}:
            if self.goal is not None or self.episode_id is not None or self.expected_version != 0:
                raise ValueError("Goal resolution precedes task execution")
            if self.command_type == "resolve_goal" and (not self.source_text or not self.source_text.strip()):
                raise ValueError("source_text is required")
            if self.command_type == "resolve_goal" and self.device_context is None:
                if self.contract_version == "agent-fe-contract.v0.3":
                    raise ValueError("device_context is required")
                self.device_context = DeviceContextRequest(platform="android", locale="ko-KR")
            if self.command_type == "cancel_goal" and not self.goal_resolution_id:
                raise ValueError("goal_resolution_id is required")
            if any((self.user_result, self.result_prompt_id, self.result_response, self.adjustment_reason,
                    self.workflow_version, self.user_confirmation, self.confirmation_id, self.accessibility_tree,
                    self.vision_snapshot, self.vision_failure, self.user_action_event)):
                raise ValueError("Execution fields are not allowed during goal preparation")
            return self
        if self.clarification_choice:
            raise ValueError("clarification_choice requires resolve_goal")
        if self.preparation_version and self.command_type == "start_task" and not self.goal_resolution_id:
            raise ValueError("Prepared start requires goal_resolution_id")
        if self.workflow_version and (self.command_type != "start_task" or self.contract_version != "agent-fe-contract.v0.3"):
            raise ValueError("interactive workflow requires a v0.3 start")
        if self.command_type == "respond_result":
            if bool(self.user_result) == bool(self.result_response):
                raise ValueError("Exactly one result answer is required")
            if self.user_result and not self.result_prompt_id:
                raise ValueError("Result answer must reference its prompt")
        elif self.user_result or self.result_prompt_id or self.result_response:
            raise ValueError("Result fields require respond_result")
        if self.adjustment_reason and self.command_type != "adjust_task":
            raise ValueError("adjustment_reason requires adjust_task")
        starts = self.command_type in {"start_task", "replace_task"} or (
            self.command_type == "adjust_task" and self.adjustment_reason is None)
        if starts != (self.goal is not None):
            raise ValueError("goal is required for starting or replacing a task")
        if starts and self.device_context is None:
            raise ValueError("device_context is required")
        if self.command_type == "start_task" and self.episode_id is not None:
            raise ValueError("episode_id must be omitted before starting")
        if self.command_type != "start_task" and self.episode_id is None:
            raise ValueError("episode_id is required after start_task")
        needs_screen = starts or self.command_type in {"resume_task", "observe"} or (
            self.command_type == "respond_confirmation" and not (
                self.user_confirmation == "rejected" and self.vision_failure is None))
        if needs_screen and self.accessibility_tree is None and self.vision_snapshot is None:
            raise ValueError("a screen observation is required")
        if self.command_type == "respond_confirmation":
            if self.user_confirmation is None or self.confirmation_id is None:
                raise ValueError("confirmation response and confirmation_id are required")
        elif self.user_confirmation is not None or self.confirmation_id is not None:
            raise ValueError("confirmation fields require respond_confirmation")
        return self


class CommandResult(BaseModel):
    command_type: str
    status: Literal["applied", "unchanged"]


class GraphReference(BaseModel):
    task_id: str
    version: str
    sha256: str


class AgentCommandResponse(BaseModel):
    execution_state: ExecutionState | None = None
    contract_version: Literal["agent-fe-contract.v0.2", "agent-fe-contract.v0.3"] = "agent-fe-contract.v0.2"
    request_id: str
    session_id: str
    session_version: int
    episode_id: str | None = None
    response_type: Literal["guidance", "control", "goal_selection", "result"]
    command_result: CommandResult
    accepted_goal_type: str | None = None
    task_id: str | None = None
    graph_reference: GraphReference | None = None
    task_status: str
    state_id: str | None = None
    step: int | None = None
    guide_command: GuideCommandResponse | None = None
    message: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class AgentGoalResolutionResponse(BaseModel):
    execution_state: ExecutionState | None = None
    contract_version: Literal["agent-fe-contract.v0.3"] = "agent-fe-contract.v0.3"
    request_id: str
    session_id: str
    session_version: int
    response_type: Literal["goal_resolution"] = "goal_resolution"
    resolution_status: Literal["confirmation_required", "needs_clarification", "unsupported"]
    goal: TypedGoalRequest | None = None
    display_name: str | None = None
    preparation: GoalPreparation | None = None
    task_status: Literal["IN_PROGRESS"] = "IN_PROGRESS"
    metadata: dict[str, Any] = Field(default_factory=dict)
    message: str
    task_status: Literal["IN_PROGRESS"] = "IN_PROGRESS"
    metadata: dict[str, Any] = Field(default_factory=dict)
