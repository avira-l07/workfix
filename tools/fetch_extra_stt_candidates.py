"""Download pinned Tamil/Telugu Whisper fine-tunes for desktop checks only."""
import concurrent.futures
import hashlib
import json
from pathlib import Path

import requests


ROOT = Path(__file__).resolve().parents[1]
REPO = "ippocode/indic-asr-onnx"
REVISION = "1f6243ec6dffc4de2db84fe1446576d1ff52c252"
FOLDERS = {"ta": "whisper-small-ta", "te": "whisper-tiny-te"}
OUTPUT = ROOT / "tools/stt_models/extra-candidates"


def download(entry: dict, folder: str) -> dict:
    name = Path(entry["path"]).name
    destination = OUTPUT / folder / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    expected = entry.get("lfs", {}).get("oid")
    if destination.exists() and destination.stat().st_size == entry["size"]:
        with destination.open("rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if not expected or actual == expected:
            return {"file": name, "bytes": entry["size"], "sha256": actual}
    url = f"https://huggingface.co/{REPO}/resolve/{REVISION}/{entry['path']}"
    with requests.get(url, stream=True, timeout=(30, 180)) as response:
        response.raise_for_status()
        with destination.open("wb") as out:
            for chunk in response.iter_content(1024 * 1024):
                out.write(chunk)
    if destination.stat().st_size != entry["size"]:
        raise ValueError(f"Incomplete {name}")
    with destination.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if expected and actual != expected:
        raise ValueError(f"Checksum mismatch: {name}")
    if not expected:
        data = destination.read_bytes()
        git_oid = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        if git_oid != entry["oid"]:
            raise ValueError(f"Publisher object mismatch: {name}")
    return {"file": name, "bytes": entry["size"], "sha256": actual}


def main() -> None:
    for code, folder in FOLDERS.items():
        url = f"https://huggingface.co/api/models/{REPO}/tree/{REVISION}/models/{folder}"
        response = requests.get(url, timeout=60)
        response.raise_for_status()
        inventory = response.json()
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            files = list(pool.map(lambda entry: download(entry, folder), inventory))
        report = {"language": code, "repo": REPO, "revision": REVISION, "files": files}
        (OUTPUT / folder / "inventory.json").write_text(json.dumps(report, indent=2))
        print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
