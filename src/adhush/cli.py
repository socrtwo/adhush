"""Argument parsing, subcommands: run, calibrate, learn, replay, doctor, ir-test."""

from __future__ import annotations

import argparse
import json
import logging
import shutil
import signal
import sys
import threading
import time
from pathlib import Path

from adhush import __version__
from adhush.capture import build_capture
from adhush.capture.file_replay import FileReplaySource
from adhush.config import Config, ConfigError, DetectConfig, FusionConfig, load_config
from adhush.control import NullController, build_controller
from adhush.control.base import ControlError
from adhush.control.ir_lirc import IrLircController
from adhush.detect import build_detectors
from adhush.detect.fusion import Fusion
from adhush.engine import Pipeline, evaluate_onsets, run_live, run_offline
from adhush.events import AdSegment
from adhush.state import AdStateMachine

_DEFAULT_CONFIG = Path("config/adhush.toml")


def _load(path: Path) -> Config:
    try:
        return load_config(path)
    except (ConfigError, OSError) as exc:
        raise SystemExit(f"adhush: config error: {exc}") from exc


def _build_pipeline(config: Config, controller: NullController | None, *, video: bool, audio: bool) -> Pipeline:
    detectors = build_detectors(config.detect, video=video, audio=audio)
    if not detectors:
        raise SystemExit("adhush: no enabled detector matches the available modalities")
    fusion = Fusion(config.fusion, config.profile.fusion_weights, [d.name for d in detectors])
    machine = AdStateMachine(config.fusion)
    ctl = controller if controller is not None else build_controller(config.control)
    return Pipeline(detectors, fusion, machine, ctl)


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
    detect_cfg = config.detect if config else DetectConfig()
    fusion_cfg = config.fusion if config else FusionConfig()
    weights = config.profile.fusion_weights if config else {}

    source = FileReplaySource(args.input)
    controller = NullController()
    with source:
        caps = source.caps()
        detectors = build_detectors(detect_cfg, video=caps.video, audio=caps.audio)
        if not detectors:
            raise SystemExit("adhush: no detector matches the fixture's modalities")
        fusion = Fusion(fusion_cfg, weights, [d.name for d in detectors])
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
    options = dict(config.control.options)
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


def _cmd_phase2(name: str) -> int:
    print(f"adhush: '{name}' lands in Phase 2 (see docs/roadmap.md)")
    return 2


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

    p_doctor = sub.add_parser("doctor", help="check environment and configuration")
    p_doctor.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_doctor.set_defaults(func=_cmd_doctor)

    p_ir = sub.add_parser("ir-test", help="send mute then unmute over IR to verify codes")
    p_ir.add_argument("--config", type=Path, default=_DEFAULT_CONFIG)
    p_ir.add_argument("--gap", type=float, default=2.0, help="seconds between mute and unmute")
    p_ir.set_defaults(func=_cmd_ir_test)

    for phase2 in ("calibrate", "learn"):
        p = sub.add_parser(phase2, help="(Phase 2)")
        p.set_defaults(func=lambda _a, _n=phase2: _cmd_phase2(_n))

    args = parser.parse_args(argv)
    return int(args.func(args))
