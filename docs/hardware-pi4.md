# Reference hardware: Raspberry Pi 4 (2 GB)

## Video path
Pi 4 has no HDMI input. Capture requires a USB 3.0 HDMI-to-UVC dongle on the
output of an HDMI splitter placed between source and TV.

Content protection on the HDMI link will prevent capture of protected sources.
AdHush does not defeat it. For protected sources use the camera path, the
audio-only path, or screen capture on the playback device itself.

## Audio path
Either the dongle's embedded audio, an HDMI audio extractor, or a USB audio
input taking the TV's analog/optical out.

## IR transmit
- GPIO pin driving an IR LED through a transistor (2N2222 or similar) and a
  current-limiting resistor; do not drive an IR LED directly from a GPIO pin.
- `gpio-ir-tx` overlay in `/boot/firmware/config.txt`, or `pigpio` waveforms.
- Place the emitter within line of sight of the set's IR receiver window.

## Budget on 2 GB
Prefer 720p capture. Run detectors on a shared downscaled frame. Keep the
fingerprint index memory-mapped rather than fully resident.
