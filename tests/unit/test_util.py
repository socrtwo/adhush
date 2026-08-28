import numpy as np
import pytest

from adhush.util.imageops import downscale, extract_roi, mean_luma, to_luma
from adhush.util.ringbuffer import RingBuffer
from adhush.util.timing import DwellTimer, snap_to_slot


class TestDwellTimer:
    def test_requires_continuous_hold(self) -> None:
        timer = DwellTimer(1.0)
        assert not timer.update(True, 0.0)
        assert not timer.update(True, 0.5)
        assert timer.update(True, 1.0)

    def test_break_resets(self) -> None:
        timer = DwellTimer(1.0)
        timer.update(True, 0.0)
        timer.update(False, 0.5)
        assert not timer.update(True, 1.5)
        assert timer.update(True, 2.5)

    def test_zero_dwell_fires_immediately(self) -> None:
        assert DwellTimer(0.0).update(True, 0.0)

    def test_negative_dwell_rejected(self) -> None:
        with pytest.raises(ValueError):
            DwellTimer(-1.0)


class TestSnapToSlot:
    def test_snaps_to_nearest(self) -> None:
        slots = [15.0, 30.0, 45.0, 60.0]
        assert snap_to_slot(29.0, slots) == 30.0
        assert snap_to_slot(16.2, slots) == 15.0
        assert snap_to_slot(100.0, slots) == 60.0

    def test_empty_slots_pass_through(self) -> None:
        assert snap_to_slot(23.0, []) == 23.0


class TestRingBuffer:
    def test_drops_oldest_when_full(self) -> None:
        buf: RingBuffer[int] = RingBuffer(3)
        for i in range(5):
            buf.push(i)
        assert list(buf) == [2, 3, 4]
        assert buf.full
        assert buf.latest() == 4

    def test_sized_from_duration_and_rate(self) -> None:
        buf: RingBuffer[int] = RingBuffer.for_duration(2.0, 30.0)
        assert buf.capacity == 60

    def test_empty_latest_raises(self) -> None:
        with pytest.raises(IndexError):
            RingBuffer(1).latest()

    def test_bad_capacity_rejected(self) -> None:
        with pytest.raises(ValueError):
            RingBuffer(0)


class TestImageOps:
    def test_to_luma_bt601(self) -> None:
        # Pure red in BGR: luma = 0.299 * 255 ≈ 76
        frame = np.zeros((4, 4, 3), dtype=np.uint8)
        frame[:, :, 2] = 255
        assert abs(int(to_luma(frame)[0, 0]) - 76) <= 1

    def test_luma_passthrough(self) -> None:
        frame = np.full((4, 4), 42, dtype=np.uint8)
        assert to_luma(frame) is frame

    def test_downscale_area_mean(self) -> None:
        frame = np.zeros((4, 4), dtype=np.uint8)
        frame[:2, :] = 100
        small = downscale(frame, 2)
        assert small.shape == (2, 2)
        assert small[0, 0] == 100 and small[1, 0] == 0

    def test_extract_roi_lower_right(self) -> None:
        frame = np.zeros((100, 200), dtype=np.uint8)
        frame[80:, 168:] = 255
        roi = extract_roi(frame, x=0.84, y=0.80, w=0.16, h=0.20)
        assert roi.shape == (20, 32)
        assert mean_luma(roi) == 255.0

    def test_roi_out_of_range_rejected(self) -> None:
        frame = np.zeros((10, 10), dtype=np.uint8)
        with pytest.raises(ValueError):
            extract_roi(frame, x=0.9, y=0.0, w=0.5, h=0.5)
