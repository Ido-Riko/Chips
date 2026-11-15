"""One cancellable local CPU inference process; no cloud fallback."""
import sys
import subprocess
import numpy as np
from faster_whisper import WhisperModel


def main():
    model = WhisperModel(sys.argv[2], device="cpu", compute_type="int8", cpu_threads=4,
                         local_files_only=True)
    # Bound decoding independently of possibly forged container duration metadata.
    decoded = subprocess.run(["ffmpeg", "-v", "error", "-i", sys.argv[1], "-t", "61",
                              "-f", "s16le", "-ac", "1", "-ar", "16000", "pipe:1"],
                             check=True, capture_output=True, timeout=15).stdout
    if not 12800 <= len(decoded) <= 60 * 16000 * 2:
        raise ValueError("Decoded audio duration outside limits")
    audio = np.frombuffer(decoded, np.int16).astype(np.float32) / 32768.0
    segments, _ = model.transcribe(audio, language=sys.argv[3] or None,
                                   beam_size=3, vad_filter=True)
    print(" ".join(segment.text.strip() for segment in segments).strip())


if __name__ == "__main__":
    main()
