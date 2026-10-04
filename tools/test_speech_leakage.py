"""Small runnable guard test: python tools/test_speech_leakage.py"""

import hashlib
import tempfile
from pathlib import Path

from check_speech_leakage import check_rows, text_hash


def main():
    with tempfile.TemporaryDirectory() as folder:
        audio = Path(folder) / "sample.wav"
        audio.write_bytes(b"small synthetic audio for hash test")
        clean_audio = Path(folder) / "different.wav"
        clean_audio.write_bytes(b"different synthetic audio")
        protected = {"text_sha256": [text_hash("வணக்கம் உலகம்!")],
                     "audio_sha256": [hashlib.sha256(audio.read_bytes()).hexdigest()],
                     "source_ids": ["held-out-7"]}
        assert check_rows(protected, [{"text": "புதிய செய்தி", "source_id": "fresh",
                                       "audio_filepath": str(clean_audio)}]) == []
        assert any("transcript" in e for e in check_rows(protected, [
            {"text": "வணக்கம், உலகம்", "source_id": "fresh", "audio_filepath": str(clean_audio)}]))
        assert any("source id" in e for e in check_rows(protected, [
            {"text": "புதிய செய்தி", "source_id": "held-out-7", "audio_filepath": str(clean_audio)}]))
        assert any("missing source id" in e for e in check_rows(protected, [
            {"text": "புதிய செய்தி", "audio_filepath": str(clean_audio)}]))
        assert any("audio" in e for e in check_rows(protected, [
            {"text": "புதிய செய்தி", "source_id": "fresh", "audio_filepath": str(audio)}]))
    print("leakage guard: transcript, source id and audio overlaps rejected")


if __name__ == "__main__":
    main()
