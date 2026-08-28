"""TOML config loading, schema validation, defaults, per-device profile resolution."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

PHASE1_DETECTORS = ("black_frame", "silence", "loudness")
KNOWN_DETECTORS = PHASE1_DETECTORS + (
    "logo_absence",
    "scene_cut",
    "aspect_change",
    "caption_gap",
    "fingerprint",
)
KNOWN_CAPTURE_BACKENDS = (
    "hdmi_uvc",
    "camera",
    "microphone",
    "line_in",
    "screen",
    "file_replay",
)
KNOWN_CONTROL_BACKENDS = (
    "rs232_sharp",
    "ir_lirc",
    "ir_pigpio",
    "cec",
    "ir_blaster_net",
    "network_ip",
    "local_audio",
)


class ConfigError(ValueError):
    """Raised for malformed or inconsistent configuration."""


@dataclass(frozen=True, slots=True)
class CaptureConfig:
    backend: str = "hdmi_uvc"
    device: str = "/dev/video0"
    width: int = 1280
    height: int = 720
    fps: int = 30
    audio_device: str = "default"
    audio_rate: int = 48000
    audio_block_ms: int = 100
    path: str = ""  # file_replay input


@dataclass(frozen=True, slots=True)
class BlackFrameConfig:
    luma_threshold: int = 16
    min_run_frames: int = 3


@dataclass(frozen=True, slots=True)
class SilenceConfig:
    dbfs_threshold: float = -50.0
    min_run_ms: int = 400


@dataclass(frozen=True, slots=True)
class LoudnessConfig:
    # Shorter than R128's canonical 3 s short-term window: the window drain is
    # the dominant unmute latency, and a late unmute is the worse failure.
    window_s: float = 1.5
    delta_lufs: float = 2.5
    baseline_s: float = 120.0


@dataclass(frozen=True, slots=True)
class DetectConfig:
    enabled: tuple[str, ...] = PHASE1_DETECTORS
    black_frame: BlackFrameConfig = field(default_factory=BlackFrameConfig)
    silence: SilenceConfig = field(default_factory=SilenceConfig)
    loudness: LoudnessConfig = field(default_factory=LoudnessConfig)


@dataclass(frozen=True, slots=True)
class FusionConfig:
    mute_confidence: float = 0.72
    unmute_confidence: float = 0.45
    mute_dwell_ms: int = 900
    unmute_dwell_ms: int = 400
    max_mute_s: float = 240.0


@dataclass(frozen=True, slots=True)
class ControlConfig:
    backend: str = "rs232_sharp"
    verify_with_audio: bool = True
    options: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class Profile:
    """Resolved device profile: identity, control traits, fusion weights."""

    name: str
    make: str = "generic"
    model: str = "generic"
    year: int = 0
    control_backends: tuple[str, ...] = ()
    discrete_mute: bool = False
    state_readback: bool = False
    fusion_weights: dict[str, float] = field(default_factory=dict)
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class Config:
    capture: CaptureConfig
    detect: DetectConfig
    fusion: FusionConfig
    control: ControlConfig
    profile: Profile
    log_level: str = "info"


def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    merged = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def _load_toml(path: Path) -> dict[str, Any]:
    try:
        with path.open("rb") as fh:
            return tomllib.load(fh)
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError(f"{path}: invalid TOML: {exc}") from exc


def _profile_file(profiles_dir: Path, name: str) -> Path:
    for candidate in (profiles_dir / f"{name}.toml", profiles_dir / f"{name}.example.toml"):
        if candidate.is_file():
            return candidate
    raise ConfigError(f"profile '{name}' not found under {profiles_dir}")


def load_profile(profiles_dir: Path, name: str, _seen: frozenset[str] = frozenset()) -> Profile:
    """Load a device profile, resolving its ``inherits`` chain."""
    if name in _seen:
        raise ConfigError(f"profile inheritance cycle at '{name}'")
    data = _load_toml(_profile_file(profiles_dir, name))
    parent_name = data.pop("inherits", None)
    if parent_name is not None:
        parent = load_profile(profiles_dir, str(parent_name), _seen | {name})
        data = _deep_merge(parent.raw, data)

    identity = data.get("identity", {})
    control = data.get("control", {})
    weights_raw = data.get("fusion", {}).get("weights", {})
    weights = {str(k): float(v) for k, v in weights_raw.items()}
    for det in weights:
        if det not in KNOWN_DETECTORS:
            raise ConfigError(f"profile '{name}': unknown detector in fusion.weights: {det}")

    backends = tuple(str(b) for b in control.get("backends", []))
    for backend in backends:
        if backend not in KNOWN_CONTROL_BACKENDS:
            raise ConfigError(f"profile '{name}': unknown control backend: {backend}")

    return Profile(
        name=name,
        make=str(identity.get("make", "generic")),
        model=str(identity.get("model", "generic")),
        year=int(identity.get("year", 0)),
        control_backends=backends,
        discrete_mute=bool(control.get("discrete_mute", False)),
        state_readback=bool(control.get("state_readback", False)),
        fusion_weights=weights,
        raw=data,
    )


_CAPTURE_DEFAULTS = CaptureConfig()
_BLACK_DEFAULTS = BlackFrameConfig()
_SILENCE_DEFAULTS = SilenceConfig()
_LOUDNESS_DEFAULTS = LoudnessConfig()
_FUSION_DEFAULTS = FusionConfig()
_CONTROL_DEFAULTS = ControlConfig()


def load_config(path: Path, profiles_dir: Path | None = None) -> Config:
    """Load the main config file and resolve its device profile."""
    data = _load_toml(path)
    if profiles_dir is None:
        profiles_dir = path.parent / "profiles"

    cap = data.get("capture", {})
    capture = CaptureConfig(
        backend=str(cap.get("backend", _CAPTURE_DEFAULTS.backend)),
        device=str(cap.get("device", _CAPTURE_DEFAULTS.device)),
        width=int(cap.get("width", _CAPTURE_DEFAULTS.width)),
        height=int(cap.get("height", _CAPTURE_DEFAULTS.height)),
        fps=int(cap.get("fps", _CAPTURE_DEFAULTS.fps)),
        audio_device=str(cap.get("audio_device", _CAPTURE_DEFAULTS.audio_device)),
        audio_rate=int(cap.get("audio_rate", _CAPTURE_DEFAULTS.audio_rate)),
        audio_block_ms=int(cap.get("audio_block_ms", _CAPTURE_DEFAULTS.audio_block_ms)),
        path=str(cap.get("path", "")),
    )
    if capture.backend not in KNOWN_CAPTURE_BACKENDS:
        raise ConfigError(f"unknown capture backend: {capture.backend}")
    if capture.fps < 1 or capture.audio_rate < 8000 or capture.audio_block_ms < 10:
        raise ConfigError("capture rates out of range")

    det = data.get("detect", {})
    enabled = tuple(str(d) for d in det.get("enabled", PHASE1_DETECTORS))
    for name in enabled:
        if name not in KNOWN_DETECTORS:
            raise ConfigError(f"unknown detector enabled: {name}")
    bf = det.get("black_frame", {})
    sil = det.get("silence", {})
    loud = det.get("loudness", {})
    detect = DetectConfig(
        enabled=enabled,
        black_frame=BlackFrameConfig(
            luma_threshold=int(bf.get("luma_threshold", _BLACK_DEFAULTS.luma_threshold)),
            min_run_frames=int(bf.get("min_run_frames", _BLACK_DEFAULTS.min_run_frames)),
        ),
        silence=SilenceConfig(
            dbfs_threshold=float(sil.get("dbfs_threshold", _SILENCE_DEFAULTS.dbfs_threshold)),
            min_run_ms=int(sil.get("min_run_ms", _SILENCE_DEFAULTS.min_run_ms)),
        ),
        loudness=LoudnessConfig(
            window_s=float(loud.get("window_s", _LOUDNESS_DEFAULTS.window_s)),
            delta_lufs=float(loud.get("delta_lufs", _LOUDNESS_DEFAULTS.delta_lufs)),
            baseline_s=float(loud.get("baseline_s", _LOUDNESS_DEFAULTS.baseline_s)),
        ),
    )

    fus = data.get("fusion", {})
    fusion = FusionConfig(
        mute_confidence=float(fus.get("mute_confidence", _FUSION_DEFAULTS.mute_confidence)),
        unmute_confidence=float(fus.get("unmute_confidence", _FUSION_DEFAULTS.unmute_confidence)),
        mute_dwell_ms=int(fus.get("mute_dwell_ms", _FUSION_DEFAULTS.mute_dwell_ms)),
        unmute_dwell_ms=int(fus.get("unmute_dwell_ms", _FUSION_DEFAULTS.unmute_dwell_ms)),
        max_mute_s=float(fus.get("max_mute_s", _FUSION_DEFAULTS.max_mute_s)),
    )
    if not 0.0 < fusion.unmute_confidence < fusion.mute_confidence < 1.0:
        raise ConfigError(
            "fusion thresholds must satisfy 0 < unmute_confidence < mute_confidence < 1"
        )
    if fusion.max_mute_s <= 0:
        raise ConfigError("fusion.max_mute_s must be positive")

    ctl = data.get("control", {})
    backend = str(ctl.get("backend", _CONTROL_DEFAULTS.backend))
    if backend not in KNOWN_CONTROL_BACKENDS:
        raise ConfigError(f"unknown control backend: {backend}")
    control = ControlConfig(
        backend=backend,
        verify_with_audio=bool(ctl.get("verify_with_audio", True)),
        options=dict(ctl.get(backend, {})),
    )

    profile_name = str(data.get("device", {}).get("profile", "generic"))
    profile = load_profile(profiles_dir, profile_name)

    log_level = str(data.get("log", {}).get("level", "info"))
    return Config(
        capture=capture,
        detect=detect,
        fusion=fusion,
        control=control,
        profile=profile,
        log_level=log_level,
    )
