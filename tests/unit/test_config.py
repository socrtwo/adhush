from pathlib import Path

import pytest

from adhush.config import ConfigError, load_config, load_profile

REPO = Path(__file__).resolve().parents[2]
EXAMPLE = REPO / "config" / "adhush.example.toml"
PROFILES = REPO / "config" / "profiles"


def test_example_config_loads_with_sharp_profile() -> None:
    config = load_config(EXAMPLE, PROFILES)
    assert config.capture.backend == "hdmi_uvc"
    assert config.detect.black_frame.luma_threshold == 16
    assert config.detect.silence.min_run_ms == 400
    assert config.fusion.mute_confidence == 0.72
    assert config.control.backend == "rs232_sharp"
    assert config.control.options["port"] == "/dev/ttyUSB0"

    profile = config.profile
    assert (profile.make, profile.model) == ("Sharp", "LC-46LE830U")
    assert profile.discrete_mute and profile.state_readback
    # Inherited from generic and not overridden:
    assert profile.fusion_weights["loudness"] == 0.15


def test_profile_inheritance_merges_parent(tmp_path: Path) -> None:
    (tmp_path / "base.toml").write_text(
        '[identity]\nmake = "base"\nmodel = "base"\n[fusion.weights]\nloudness = 0.2\n'
    )
    (tmp_path / "child.toml").write_text('inherits = "base"\n[identity]\nmodel = "child"\n')
    profile = load_profile(tmp_path, "child")
    assert profile.make == "base"
    assert profile.model == "child"
    assert profile.fusion_weights == {"loudness": 0.2}


def test_profile_cycle_detected(tmp_path: Path) -> None:
    (tmp_path / "a.toml").write_text('inherits = "b"\n')
    (tmp_path / "b.toml").write_text('inherits = "a"\n')
    with pytest.raises(ConfigError, match="cycle"):
        load_profile(tmp_path, "a")


def test_missing_profile_rejected(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="not found"):
        load_profile(tmp_path, "nope")


def test_unknown_detector_rejected(tmp_path: Path) -> None:
    (tmp_path / "adhush.toml").write_text('[detect]\nenabled = ["psychic"]\n')
    with pytest.raises(ConfigError, match="unknown detector"):
        load_config(tmp_path / "adhush.toml", PROFILES)


def test_inverted_fusion_thresholds_rejected(tmp_path: Path) -> None:
    (tmp_path / "adhush.toml").write_text(
        "[fusion]\nmute_confidence = 0.4\nunmute_confidence = 0.6\n"
    )
    with pytest.raises(ConfigError, match="thresholds"):
        load_config(tmp_path / "adhush.toml", PROFILES)


def test_unknown_control_backend_rejected(tmp_path: Path) -> None:
    (tmp_path / "adhush.toml").write_text('[control]\nbackend = "telepathy"\n')
    with pytest.raises(ConfigError, match="control backend"):
        load_config(tmp_path / "adhush.toml", PROFILES)
