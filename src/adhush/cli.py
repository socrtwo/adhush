"""Argument parsing, subcommands: run, calibrate, learn, replay, probe, doctor, ir-test."""

from __future__ import annotations

import argparse
import heapq
import json
import logging
import shutil
import signal
import sys
import threading
import time
from collections.abc import Iterator
from pathlib import Path

from adhush import __version__
from adhush.capture import build_capture
from adhush.capture.file_replay import FileReplaySource
from adhush.config import Config, ConfigError, DetectConfig, FusionConfig, load_config
from adhush.control import NullController, build_controller, resolve_options
from adhush.control.base import ControlError
from adhush.control.ir_lirc import IrLircController
from adhush.control.probe import probe_backends
from adhush.detect import build_detectors
from adhush.detect.fingerprint import FingerprintDetector
from adhush.detect.fusion import Fusion
from adhush.detect.logo_absence import build_template, save_template
from adhush.engine import Pipeline, evaluate_onsets, run_live, run_offline
from adhush.events import AdSegment, AudioEvent, FrameEvent
from adhush.fingerprint import open_fingerprints
from adhush.state import AdStateMachine
from adhush.util.imageops import extract_roi

_DEFAULT_CONFIG = Path("config/adhush.toml")


def _load(path: Path) -> Config:
    try:
        return load_config(path)
    except (ConfigError, OSError) as exc:
        raise SystemExit(f"adhush: config error: {exc}") from exc


def _build_pipeline(
    config: Config, controller: NullController | None, *, video: bool, audio: bool
) -> Pipeline:
    fp_active = (
        video and config.fingerprint.enabled and "fingerprint" in config.detect.enabled
    )
    matcher = learner = None
    fingerprint = None
    if fp_active:
        _store, matcher, learner = open_fingerprints(config.fingerprint)
        fingerprint = (config.fingerprint, matcher)
    detectors = build_detectors(
        config.detect, video=video, audio=audio, fingerprint=fingerprint
    )
    if not detectors:
        raise SystemExit("adhush: no enabled detector matches the available modalities")
    fusion = Fusion(config.fusion, config.profile.fusion_weights, [d.name for d in detectors])
    machine = AdStateMachine(config.fusion)
    ctl = controller if controller is not None else build_controller(
        config.control, config.profile
    )
    return Pipeline(
        detectors,
        fusion,
        machine,
        ctl,
        learner=learner if config.fingerprint.learn else None,
        matcher=matcher,
    )


def _cmd_run(args: argparse.Namespace) -> int:
    config = _load(args.config)
    logging.basicConfig(level=config.log_level.upper())
    source = build_capture(config.capture)
    stop = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    with source:
        caps = source.caps()
        pipeline = _build_pipeline(config, None, video=caps.video, audio=caps.audio)
        print(f"adhush {__version__}: running on {config.capture.backend} "
              f"(video={caps.video} audio={caps.audio}), control={config.control.backend}")
        run_live(source, pipeline, stop)
    return 0


def _cmd_replay(args: argparse.Namespace) -> int:
    config = _load(args.config) if args.config else None

    source = FileReplaySource(args.input)
    controller = NullController()
    with source:
        caps = source.caps()
        if config is not None:
            # Full configured pipeline, fingerprint store included.
            pipeline = _build_pipeline(config, controller, video=caps.video, audio=caps.audio)
        else:
            detectors = build_detectors(DetectConfig(), video=caps.video, audio=caps.audio)
            if not detectors:
                raise SystemExit("adhush: no detector matches the fixture's modalities")
            fusion_cfg = FusionConfig()
            fusion = Fusion(fusion_cfg, {}, [d.name for d in detectors])
            pipeline = Pipeline(detectors, fusion, AdStateMachine(fusion_cfg), controller)
        transitions = run_offline(source, pipeline)

    for t in transitions:
        top = t.reasons[0] if t.reasons else ""
        print(f"{t.ts:8.2f}s  {t.action.value:<6}  conf={t.confidence:.2f}  {top}")
    if not transitions:
        print("no mute/unmute transitions")

    if args.labels:
        segments = _read_labels(args.labels)
        mute, unmute = evaluate_onsets(
            transitions,
            segments,
            mute_tolerance_s=args.mute_tolerance,
            unmute_tolerance_s=args.unmute_tolerance,
        )
        print(f"mute-onset:   precision={mute.precision:.2f} recall={mute.recall:.2f} "
              f"({mute.matched}/{mute.predicted} predicted, {mute.labeled} labeled)")
        print(f"unmute-onset: precision={unmute.precision:.2f} recall={unmute.recall:.2f} "
              f"({unmute.matched}/{unmute.predicted} predicted, {unmute.labeled} labeled)")
    return 0


def _read_labels(path: Path) -> list[AdSegment]:
    try:
        raw = json.loads(path.read_text())
        return [
            AdSegment(start_ts=float(s["start_ts"]), duration_s=float(s["duration_s"]), source="label")
            for s in raw
        ]
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise SystemExit(f"adhush: bad labels file {path}: {exc}") from exc


def _cmd_doctor(args: argparse.Namespace) -> int:
    failures = 0

    def check(label: str, ok: bool, hint: str = "") -> None:
        nonlocal failures
        print(f"  [{'ok' if ok else 'FAIL'}] {label}" + (f" — {hint}" if not ok and hint else ""))
        if not ok:
            failures += 1

    print(f"adhush {__version__} doctor")
    check("python >= 3.11", sys.version_info >= (3, 11))
    try:
        import numpy  # noqa: F401
        check("numpy importable", True)
    except ImportError:
        check("numpy importable", False, "pip install numpy")
    check("ffmpeg on PATH", shutil.which("ffmpeg") is not None, "needed for hdmi_uvc and media replay")
    check("ffprobe on PATH", shutil.which("ffprobe") is not None, "needed for media replay")
    check("irsend on PATH", shutil.which("irsend") is not None, "needed only for ir_lirc control")
    try:
        import serial  # type: ignore[import-untyped]  # noqa: F401
        check("pyserial importable", True)
    except ImportError:
        check("pyserial importable", False, "needed only for rs232_sharp control")

    if args.config.is_file():
        try:
            config = load_config(args.config)
            check(f"config {args.config} parses", True)
            check(
                f"profile '{config.profile.name}' resolves "
                f"({config.profile.make} {config.profile.model})",
                True,
            )
            if config.capture.backend == "hdmi_uvc":
                check(
                    f"capture device {config.capture.device} present",
                    Path(config.capture.device).exists(),
                )
            if config.control.backend == "rs232_sharp":
                port = str(config.control.options.get("port", "/dev/ttyUSB0"))
                check(f"serial port {port} present", Path(port).exists())
        except ConfigError as exc:
            check(f"config {args.config} parses", False, str(exc))
    else:
        print(f"  [--] no config at {args.config} (copy config/adhush.example.toml)")

    print("all checks passed" if failures == 0 else f"{failures} check(s) failed")
    return 0 if failures == 0 else 1


def _cmd_ir_test(args: argparse.Namespace) -> int:
    config = _load(args.config)
    if config.control.backend != "ir_lirc":
        raise SystemExit("adhush: ir-test requires control.backend = 'ir_lirc' in config")
    options = resolve_options(config.control, config.profile, "ir_lirc")
    try:
        controller = IrLircController(options)
        print(f"sending mute via remote '{options.get('remote')}'...")
        controller.mute()
        time.sleep(args.gap)
        print("sending unmute...")
        controller.unmute()
    except ControlError as exc:
        raise SystemExit(f"adhush: {exc}") from exc
    print("done — confirm the set muted and unmuted")
    return 0


def _cmd_probe(args: argparse.Namespace) -> int:
    """Report which control backends can plausibly drive the configured set."""
    config = _load(args.config)
    profile = config.profile
    print(
        f"probing control paths for {profile.make} {profile.model}"
        f" (profile '{profile.name}', preference order)"
    )
    results = probe_backends(config)
    for result in results:
        mark = "ok" if result.available else "--"
        discrete = {True: "discrete", False: "toggle", None: "?"}[result.discrete]
        print(f"  [{mark}] {result.backend:<15} {discrete:<9} {result.detail}")
    available = [r for r in results if r.available]
    if not available:
        print("no usable control path found; check wiring and profile codes")
        return 1
    best = available[0].backend
    print(f"suggested control.backend = \"{best}\"")

    if args.active:
        print(f"active test: muting via {config.control.backend} for {args.gap:.0f}s...")
        try:
            controller = build_controller(config.control, profile)
            controller.mute()
            time.sleep(args.gap)
            controller.unmute()
            controller.close()
        except ControlError as exc:
            raise SystemExit(f"adhush: active test failed: {exc}") from exc
        print("done — confirm the set muted and unmuted")
    return 0


def _cmd_calibrate(args: argparse.Namespace) -> int:
    """Build the logo edge template from material where the logo is visible."""
    config = _load(args.config)
    logo_cfg = config.detect.logo_absence
    source = (
        FileReplaySource(args.input) if args.input else build_capture(config.capture)
    )
    rois = []
    with source:
        if not source.caps().video:
            raise SystemExit("adhush: calibrate needs a video-capable source")
        start_ts: float | None = None
        for frame in source.frames():
            if start_ts is None:
                start_ts = frame.ts
            if frame.ts - start_ts > args.seconds:
                break
            roi = logo_cfg.roi
            rois.append(extract_roi(frame.frame, roi.x, roi.y, roi.w, roi.h).copy())
    if not rois:
        raise SystemExit("adhush: no frames captured; nothing to calibrate from")
    template = build_template(rois)
    save_template(Path(logo_cfg.template), template)
    print(
        f"calibrated logo template from {len(rois)} frames"
        f" (roi {logo_cfg.roi.x:.2f},{logo_cfg.roi.y:.2f}"
        f" {logo_cfg.roi.w:.2f}x{logo_cfg.roi.h:.2f}) -> {logo_cfg.template}"
    )
    print("make sure the network logo was on screen the whole time; re-run if not")
    return 0


def _cmd_learn(args: argparse.Namespace) -> int:
    """Seed the fingerprint store from a recording with labeled ad segments."""
    config = _load(args.config)
    fp_cfg = config.fingerprint
    segments = sorted(_read_labels(args.labels), key=lambda s: s.start_ts)
    if not segments:
        raise SystemExit("adhush: labels file contains no segments")
    _store, matcher, learner = open_fingerprints(fp_cfg)
    detector = FingerprintDetector(fp_cfg, matcher)

    learned: list[tuple[AdSegment, int | None]] = []

    def flush(seg: AdSegment) -> None:
        end = seg.start_ts + min(fp_cfg.window_s, seg.duration_s)
        ad_id = learner.learn_segment(
            seg.start_ts,
            seg.duration_s,
            detector.video_between(seg.start_ts, end),
            detector.audio_between(seg.start_ts, end),
        )
        learned.append((seg, ad_id))

    source = FileReplaySource(args.input)
    with source:
        if not source.caps().video:
            raise SystemExit("adhush: learn needs a video-capable recording")
        pending = list(segments)
        events: Iterator[FrameEvent | AudioEvent] = heapq.merge(
            source.frames(), source.audio_blocks(), key=lambda e: e.ts
        )
        for event in events:
            if isinstance(event, FrameEvent):
                detector.observe_frame(event)
            else:
                detector.observe_audio(event)
            while pending and event.ts > pending[0].start_ts + fp_cfg.window_s + 1.0:
                flush(pending.pop(0))
        for seg in pending:
            flush(seg)

    for seg, ad_id in learned:
        status = f"stored as ad {ad_id}" if ad_id is not None else "skipped"
        print(f"  {seg.start_ts:8.1f}s +{seg.duration_s:.0f}s  {status}")
    print(f"fingerprint store now holds {_store.count()} ad(s) at {fp_cfg.store}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="adhush", description="Mute TV commercials automatically.")
    parser.add_argument("--version", action="version", version=f"adhush {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="run live detection and control")
    p_run.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_run.set_defaults(func=_cmd_run)

    p_replay = sub.add_parser("replay", help="replay a recording offline and score against labels")
    p_replay.add_argument("input", type=Path, help=".npz fixture or media file")
    p_replay.add_argument("--config", type=Path, default=None)
    p_replay.add_argument("--labels", type=Path, default=None, help="JSON [{start_ts, duration_s}]")
    p_replay.add_argument("--mute-tolerance", type=float, default=4.0)
    p_replay.add_argument("--unmute-tolerance", type=float, default=2.0)
    p_replay.set_defaults(func=_cmd_replay)

    p_probe = sub.add_parser("probe", help="discover which control backends can drive the set")
    p_probe.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_probe.add_argument(
        "--active", action="store_true", help="also send a real mute/unmute pair"
    )
    p_probe.add_argument("--gap", type=float, default=2.0, help="seconds muted in --active")
    p_probe.set_defaults(func=_cmd_probe)

    p_doctor = sub.add_parser("doctor", help="check environment and configuration")
    p_doctor.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_doctor.set_defaults(func=_cmd_doctor)

    p_ir = sub.add_parser("ir-test", help="send mute then unmute over IR to verify codes")
    p_ir.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_ir.add_argument("--gap", type=float, default=2.0, help="seconds between mute and unmute")
    p_ir.set_defaults(func=_cmd_ir_test)

    p_cal = sub.add_parser(
        "calibrate", help="build the logo template from logo-visible program material"
    )
    p_cal.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_cal.add_argument("--input", type=Path, default=None, help="recording; default: live capture")
    p_cal.add_argument("--seconds", type=float, default=10.0)
    p_cal.set_defaults(func=_cmd_calibrate)

    p_learn = sub.add_parser(
        "learn", help="seed the fingerprint store from a labeled recording"
    )
    p_learn.add_argument("input", type=Path, help=".npz fixture or media file")
    p_learn.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_learn.add_argument("--labels", type=Path, required=True, help="JSON [{start_ts, duration_s}]")
    p_learn.set_defaults(func=_cmd_learn)

    args = parser.parse_args(argv)
    return int(args.func(args))
