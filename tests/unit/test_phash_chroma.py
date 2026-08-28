import numpy as np

from adhush.fingerprint.audio_chroma import CHROMA_BITS, agreement, chroma_bits
from adhush.fingerprint.video_phash import HASH_BITS, frame_std, hamming, phash

RATE = 8000


def _texture(seed: int) -> np.ndarray:
    return np.random.default_rng(seed).integers(0, 256, (48, 64), dtype=np.uint8)


class TestPhash:
    def test_deterministic(self) -> None:
        frame = _texture(1)
        assert phash(frame) == phash(frame.copy())

    def test_robust_to_mild_noise(self) -> None:
        frame = _texture(2).astype(np.int16)
        noisy = np.clip(frame + np.random.default_rng(3).integers(-5, 6, frame.shape), 0, 255)
        distance = hamming(phash(frame.astype(np.uint8)), phash(noisy.astype(np.uint8)))
        assert distance <= 10

    def test_different_content_is_distant(self) -> None:
        distances = [
            hamming(phash(_texture(seed)), phash(_texture(seed + 100)))
            for seed in range(5)
        ]
        assert min(distances) > 10  # beyond the default match threshold

    def test_hash_fits_declared_bits(self) -> None:
        assert phash(_texture(4)) < (1 << HASH_BITS)

    def test_flat_frame_gate(self) -> None:
        flat = np.full((48, 64), 40, dtype=np.uint8)
        assert frame_std(flat) == 0.0
        assert frame_std(_texture(5)) > 6.0


class TestChroma:
    def _tone(self, hz: float, seconds: float = 0.5) -> np.ndarray:
        t = np.arange(int(RATE * seconds), dtype=np.float64) / RATE
        return (0.3 * np.sin(2 * np.pi * hz * t)).astype(np.float32)

    def test_deterministic_and_level_invariant(self) -> None:
        tone = self._tone(523.0)
        assert chroma_bits(tone, RATE) == chroma_bits(tone * 0.1, RATE)

    def test_distinguishes_pitch_classes(self) -> None:
        c_note = chroma_bits(self._tone(523.0), RATE)  # C
        f_sharp = chroma_bits(self._tone(740.0), RATE)  # F#
        assert c_note != f_sharp

    def test_agreement_bounds(self) -> None:
        a = [chroma_bits(self._tone(hz), RATE) for hz in (523.0, 659.0, 784.0)]
        b = [chroma_bits(self._tone(hz), RATE) for hz in (554.0, 740.0, 880.0)]
        assert agreement(a, a) == 1.0
        assert agreement(a, b) < 1.0
        assert agreement([], []) == 0.0

    def test_bits_fit_declared_width(self) -> None:
        assert chroma_bits(self._tone(659.0), RATE) < (1 << CHROMA_BITS)
