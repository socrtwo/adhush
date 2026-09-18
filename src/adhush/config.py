"""TOML config loading, schema validation, defaults, per-device profile resolution."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from adhush.util.resources import bundled

PHASE1_DETECTORS = ("black_frame", "silence", "loudness")
PHASE2_DETECTORS = ("logo_absence", "scene_cut", "fingerprint")
PHASE3_DETECTORS = ("clock", "jingle", "aspect_change")
# ADR 0023/0024: every one of these is inert until it has something to say.
PHASE4_DETECTORS = ("rating_bug", "ad_units", "cutscene", "stereo_width", "watermark", "schedule", "scte35", "crowd")
# ADR 0027: the sound-effect stinger that opens a segment.
PHASE5_DETECTORS = ("stinger",)
IMPLEMENTED_DETECTORS = (
    PHASE1_DETECTORS + PHASE2_DETECTORS + PHASE3_DETECTORS + PHASE4_DETECTORS + PHASE5_DETECTORS
)
KNOWN_DETECTORS = IMPLEMENTED_DETECTORS + ("caption_gap",)
KNOWN_CAPTURE_BACKENDS = (
    "hdmi_uvc",
    "camera",
    "microphone",
    "line_in",
    "screen",
    "file_replay",
    "ts_stream",
)
KNOWN_CONTROL_BACKENDS = (
    "rs232_sharp",
    "ir_lirc",
    "ir_pigpio",
    "cec",
    "ir_blaster_net",
    "network_ip",
    "local_audio",
    "relay_hdmi",
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
    autocrop: bool = True  # camera: crop to the detected screen rectangle
    # ts_stream (ADR 0024): an MPEG transport stream over HTTP or UDP — an
    # HDHomeRun's http://<ip>:5004/auto/v<channel>, a DVB dongle's udp://…
    url: str = ""
    # Measure stereo width before the mono downmix on stereo backends (ADR 0023).
    stereo: bool = True


@dataclass(frozen=True, slots=True)
class BlackFrameConfig:
    luma_threshold: int = 16
    min_run_frames: int = 3
    # A frame whose downscaled luma spreads no more than this is a uniform
    # separator (a white flash, a colour card) even when it is not black
    # (ADR 0023); 0 turns it off.
    uniform_spread: float = 6.0


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
    # Elevated loudness lasting longer than any ad pod means the baseline is
    # wrong (it was taken during a quiet passage); after this long it may follow.
    max_elevated_s: float = 180.0
    # Crest factor (ADR 0021): commercials are compressed harder than the
    # programme, so their peak-to-RMS ratio sits several dB lower even when
    # they are not louder. A drop of this many dB below the programme's crest
    # is a full crest vote; 0 turns the cue off.
    crest_drop_db: float = 4.0


@dataclass(frozen=True, slots=True)
class AspectChangeConfig:
    """Letterbox/pillarbox transitions (ADR 0021), HDMI and screen paths only."""

    bar_luma: float = 24.0  # a row or column this dark on average is a bar…
    bar_spread: float = 8.0  # …if it is also this flat (a dark scene is not a bar)
    frame_aspect: float = 16.0 / 9.0  # the shape of the whole captured frame
    sample_interval_s: float = 0.25
    confirm_s: float = 1.0  # a new shape must hold this long before it counts
    baseline_s: float = 600.0  # the programme's shape is the mode over this long
    min_baseline_s: float = 30.0
    max_change_s: float = 360.0  # changed longer than any break: the programme changed shape


@dataclass(frozen=True, slots=True)
class RatingBugConfig:
    """The parental-rating box as programme evidence (ADR 0023)."""

    roi: RoiConfig = field(default_factory=lambda: RoiConfig(x=0.0, y=0.02, w=0.22, h=0.16))
    bright: int = 190  # luma at or above which a pixel belongs to the box
    min_fill: float = 0.45  # box pixels that are bright (the text is dark)
    min_aspect: float = 1.2  # wider than tall…
    max_aspect: float = 4.0  # …but not a banner
    min_share: float = 0.04  # box area as a share of the ROI…
    max_share: float = 0.6  # …and not the whole corner lit up
    appear_after_s: float = 5.0  # the ROI must have been box-free this long: a fixed bright corner is scenery
    min_present_s: float = 1.0  # …and the box must stay this long
    hold_s: float = 20.0  # programme evidence lasts this long after the box
    sample_interval_s: float = 0.25


@dataclass(frozen=True, slots=True)
class AdUnitsConfig:
    """Ad-unit length quantisation (ADR 0023)."""

    tolerance_s: float = 1.2  # a gap within this of 15/30/45/60/90/120 s fits
    hold_s: float = 35.0  # the fit holds this long after the last separator
    merge_s: float = 2.0  # two cues this close are one separator
    black_luma: float = 20.0
    min_black_frames: int = 2
    silence_dbfs: float = -50.0
    min_silence_s: float = 0.25


@dataclass(frozen=True, slots=True)
class CutsceneConfig:
    """Intro/outro template matching (ADR 0023)."""

    directory: str = "data/cutscenes"
    max_hamming: int = 8  # phash bits
    min_correlation: float = 0.9  # thumbnail correlation
    hold_s: float = 10.0  # an "in" template ducks for this long
    close_hold_s: float = 10.0  # an "out" template is programme evidence this long
    sample_interval_s: float = 0.2


@dataclass(frozen=True, slots=True)
class StereoWidthConfig:
    """Stereo-width switch (ADR 0023)."""

    window_s: float = 2.0
    baseline_s: float = 120.0
    min_delta: float = 0.15  # side/mid ratio change that counts as a switch
    confirm_s: float = 1.5
    max_switch_s: float = 360.0  # switched longer than any break: the programme changed mix


@dataclass(frozen=True, slots=True)
class WatermarkConfig:
    """ATSC A/335 / DVB-TA video watermark presence (ADR 0023). Experimental."""

    lines: int = 2
    symbol_px: float = 8.0  # symbol width at 1920 px; scaled to the frame
    run_in: int = 0xEB52
    run_in_bits: int = 16
    min_contrast: float = 40.0  # luma spread between the two levels
    min_alignment: float = 0.7  # share of luma steps on symbol boundaries
    sample_interval_s: float = 0.5
    confirm_s: float = 1.5
    baseline_s: float = 600.0
    min_baseline_s: float = 30.0
    max_absent_s: float = 360.0


@dataclass(frozen=True, slots=True)
class ScheduleConfig:
    """EPG programme boundaries from an XMLTV file (ADR 0024)."""

    file: str = ""  # XMLTV; empty = off
    channel: str = ""  # the XMLTV channel id (or display name) being watched
    start_grace_s: float = 90.0  # a break is unlikely this long after a programme starts
    ad_free: tuple[str, ...] = ()  # channel ids or display names that never carry ads
    reload_s: float = 3600.0


@dataclass(frozen=True, slots=True)
class Scte35Config:
    """SCTE-35 cues from a transport stream (ADR 0024)."""

    max_break_s: float = 360.0  # an out-of-network with no in-network ends here
    in_hold_s: float = 10.0  # an in-network cue is programme evidence this long


@dataclass(frozen=True, slots=True)
class CrowdConfig:
    """A shared feed of break times on the same channel (ADR 0024). Opt-in."""

    url: str = ""  # an adhush crowd server; empty = off
    channel: str = ""  # the channel's name; only a hash prefix ever leaves the device
    report: bool = True  # send this device's own confirmed breaks
    min_reports: int = 2  # other devices that must agree before it votes
    window_s: float = 45.0  # a report is fresh this long
    poll_s: float = 5.0
    salt: str = "adhush-crowd-v1"


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
    # How far the ROI may sit from where calibration put it, in pixels of a
    # 320-wide screen: the box is slid over this window and the best match
    # counts, so a camera box found a few pixels off is not "logo gone".
    # 0 = the fixed box only (HDMI capture is pixel-exact and needs none).
    search_px: int = 0
    # Only after the logo has been *seen* can it be missed: absence votes wait
    # for one clear sighting, and "Not an ad" demands a fresh one.
    require_sighting: bool = False
    # No frame for this long (the camera dropped a partial screen) = inert.
    stale_s: float = 2.0


@dataclass(frozen=True, slots=True)
class SceneCutConfig:
    # Mean-abs luma delta (downscaled) that counts as a shot change.
    diff_threshold: float = 12.0
    window_s: float = 10.0
    # Cut rate mapping to confidence: 0 at low_cpm, 1 at high_cpm.
    low_cpm: float = 15.0
    high_cpm: float = 40.0


@dataclass(frozen=True, slots=True)
class ClockConfig:
    """The break clock (ADR 0017): a learned minute-of-hour prior."""

    file: str = "data/clock.tsv"
    min_hours: int = 3  # a minute votes once watched in this many distinct hours
    full_fraction: float = 0.6  # this fraction of watched hours being a break = certainty
    min_break_s: float = 15.0
    max_break_s: float = 300.0


@dataclass(frozen=True, slots=True)
class JingleConfig:
    """Break jingles (ADR 0020): the channel's own sting into and out of every break."""

    file: str = "data/jingles.tsv"
    sample_interval_s: float = 0.5
    jingle_s: float = 3.0  # AdVent: three seconds is enough to recognise a sting
    candidate_s: float = 6.0  # a candidate keeps a wider window than the sting
    min_agreement: float = 0.72  # aligned chroma-bit agreement; 0.5 is chance
    promote_hits: int = 3  # distinct breaks a candidate must open before it votes
    hold_s: float = 20.0  # an opener's vote lasts this long
    close_hold_s: float = 10.0  # a closer counts as program evidence this long
    history_s: float = 400.0
    max_candidates: int = 80
    # ADR 0027, family matching: a sting is also recognised transposed by up to
    # this many semitones either way and stretched by up to this fraction ...
    pitch_shifts: int = 2
    tempo_tolerance: float = 0.1
    # ... but a transposed or stretched match must clear a higher bar.
    family_penalty: float = 0.05
    # ADR 0027, time of day: a sting heard at an hour it has opened breaks in
    # before is trusted sooner (one hit earlier) and matched a little more loosely.
    hour_bonus: float = 0.03


@dataclass(frozen=True, slots=True)
class StingerConfig:
    """Segment stingers (ADR 0027): the short whoosh / hit / swell a channel drops
    on the cut into a segment or a break, followed by a change of level."""

    block_s: float = 0.1
    min_flatness: float = 0.25  # a burst is noisy, not tonal; 0 = pure tone, 1 = white noise
    burst_above_db: float = 6.0  # burst level over the two-second pre-burst median
    max_burst_s: float = 1.5
    level_step_db: float = 3.0  # post-burst median must move this much from pre-burst ...
    loud_burst_db: float = 10.0  # ... unless the burst itself was this far above
    pre_s: float = 2.0
    post_s: float = 1.0
    hold_s: float = 2.5  # the vote decays to nothing over this long
    duck_guard_s: float = 1.5  # ignore audio this long after our own volume change


@dataclass(frozen=True, slots=True)
class DetectConfig:
    enabled: tuple[str, ...] = IMPLEMENTED_DETECTORS
    black_frame: BlackFrameConfig = field(default_factory=BlackFrameConfig)
    silence: SilenceConfig = field(default_factory=SilenceConfig)
    loudness: LoudnessConfig = field(default_factory=LoudnessConfig)
    logo_absence: LogoAbsenceConfig = field(default_factory=LogoAbsenceConfig)
    scene_cut: SceneCutConfig = field(default_factory=SceneCutConfig)
    clock: ClockConfig = field(default_factory=ClockConfig)
    jingle: JingleConfig = field(default_factory=JingleConfig)
    aspect_change: AspectChangeConfig = field(default_factory=AspectChangeConfig)
    rating_bug: RatingBugConfig = field(default_factory=RatingBugConfig)
    ad_units: AdUnitsConfig = field(default_factory=AdUnitsConfig)
    cutscene: CutsceneConfig = field(default_factory=CutsceneConfig)
    stereo_width: StereoWidthConfig = field(default_factory=StereoWidthConfig)
    watermark: WatermarkConfig = field(default_factory=WatermarkConfig)
    schedule: ScheduleConfig = field(default_factory=ScheduleConfig)
    scte35: Scte35Config = field(default_factory=Scte35Config)
    crowd: CrowdConfig = field(default_factory=CrowdConfig)
    stinger: StingerConfig = field(default_factory=StingerConfig)


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
    # After "Not an ad", no automatic mute for this long: the user's word
    # outranks the detectors for a while.
    not_ad_quiet_s: float = 60.0


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
class IpcConfig:
    enabled: bool = False
    host: str = "127.0.0.1"  # keep it loopback unless the LAN is trusted
    port: int = 8675
    # Optional shared secret; when set, requests need Authorization: Bearer <token>.
    token: str = ""
    # Directory holding the web front end the server serves at "/". Relative
    # paths resolve against the working directory, then a source checkout.
    web_root: str = "platforms/web"


@dataclass(frozen=True, slots=True)
class UiConfig:
    # Show the always-on-top mini window next to `adhush run` when a display
    # exists. It is a thin IPC client, so it needs [ipc] enabled.
    overlay: bool = True


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
    ipc: IpcConfig = field(default_factory=IpcConfig)
    ui: UiConfig = field(default_factory=UiConfig)
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
_CLOCK_DEFAULTS = ClockConfig()
_JINGLE_DEFAULTS = JingleConfig()
_ASPECT_DEFAULTS = AspectChangeConfig()
_RATING_DEFAULTS = RatingBugConfig()
_UNITS_DEFAULTS = AdUnitsConfig()
_CUTSCENE_DEFAULTS = CutsceneConfig()
_WIDTH_DEFAULTS = StereoWidthConfig()
_WATERMARK_DEFAULTS = WatermarkConfig()
_SCHEDULE_DEFAULTS = ScheduleConfig()
_SCTE_DEFAULTS = Scte35Config()
_CROWD_DEFAULTS = CrowdConfig()
_STINGER_DEFAULTS = StingerConfig()
_FUSION_DEFAULTS = FusionConfig()
_CONTROL_DEFAULTS = ControlConfig()
_FP_DEFAULTS = FingerprintConfig()
_IPC_DEFAULTS = IpcConfig()
_UI_DEFAULTS = UiConfig()
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
        if not profiles_dir.is_dir():
            profiles_dir = bundled("config/profiles")  # the set shipped with AdHush

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
        autocrop=bool(cap.get("autocrop", _CAPTURE_DEFAULTS.autocrop)),
        url=str(cap.get("url", _CAPTURE_DEFAULTS.url)),
        stereo=bool(cap.get("stereo", _CAPTURE_DEFAULTS.stereo)),
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
    clk = det.get("clock", {})
    jng = det.get("jingle", {})
    asp = det.get("aspect_change", {})
    rat = det.get("rating_bug", {})
    unt = det.get("ad_units", {})
    cut = det.get("cutscene", {})
    wid = det.get("stereo_width", {})
    wmk = det.get("watermark", {})
    sch = det.get("schedule", {})
    sct = det.get("scte35", {})
    crd = det.get("crowd", {})
    stg = det.get("stinger", {})
    detect = DetectConfig(
        enabled=enabled,
        black_frame=BlackFrameConfig(
            luma_threshold=int(bf.get("luma_threshold", _BLACK_DEFAULTS.luma_threshold)),
            min_run_frames=int(bf.get("min_run_frames", _BLACK_DEFAULTS.min_run_frames)),
            uniform_spread=float(bf.get("uniform_spread", _BLACK_DEFAULTS.uniform_spread)),
        ),
        silence=SilenceConfig(
            dbfs_threshold=float(sil.get("dbfs_threshold", _SILENCE_DEFAULTS.dbfs_threshold)),
            min_run_ms=int(sil.get("min_run_ms", _SILENCE_DEFAULTS.min_run_ms)),
        ),
        loudness=LoudnessConfig(
            window_s=float(loud.get("window_s", _LOUDNESS_DEFAULTS.window_s)),
            delta_lufs=float(loud.get("delta_lufs", _LOUDNESS_DEFAULTS.delta_lufs)),
            baseline_s=float(loud.get("baseline_s", _LOUDNESS_DEFAULTS.baseline_s)),
            max_elevated_s=float(loud.get("max_elevated_s", _LOUDNESS_DEFAULTS.max_elevated_s)),
            crest_drop_db=float(loud.get("crest_drop_db", _LOUDNESS_DEFAULTS.crest_drop_db)),
        ),
        logo_absence=LogoAbsenceConfig(
            roi=_parse_roi(logo.get("roi"), profile_roi),
            absence_frames=int(logo.get("absence_frames", _LOGO_DEFAULTS.absence_frames)),
            template=str(logo.get("template", _LOGO_DEFAULTS.template)),
            present_threshold=float(
                logo.get("present_threshold", _LOGO_DEFAULTS.present_threshold)
            ),
            search_px=int(logo.get("search_px", _LOGO_DEFAULTS.search_px)),
            require_sighting=bool(
                logo.get("require_sighting", _LOGO_DEFAULTS.require_sighting)
            ),
            stale_s=float(logo.get("stale_s", _LOGO_DEFAULTS.stale_s)),
        ),
        scene_cut=SceneCutConfig(
            diff_threshold=float(scene.get("diff_threshold", _SCENE_DEFAULTS.diff_threshold)),
            window_s=float(scene.get("window_s", _SCENE_DEFAULTS.window_s)),
            low_cpm=float(scene.get("low_cpm", _SCENE_DEFAULTS.low_cpm)),
            high_cpm=float(scene.get("high_cpm", _SCENE_DEFAULTS.high_cpm)),
        ),
        clock=ClockConfig(
            file=str(clk.get("file", _CLOCK_DEFAULTS.file)),
            min_hours=int(clk.get("min_hours", _CLOCK_DEFAULTS.min_hours)),
            full_fraction=float(clk.get("full_fraction", _CLOCK_DEFAULTS.full_fraction)),
            min_break_s=float(clk.get("min_break_s", _CLOCK_DEFAULTS.min_break_s)),
            max_break_s=float(clk.get("max_break_s", _CLOCK_DEFAULTS.max_break_s)),
        ),
        jingle=JingleConfig(
            file=str(jng.get("file", _JINGLE_DEFAULTS.file)),
            sample_interval_s=float(jng.get("sample_interval_s", _JINGLE_DEFAULTS.sample_interval_s)),
            jingle_s=float(jng.get("jingle_s", _JINGLE_DEFAULTS.jingle_s)),
            candidate_s=float(jng.get("candidate_s", _JINGLE_DEFAULTS.candidate_s)),
            min_agreement=float(jng.get("min_agreement", _JINGLE_DEFAULTS.min_agreement)),
            promote_hits=int(jng.get("promote_hits", _JINGLE_DEFAULTS.promote_hits)),
            hold_s=float(jng.get("hold_s", _JINGLE_DEFAULTS.hold_s)),
            close_hold_s=float(jng.get("close_hold_s", _JINGLE_DEFAULTS.close_hold_s)),
            history_s=float(jng.get("history_s", _JINGLE_DEFAULTS.history_s)),
            max_candidates=int(jng.get("max_candidates", _JINGLE_DEFAULTS.max_candidates)),
            pitch_shifts=int(jng.get("pitch_shifts", _JINGLE_DEFAULTS.pitch_shifts)),
            tempo_tolerance=float(jng.get("tempo_tolerance", _JINGLE_DEFAULTS.tempo_tolerance)),
            family_penalty=float(jng.get("family_penalty", _JINGLE_DEFAULTS.family_penalty)),
            hour_bonus=float(jng.get("hour_bonus", _JINGLE_DEFAULTS.hour_bonus)),
        ),
        aspect_change=AspectChangeConfig(
            bar_luma=float(asp.get("bar_luma", _ASPECT_DEFAULTS.bar_luma)),
            bar_spread=float(asp.get("bar_spread", _ASPECT_DEFAULTS.bar_spread)),
            frame_aspect=float(asp.get("frame_aspect", _ASPECT_DEFAULTS.frame_aspect)),
            sample_interval_s=float(asp.get("sample_interval_s", _ASPECT_DEFAULTS.sample_interval_s)),
            confirm_s=float(asp.get("confirm_s", _ASPECT_DEFAULTS.confirm_s)),
            baseline_s=float(asp.get("baseline_s", _ASPECT_DEFAULTS.baseline_s)),
            min_baseline_s=float(asp.get("min_baseline_s", _ASPECT_DEFAULTS.min_baseline_s)),
            max_change_s=float(asp.get("max_change_s", _ASPECT_DEFAULTS.max_change_s)),
        ),
        rating_bug=RatingBugConfig(
            roi=_parse_roi(rat.get("roi"), _RATING_DEFAULTS.roi),
            bright=int(rat.get("bright", _RATING_DEFAULTS.bright)),
            min_fill=float(rat.get("min_fill", _RATING_DEFAULTS.min_fill)),
            min_aspect=float(rat.get("min_aspect", _RATING_DEFAULTS.min_aspect)),
            max_aspect=float(rat.get("max_aspect", _RATING_DEFAULTS.max_aspect)),
            min_share=float(rat.get("min_share", _RATING_DEFAULTS.min_share)),
            max_share=float(rat.get("max_share", _RATING_DEFAULTS.max_share)),
            appear_after_s=float(rat.get("appear_after_s", _RATING_DEFAULTS.appear_after_s)),
            min_present_s=float(rat.get("min_present_s", _RATING_DEFAULTS.min_present_s)),
            hold_s=float(rat.get("hold_s", _RATING_DEFAULTS.hold_s)),
            sample_interval_s=float(rat.get("sample_interval_s", _RATING_DEFAULTS.sample_interval_s)),
        ),
        ad_units=AdUnitsConfig(
            tolerance_s=float(unt.get("tolerance_s", _UNITS_DEFAULTS.tolerance_s)),
            hold_s=float(unt.get("hold_s", _UNITS_DEFAULTS.hold_s)),
            merge_s=float(unt.get("merge_s", _UNITS_DEFAULTS.merge_s)),
            black_luma=float(unt.get("black_luma", _UNITS_DEFAULTS.black_luma)),
            min_black_frames=int(unt.get("min_black_frames", _UNITS_DEFAULTS.min_black_frames)),
            silence_dbfs=float(unt.get("silence_dbfs", _UNITS_DEFAULTS.silence_dbfs)),
            min_silence_s=float(unt.get("min_silence_s", _UNITS_DEFAULTS.min_silence_s)),
        ),
        cutscene=CutsceneConfig(
            directory=str(cut.get("directory", _CUTSCENE_DEFAULTS.directory)),
            max_hamming=int(cut.get("max_hamming", _CUTSCENE_DEFAULTS.max_hamming)),
            min_correlation=float(cut.get("min_correlation", _CUTSCENE_DEFAULTS.min_correlation)),
            hold_s=float(cut.get("hold_s", _CUTSCENE_DEFAULTS.hold_s)),
            close_hold_s=float(cut.get("close_hold_s", _CUTSCENE_DEFAULTS.close_hold_s)),
            sample_interval_s=float(cut.get("sample_interval_s", _CUTSCENE_DEFAULTS.sample_interval_s)),
        ),
        stereo_width=StereoWidthConfig(
            window_s=float(wid.get("window_s", _WIDTH_DEFAULTS.window_s)),
            baseline_s=float(wid.get("baseline_s", _WIDTH_DEFAULTS.baseline_s)),
            min_delta=float(wid.get("min_delta", _WIDTH_DEFAULTS.min_delta)),
            confirm_s=float(wid.get("confirm_s", _WIDTH_DEFAULTS.confirm_s)),
            max_switch_s=float(wid.get("max_switch_s", _WIDTH_DEFAULTS.max_switch_s)),
        ),
        watermark=WatermarkConfig(
            lines=int(wmk.get("lines", _WATERMARK_DEFAULTS.lines)),
            symbol_px=float(wmk.get("symbol_px", _WATERMARK_DEFAULTS.symbol_px)),
            run_in=int(wmk.get("run_in", _WATERMARK_DEFAULTS.run_in)),
            run_in_bits=int(wmk.get("run_in_bits", _WATERMARK_DEFAULTS.run_in_bits)),
            min_contrast=float(wmk.get("min_contrast", _WATERMARK_DEFAULTS.min_contrast)),
            min_alignment=float(wmk.get("min_alignment", _WATERMARK_DEFAULTS.min_alignment)),
            sample_interval_s=float(wmk.get("sample_interval_s", _WATERMARK_DEFAULTS.sample_interval_s)),
            confirm_s=float(wmk.get("confirm_s", _WATERMARK_DEFAULTS.confirm_s)),
            baseline_s=float(wmk.get("baseline_s", _WATERMARK_DEFAULTS.baseline_s)),
            min_baseline_s=float(wmk.get("min_baseline_s", _WATERMARK_DEFAULTS.min_baseline_s)),
            max_absent_s=float(wmk.get("max_absent_s", _WATERMARK_DEFAULTS.max_absent_s)),
        ),
        schedule=ScheduleConfig(
            file=str(sch.get("file", _SCHEDULE_DEFAULTS.file)),
            channel=str(sch.get("channel", _SCHEDULE_DEFAULTS.channel)),
            start_grace_s=float(sch.get("start_grace_s", _SCHEDULE_DEFAULTS.start_grace_s)),
            ad_free=tuple(str(c) for c in sch.get("ad_free", list(_SCHEDULE_DEFAULTS.ad_free))),
            reload_s=float(sch.get("reload_s", _SCHEDULE_DEFAULTS.reload_s)),
        ),
        scte35=Scte35Config(
            max_break_s=float(sct.get("max_break_s", _SCTE_DEFAULTS.max_break_s)),
            in_hold_s=float(sct.get("in_hold_s", _SCTE_DEFAULTS.in_hold_s)),
        ),
        crowd=CrowdConfig(
            url=str(crd.get("url", _CROWD_DEFAULTS.url)),
            channel=str(crd.get("channel", _CROWD_DEFAULTS.channel)),
            report=bool(crd.get("report", _CROWD_DEFAULTS.report)),
            min_reports=int(crd.get("min_reports", _CROWD_DEFAULTS.min_reports)),
            window_s=float(crd.get("window_s", _CROWD_DEFAULTS.window_s)),
            poll_s=float(crd.get("poll_s", _CROWD_DEFAULTS.poll_s)),
            salt=str(crd.get("salt", _CROWD_DEFAULTS.salt)),
        ),
        stinger=StingerConfig(
            block_s=float(stg.get("block_s", _STINGER_DEFAULTS.block_s)),
            min_flatness=float(stg.get("min_flatness", _STINGER_DEFAULTS.min_flatness)),
            burst_above_db=float(stg.get("burst_above_db", _STINGER_DEFAULTS.burst_above_db)),
            max_burst_s=float(stg.get("max_burst_s", _STINGER_DEFAULTS.max_burst_s)),
            level_step_db=float(stg.get("level_step_db", _STINGER_DEFAULTS.level_step_db)),
            loud_burst_db=float(stg.get("loud_burst_db", _STINGER_DEFAULTS.loud_burst_db)),
            pre_s=float(stg.get("pre_s", _STINGER_DEFAULTS.pre_s)),
            post_s=float(stg.get("post_s", _STINGER_DEFAULTS.post_s)),
            hold_s=float(stg.get("hold_s", _STINGER_DEFAULTS.hold_s)),
            duck_guard_s=float(stg.get("duck_guard_s", _STINGER_DEFAULTS.duck_guard_s)),
        ),
    )
    if not 0.0 < detect.clock.full_fraction <= 1.0 or detect.clock.min_hours < 1:
        raise ConfigError("clock requires 0 < full_fraction <= 1 and min_hours >= 1")
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
        not_ad_quiet_s=float(fus.get("not_ad_quiet_s", _FUSION_DEFAULTS.not_ad_quiet_s)),
    )
    if not 0.0 < fusion.unmute_confidence < fusion.mute_confidence < 1.0:
        raise ConfigError(
            "fusion thresholds must satisfy 0 < unmute_confidence < mute_confidence < 1"
        )
    if fusion.max_mute_s <= 0:
        raise ConfigError("fusion.max_mute_s must be positive")
    if fusion.not_ad_quiet_s < 0 or detect.logo_absence.search_px < 0:
        raise ConfigError("fusion.not_ad_quiet_s and logo_absence.search_px must be >= 0")

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

    ipc_raw = data.get("ipc", {})
    ipc = IpcConfig(
        enabled=bool(ipc_raw.get("enabled", False)),
        host=str(ipc_raw.get("host", _IPC_DEFAULTS.host)),
        port=int(ipc_raw.get("port", _IPC_DEFAULTS.port)),
        token=str(ipc_raw.get("token", "")),
        web_root=str(ipc_raw.get("web_root", _IPC_DEFAULTS.web_root)),
    )
    if not 0 < ipc.port < 65536:
        raise ConfigError("ipc.port out of range")

    ui_raw = data.get("ui", {})
    ui = UiConfig(overlay=bool(ui_raw.get("overlay", _UI_DEFAULTS.overlay)))

    log_level = str(data.get("log", {}).get("level", "info"))
    return Config(
        capture=capture,
        detect=detect,
        fusion=fusion,
        control=control,
        profile=profile,
        fingerprint=fingerprint,
        ipc=ipc,
        ui=ui,
        log_level=log_level,
    )
