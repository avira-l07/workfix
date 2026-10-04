"""Small offline tests for the Kaggle trial's speaker and leakage gates."""

import hashlib
import json
import tempfile
from pathlib import Path

from check_speech_leakage import check_rows, text_hash
from kathbath_noisy_test import parse_transcript_lines, split_member
from kathbath_trial import choose_clips, choose_shards
from prepare_speech_finetune import speaker_split


def main():
    groups = [f"kathbath:{speaker}" for speaker in range(20) for _ in range(3)]
    train, dev = speaker_split(groups, seed=17)
    assert len(train) == 18 and len(dev) == 2 and not train & dev
    assert speaker_split(list(reversed(groups)), seed=17) == (train, dev)

    shards = [
        {"index": 0, "hours": 4.0, "speaker_ids": [0, 1], "speaker_seconds": {0: 3600, 1: 3600}},
        {"index": 1, "hours": 4.0, "speaker_ids": [1, 2], "speaker_seconds": {1: 3600, 2: 3600}},
        {"index": 2, "hours": 4.0, "speaker_ids": [3, 4], "speaker_seconds": {3: 3600, 4: 3600}},
    ]
    assert set(choose_shards(shards, 1, cap_minutes=20)) == {0, 2}
    candidates = [{"speaker_id": speaker, "duration": 5.0,
                   "fname": f"{speaker}-{clip}.m4a"}
                  for speaker in range(4) for clip in range(3)]
    chosen = choose_clips(candidates, target_seconds=40, cap_seconds=10, seed=17)
    assert len(chosen) == 8
    assert {r["speaker_id"] for r in chosen} == set(range(4))

    assert split_member("kb_data_noisy_m4a/tamil/test/example.m4a") == ("ta", "test", "example.m4a")
    assert parse_transcript_lines("example.m4a வணக்கம் உலகம்\n".encode()) == {
        "example.m4a": "வணக்கம் உலகம்"}
    with tempfile.TemporaryDirectory() as folder:
        audio = Path(folder) / "clip.wav"
        audio.write_bytes(b"test audio")
        index = {"text_sha256": [text_hash("வணக்கம் உலகம்")],
                 "source_ids": ["kathbath:ta:known"],
                 "audio_sha256": [hashlib.sha256(audio.read_bytes()).hexdigest()]}
        row = {"text": "வணக்கம், உலகம்", "source_id": "kathbath:ta:known",
               "audio_filepath": str(audio)}
        errors = check_rows(index, [row])
        assert len(errors) == 3, errors
    # Exercise actual manifest preparation, including speaker/shard ID zero.
    import numpy as np
    import soundfile as sf
    import prepare_speech_finetune as prep
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        audio = root / "synthetic.wav"
        sf.write(audio, np.zeros(16000, dtype=np.float32), 16000, subtype="PCM_16")
        rows = [{"audio_filepath": str(audio), "text": "வணக்கம் உலகம்",
                 "source_id": f"synthetic-{speaker}", "speaker_id": speaker,
                 "source": "synthetic", "source_version": "test", "license": "CC0-1.0",
                 "source_shard": 0, "gender": "unknown"} for speaker in range(20)]
        source = root / "source.jsonl"
        source.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        protected = root / "protected.json"
        protected.write_text(json.dumps({"language": "ta", "splits": {"validation": 1, "test": 1},
                                        "text_sha256": [], "audio_sha256": [], "source_ids": []}))
        original_data = prep.DATA
        try:
            prep.DATA = root / "data"
            prep.prepare("ta", source, root / "prepared", protected, 17)
            audit = json.loads((root / "prepared/ta-audit.json").read_text())
            assert audit["speaker_groups_by_split"] == {"train": 18, "dev": 2}
            assert audit["speaker_overlap_count"] == 0 and audit["source_shards"] == [0]
            output = [json.loads(line) for path in (prep.DATA / "manifests").glob("*.jsonl")
                      for line in path.read_text(encoding="utf-8").splitlines()]
            assert any(row["speaker_id"] == 0 for row in output)
        finally:
            prep.DATA = original_data
    print("Kathbath trial: disjoint speaker split, cap, transcript and three leakage gates passed")


if __name__ == "__main__":
    main()
