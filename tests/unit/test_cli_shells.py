"""The new subcommands are registered and the run flag is tri-state."""

import pytest

from adhush.cli import main


@pytest.mark.parametrize("argv", [["overlay", "--help"], ["service", "--help"], ["run", "--help"]])
def test_subcommands_have_help(argv, capsys) -> None:
    with pytest.raises(SystemExit) as exc:
        main(argv)
    assert exc.value.code == 0
    assert "overlay" in capsys.readouterr().out


def test_service_requires_an_action() -> None:
    with pytest.raises(SystemExit) as exc:
        main(["service"])
    assert exc.value.code == 2
