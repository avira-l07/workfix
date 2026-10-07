"""Fetch and verify one pinned public neural-model snapshot, without executing its code."""
import hashlib
import json
import time
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent
info = json.loads((root / "direct-model-info.json").read_text(encoding="utf-8"))
assert info["id"] == "hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8"
revision = info["sha"]
assert len(revision) == 40 and all(c in "0123456789abcdef" for c in revision)
directory = root / "direct-int8"
directory.mkdir(exist_ok=True)
names = {"encoder_model.onnx", "encoder_model.onnx.data", "decoder_model.onnx",
         "decoder_shared.onnx.data", "decoder_with_past_model.onnx", "tokenizer_src.json",
         "tokenizer_tgt.json", "tokenizer_meta.json", "config.json", "generation_config.json",
         "model.SRC", "model.TGT", "dict.SRC.json", "dict.TGT.json"}
files = []
for row in info["siblings"]:
    name = row["rfilename"]
    if name not in names:
        continue
    path = directory / name
    temporary = directory / (name + ".download")
    if not path.exists():
        url = f"https://huggingface.co/{info['id']}/resolve/{revision}/{name}"
        if not temporary.exists() or temporary.stat().st_size != row["size"]:
            with urllib.request.urlopen(url, timeout=90) as source, temporary.open("wb") as destination:
                while chunk := source.read(1024 * 1024):
                    destination.write(chunk)
        for attempt in range(8):
            try:
                temporary.replace(path)
                break
            except PermissionError:
                if attempt == 7:
                    raise
                time.sleep(0.25 * (attempt + 1))
    assert path.stat().st_size == row["size"], name
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if row.get("lfs"):
        assert digest == row["lfs"]["sha256"], name
    else:
        data = path.read_bytes()
        blob = b"blob " + str(len(data)).encode() + b"\0" + data
        assert hashlib.sha1(blob).hexdigest() == row["blobId"], name
    files.append({"name": name, "bytes": path.stat().st_size, "sha256": digest})
    print(f"Verified {name} ({path.stat().st_size:,} bytes)", flush=True)
assert {row["name"] for row in files} == names
(root / "direct-model-verification.json").write_text(
    json.dumps({"repository": info["id"], "revision": revision, "files": files}, indent=2) + "\n")
