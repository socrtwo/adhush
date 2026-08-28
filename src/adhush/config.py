"""TOML config loading, schema validation, defaults, per-device profile resolution."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

PHASE1_DETECTORS = ("black_frame", "silence", "loudness")
PHASE2_DETECTORS = ("logo_absence", "scene_cut", "fingerprint")
IMPLEMENTED_DETECTORS = PHASE1_DETECTORS + PHASE2_DETECTORS
KNOWN_DETECTORS = IMPLEMENTED_DETECTORS + ("aspect_change", "caption_gap")
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
class RoiConfig:
    x: float = 0.84
    y: float = 0.80
    w: float = 0.14
    h: float = 0.14


@dataclass(frozen=True, slots=True)
class LogoAbsenceConfig:
    roi: RoiConfig = field(default_factory=RoiConfig)
    absence_frames: int = 45
    template: str = "data/logos/logo.npz"
    # Edge-correlation score below which the logo counts as absent.
    present_threshold: float = 0.4


@dataclass(frozen=True, slots=True)
class SceneCutConfig:
    # Mean-abs luma delta (downscaled) that counts as a shot change.
    diff_threshold: float = 12.0
    window_s: float = 10.0
    # Cut rate mapping to confidence: 0 at low_cpm, 1 at high_cpm.
    low_cpm: float = 15.0
    high_cpm: float = 40.0


@dataclass(frozen=True, slots=True)
class DetectConfig:
    enabled: tuple[str, ...] = IMPLEMENTED_DETECTORS
    black_frame: BlackFrameConfig = field(default_factory=BlackFrameConfig)
    silence: SilenceConfig = field(default_factory=SilenceConfig)
    loudness: LoudnessConfig = field(default_factory=LoudnessConfig)
    logo_absence: LogoAbsenceConfig = field(default_factory=LogoAbsenceConfig)
    scene_cut: SceneCutConfig = field(default_factory=SceneCutConfig)


@dataclass(frozen=True, slots=True)
class FusionConfig:
    mute_confidence: float = 0.72
    unmute_confidence: float = 0.45
    mute_dwell_ms: int = 900
    unmute_dwell_ms: int = 400
    max_mute_s: float = 240.0
    # Early-unmute dwell while inside a fingerprint-matched ad window: the
    # combined confidence must stay on the program side this long before the
    # learned-duration mute is abandoned.
    fp_unmute_dwell_ms: int = 3000


@dataclass(frozen=True, slots=True)
class FingerprintConfig:
    enabled: bool = True
    store: str = "data/fingerprints/ads.sqlite"
    hamming_threshold: int = 10
    audio_corroboration: bool = True
    learn: bool = True
    slot_snap_s: tuple[float, ...] = (15.0, 30.0, 45.0, 60.0)
    # Sampling and matching cadence.
    sample_interval_s: float = 0.5
    window_s: float = 6.0  # how much of an ad's start is fingerprinted
    confirm_hits: int = 3  # consecutive matching samples to promote
    # Frames flatter than this luma stddev are never hashed (black frames and
    # plain cards hash identically and would cross-match everything).
    min_frame_std: float = 6.0
    # Learned-segment sanity bounds.
    min_learn_s: float = 8.0
    max_learn_s: float = 120.0
    # Below this many observations the slot snap overrides the learned
    # duration; at or above it, the learned duration wins.
    snap_min_samples: int = 3
    # Minimum fraction of agreeing chroma bits for audio corroboration.
    audio_min_agreement: float = 0.7


@dataclass(frozen=True, slots=True)
class ControlConfig:
    backend: str = "rs232_sharp"
    verify_with_audio: bool = True
    # The selected backend's [control.<backend>] section.
    options: dict[str, Any] = field(default_factory=dict)
    # Every backend's [control.<backend>] section, so probing can resolve
    # options for paths other than the selected one.
    sections: dict[str, dict[str, Any]] = field(default_factory=dict)


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
    fingerprint: FingerprintConfig = field(default_factory=FingerprintConfig)
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
_LOGO_DEFAULTS = LogoAbsenceConfig()
_SCENE_DEFAULTS = SceneCutConfig()
_FUSION_DEFAULTS = FusionConfig()
_CONTROL_DEFAULTS = ControlConfig()
_FP_DEFAULTS = FingerprintConfig()
_ROI_DEFAULTS = RoiConfig()


def _parse_roi(raw: dict[str, Any] | None, fallback: RoiConfig) -> RoiConfig:
    if not raw:
        return fallback
    roi = RoiConfig(
        x=float(raw.get("x", fallback.x)),
        y=float(raw.get("y", fallback.y)),
        w=float(raw.get("w", fallback.w)),
        h=float(raw.get("h", fallback.h)),
    )
    for name, v in (("x", roi.x), ("y", roi.y), ("w", roi.w), ("h", roi.h)):
        if not 0.0 <= v <= 1.0:
            raise ConfigError(f"logo roi {name}={v} outside [0, 1]")
    if roi.x + roi.w > 1.0 or roi.y + roi.h > 1.0:
        raise ConfigError("logo roi extends past the frame")
    return roi


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

    profile_name = str(data.get("device", {}).get("profile", "generic"))
    profile = load_profile(profiles_dir, profile_name)
    # The device profile supplies the logo ROI default; [detect.logo_absence]
    # in the main config overrides it.
    profile_roi = _parse_roi(profile.raw.get("roi", {}).get("logo"), _ROI_DEFAULTS)

    det = data.get("detect", {})
    enabled = tuple(str(d) for d in det.get("enabled", IMPLEMENTED_DETECTORS))
    for name in enabled:
        if name not in KNOWN_DETECTORS:
            raise ConfigError(f"unknown detector enabled: {name}")
    bf = det.get("black_frame", {})
    sil = det.get("silence", {})
    loud = det.get("loudness", {})
    logo = det.get("logo_absence", {})
    scene = det.get("scene_cut", {})
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
        logo_absence=LogoAbsenceConfig(
            roi=_parse_roi(logo.get("roi"), profile_roi),
            absence_frames=int(logo.get("absence_frames", _LOGO_DEFAULTS.absence_frames)),
            template=str(logo.get("template", _LOGO_DEFAULTS.template)),
            present_threshold=float(
                logo.get("present_threshold", _LOGO_DEFAULTS.present_threshold)
            ),
        ),
        scene_cut=SceneCutConfig(
            diff_threshold=float(scene.get("diff_threshold", _SCENE_DEFAULTS.diff_threshold)),
            window_s=float(scene.get("window_s", _SCENE_DEFAULTS.window_s)),
            low_cpm=float(scene.get("low_cpm", _SCENE_DEFAULTS.low_cpm)),
            high_cpm=float(scene.get("high_cpm", _SCENE_DEFAULTS.high_cpm)),
        ),
    )
    if detect.scene_cut.low_cpm >= detect.scene_cut.high_cpm:
        raise ConfigError("scene_cut requires low_cpm < high_cpm")

    fus = data.get("fusion", {})
    fusion = FusionConfig(
        mute_confidence=float(fus.get("mute_confidence", _FUSION_DEFAULTS.mute_confidence)),
        unmute_confidence=float(fus.get("unmute_confidence", _FUSION_DEFAULTS.unmute_confidence)),
        mute_dwell_ms=int(fus.get("mute_dwell_ms", _FUSION_DEFAULTS.mute_dwell_ms)),
        unmute_dwell_ms=int(fus.get("unmute_dwell_ms", _FUSION_DEFAULTS.unmute_dwell_ms)),
        max_mute_s=float(fus.get("max_mute_s", _FUSION_DEFAULTS.max_mute_s)),
        fp_unmute_dwell_ms=int(
            fus.get("fp_unmute_dwell_ms", _FUSION_DEFAULTS.fp_unmute_dwell_ms)
        ),
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
        sections={b: dict(ctl[b]) for b in KNOWN_CONTROL_BACKENDS if b in ctl},
    )

    fp = data.get("fingerprint", {})
    fingerprint = FingerprintConfig(
        enabled=bool(fp.get("enabled", _FP_DEFAULTS.enabled)),
        store=str(fp.get("store", _FP_DEFAULTS.store)),
        hamming_threshold=int(fp.get("hamming_threshold", _FP_DEFAULTS.hamming_threshold)),
        audio_corroboration=bool(
            fp.get("audio_corroboration", _FP_DEFAULTS.audio_corroboration)
        ),
        learn=bool(fp.get("learn", _FP_DEFAULTS.learn)),
        slot_snap_s=tuple(float(s) for s in fp.get("slot_snap_s", _FP_DEFAULTS.slot_snap_s)),
        sample_interval_s=float(fp.get("sample_interval_s", _FP_DEFAULTS.sample_interval_s)),
        window_s=float(fp.get("window_s", _FP_DEFAULTS.window_s)),
        confirm_hits=int(fp.get("confirm_hits", _FP_DEFAULTS.confirm_hits)),
        min_frame_std=float(fp.get("min_frame_std", _FP_DEFAULTS.min_frame_std)),
        min_learn_s=float(fp.get("min_learn_s", _FP_DEFAULTS.min_learn_s)),
        max_learn_s=float(fp.get("max_learn_s", _FP_DEFAULTS.max_learn_s)),
        snap_min_samples=int(fp.get("snap_min_samples", _FP_DEFAULTS.snap_min_samples)),
        audio_min_agreement=float(
            fp.get("audio_min_agreement", _FP_DEFAULTS.audio_min_agreement)
        ),
    )
    if fingerprint.hamming_threshold < 0 or fingerprint.confirm_hits < 1:
        raise ConfigError("fingerprint thresholds out of range")

    log_level = str(data.get("log", {}).get("level", "info"))
    return Config(
        capture=capture,
        detect=detect,
        fusion=fusion,
        control=control,
        profile=profile,
        fingerprint=fingerprint,
        log_level=log_level,
    )
