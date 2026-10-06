"""Bounded Tamil Gate B baseline/pilot. NeMo imports occur only on the CUDA host.

This is a training-readiness experiment, not the 10-hour training trial or an
app update. The notebook embeds this file and the existing saved-data guard.
"""

import argparse
import hashlib
import json
import math
import os
import random
import statistics
import subprocess
import sys
import time
import traceback
from collections import defaultdict
from pathlib import Path

from kathbath_gate_b_data import digest

NEMO_REVISION = "8dce88cf8e94963e2033c3137f7b9993b51db88a"
MODEL_REPO = "ai4bharat/indicconformer_stt_ta_hybrid_ctc_rnnt_large"
MODEL_REVISION = "8c31aa8d04964b8fc87e4eaaee7916f7d2c024da"
MODEL_FILE = "indicconformer_stt_ta_hybrid_rnnt_large.nemo"
REFERENCE_WER = 0.2182741116751269
BASELINE_TOLERANCE = 0.03
MAX_STEPS = 20
SEEDS = (17, 29)


def write_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                         encoding="utf-8")
    temporary.replace(path)


def read_rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def subset(rows, count):
    """Fixed speaker round-robin, never using transcripts/WER to select clips."""
    groups = defaultdict(list)
    for row in rows:
        if 1 <= row["duration"] <= 10:
            groups[row["speaker_group"]].append(row)
    rng = random.Random(20261004)
    names = sorted(groups)
    rng.shuffle(names)
    for name in names:
        groups[name].sort(key=lambda row: row["source_id"])
        rng.shuffle(groups[name])
    selected = []
    while len(selected) < count and any(groups.values()):
        for name in names:
            if groups[name] and len(selected) < count:
                selected.append(groups[name].pop())
    if len(selected) != count:
        raise RuntimeError(f"Need {count} eligible TRAIN/DEV clips; found {len(selected)}")
    return selected


def make_plan(repo, data_report):
    repo = Path(repo)
    if data_report["language"] != "ta" or not data_report["status"].startswith("Saved Tamil data checks passed"):
        raise RuntimeError("A successful Tamil saved-data check is required")
    data = repo / "tools/stt_training"
    full = {s: read_rows(data / f"manifests/ta-{s}.jsonl") for s in ("train", "dev")}
    chosen = {"train": subset(full["train"], 128), "dev": subset(full["dev"], 64)}
    if ({r["speaker_group"] for r in chosen["train"]} & {r["speaker_group"] for r in chosen["dev"]}
            or {r["source_id"] for r in chosen["train"]} & {r["source_id"] for r in chosen["dev"]}):
        raise RuntimeError("Pilot TRAIN/DEV overlap")
    manifests = {}
    pilot = repo / "pilot"
    pilot.mkdir(exist_ok=True)
    for side, rows in chosen.items():
        path = pilot / f"ta-{side}.jsonl"
        # The fork's multilingual tokenizer reads `lang`, not `language_id`.
        path.write_text("".join(json.dumps(dict(row, lang="ta"), ensure_ascii=False) + "\n" for row in rows),
                        encoding="utf-8")
        manifests[side] = str(path.resolve())
    protected = data / "ta-combined-protected.json"
    command = [sys.executable, str(repo / "tools/check_speech_leakage.py"), "check", "--language", "ta",
               "--protected", str(protected), "--manifest", *manifests.values()]
    checked = subprocess.run(command, check=True, capture_output=True, text=True)
    paths = [*map(Path, manifests.values()), *sorted((repo / "tools").glob("*.py")), protected,
             data / "manifests/ta-train.jsonl", data / "manifests/ta-dev.jsonl",
             data / "noisy-ta-test-unknown.json",
             repo / "tools/stt_models/five-language-validation/manifest-ta-30.json",
             repo / "tools/stt_models/five-language-test/manifest-ta-100.json"]
    plan = {"status": "Pilot prepared; baseline and training not run", "language": "ta",
            "gate_a_report_sha256": data_report["gate_a_report_sha256"], "manifests": manifests,
            "sealed_files": {str(p.resolve()): digest(p) for p in paths},
            "sealed_pilot_audio": {r["audio_filepath"]: digest(r["audio_filepath"])
                                   for rows in chosen.values() for r in rows},
            "counts": {s: len(rows) for s, rows in chosen.items()},
            "hours": {s: sum(r["duration"] for r in rows) / 3600 for s, rows in chosen.items()},
            "speakers": {s: len({r["speaker_group"] for r in rows}) for s, rows in chosen.items()},
            "seeds": list(SEEDS), "modes": ["freeze-lower", "full"], "max_steps_per_run": MAX_STEPS,
            "leakage_output": checked.stdout.strip(), "phone_accuracy": "Not verified",
            "full_trial": "Not run", "heldout_after_training": "Not run", "app_promotion": "Not done"}
    write_json(repo / "pilot-plan.json", plan)
    return plan


def verify_plan(repo):
    plan = json.loads((Path(repo) / "pilot-plan.json").read_text(encoding="utf-8"))
    if plan["language"] != "ta" or plan["max_steps_per_run"] != MAX_STEPS or plan["seeds"] != list(SEEDS):
        raise RuntimeError("Unexpected pilot scope; do not expand the reviewed trial")
    for path, expected in plan["sealed_files"].items():
        if digest(path) != expected:
            raise RuntimeError(f"Prepared data changed: {path}")
    for path, expected in plan["sealed_pilot_audio"].items():
        if digest(path) != expected:
            raise RuntimeError(f"Pilot audio changed: {path}")
    # Detect audio changes after preflight as well as modified manifests.
    sys.path.insert(0, str(Path(repo) / "tools"))
    from check_speech_leakage import check_rows
    index = json.loads((Path(repo) / "tools/stt_training/ta-combined-protected.json").read_text(encoding="utf-8"))
    for path in plan["manifests"].values():
        rows = read_rows(path)
        if any(r.get("lang") != "ta" for r in rows):
            raise RuntimeError("Pilot rows must explicitly pass Tamil to the tokenizer")
        errors = check_rows(index, rows)
        if errors:
            raise RuntimeError("Pilot leakage: " + "; ".join(errors[:4]))
    return plan


def completed_pilot(repo, mode, seed):
    """Skip a completed run only when its measured evidence matches this trial."""
    repo = Path(repo)
    path = repo / f"pilot-{mode}-{seed}/report.json"
    if not path.exists():
        return None
    result = json.loads(path.read_text(encoding="utf-8"))
    valid = (result.get("status") == "Bounded pilot executed; full trial and held-out comparison not run"
             and not result.get("error") and result.get("weights_changed") is True
             and result.get("language") == "ta" and result.get("mode") == mode
             and result.get("seed") == seed and seed in SEEDS and mode in ("freeze-lower", "full")
             and result.get("max_steps") == MAX_STEPS and result.get("training_precision") == "32-true"
             and isinstance(result.get("global_step"), int) and 0 < result["global_step"] <= MAX_STEPS
             and result.get("plan_sha256") == digest(repo / "pilot-plan.json")
             and result.get("baseline_report_sha256") == digest(repo / "baseline-report.json")
             and (path.parent / "last-pilot.ckpt").is_file())
    history = result.get("history", [])
    valid = (valid and any("dev_ctc_wer" in row for row in history)
             and any("loss" in row for row in history)
             and all(math.isfinite(float(row[key])) and float(row[key]) >= 0
                     for row in history for key in ("loss", "dev_ctc_wer") if key in row))
    if not valid:
        raise RuntimeError(f"Existing {mode}/{seed} report is not a verified completed FP32 pilot; preserve it and share the ZIP")
    return result


def runtime():
    import torch
    import nemo
    import pytorch_lightning as pl
    if sys.version_info[:2] != (3, 11) or torch.__version__.split("+")[0] != "2.2.2":
        raise RuntimeError("Use the isolated Python 3.11 / PyTorch 2.2.2 environment in the notebook")
    source = Path(os.environ["ITANTRA_NEMO_SOURCE"]).resolve()
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != NEMO_REVISION or not Path(nemo.__file__).resolve().is_relative_to(source):
        raise RuntimeError("Imported NeMo is not the pinned AI4Bharat source checkout")
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable; this notebook does not train on CPU")
    gpu = torch.cuda.get_device_properties(0)
    if gpu.total_memory < 14 * 1024**3:
        raise RuntimeError("Use a CUDA GPU with at least 14 GiB reported VRAM for this pilot")
    return {"python": sys.version.split()[0], "torch": torch.__version__, "lightning": pl.__version__,
            "nemo_revision": revision, "gpu": gpu.name, "gpu_total_bytes": gpu.total_memory,
            "cuda": torch.version.cuda, "gpu_count": torch.cuda.device_count(), "devices_used": 1,
            "precision": "bf16-mixed" if torch.cuda.is_bf16_supported() else "16-mixed"}


def rnnt_cuda_preflight():
    """Exercise the exact CUDA interop/loss path before loading a large model."""
    import importlib.metadata as metadata
    import torch
    from numba import cuda
    from numba.cuda.cudadrv import driver
    from nemo.collections.asr.losses.rnnt import RNNTLoss
    if not driver.USE_NV_BINDING or metadata.version("cuda-python") != "12.4.0":
        raise RuntimeError("Install cuda-python==12.4.0 and set NUMBA_CUDA_USE_NVIDIA_BINDING=1 before importing Numba")
    checked = runtime()
    checked.update(numba=metadata.version("numba"), cuda_python=metadata.version("cuda-python"),
                   numba_cuda_use_nvidia_binding=bool(driver.USE_NV_BINDING))
    torch.manual_seed(17)
    values = torch.randn(1, 4, 3, 5, device="cuda:0")
    targets = torch.tensor([[1, 2]], device="cuda:0", dtype=torch.long)
    input_lengths = torch.tensor([4], device="cuda:0", dtype=torch.long)
    target_lengths = torch.tensor([2], device="cuda:0", dtype=torch.long)
    results = []
    for dtype in (torch.float32, torch.float16):
        logits = values.to(dtype).detach().requires_grad_(True)
        cuda.as_cuda_array(logits.detach())
        criterion = RNNTLoss(num_classes=4, reduction="mean_batch", loss_name="warprnnt_numba")
        loss = criterion(log_probs=logits, targets=targets, input_lengths=input_lengths,
                         target_lengths=target_lengths)
        loss.backward()
        torch.cuda.synchronize()
        if (not torch.isfinite(loss).all() or logits.grad is None
                or not torch.isfinite(logits.grad).all() or not torch.count_nonzero(logits.grad)):
            raise RuntimeError("RNNT CUDA smoke test produced invalid loss or gradients")
        results.append({"dtype": str(dtype), "loss": float(loss.detach()),
                        "gradient_norm": float(logits.grad.float().norm())})
    return dict(checked, status="Tiny RNNT CUDA forward/backward passed; pilot not run", checks=results)


def extract_text(value):
    if isinstance(value, tuple):
        value = value[0]
    while isinstance(value, list) and len(value) == 1:
        value = value[0]
    if hasattr(value, "text"):
        value = value.text
    if not isinstance(value, str):
        raise RuntimeError(f"Unexpected NeMo transcript structure: {type(value).__name__}")
    return value


def baseline_gate(summary):
    return (summary["count"] == 30 and summary["native_script_count"] == 30
            and math.isfinite(summary["wer"])
            and abs(summary["wer"] - REFERENCE_WER) <= BASELINE_TOLERANCE)


def restore(checkpoint, device, trainer=None):
    from omegaconf import open_dict
    from nemo.collections.asr.models import ASRModel
    config = ASRModel.restore_from(str(checkpoint), return_config=True)
    # Saved source training paths are not valid in a new Kaggle session.
    with open_dict(config):
        config.train_ds = None
        # The pinned BPE transcribe loader still reads validation_ds.get().
        config.validation_ds = {
            "use_start_end_token": (config.validation_ds or {}).get("use_start_end_token", False),
            "manifest_filepath": None, "defer_setup": True,
        }
        config.test_ds = None
    model = ASRModel.restore_from(str(checkpoint), override_config_path=config,
                                 map_location=device, trainer=trainer, strict=True)
    if model.cfg.preprocessor.sample_rate != 16000:
        raise RuntimeError("The restored model is not configured for 16-kHz speech")
    return model


def evaluation_wav_duration(path):
    """Read protected PCM16/float32 evaluation WAVs without converting them."""
    import numpy as np
    import soundfile as sf
    with sf.SoundFile(str(path)) as audio:
        if (audio.samplerate != 16000 or audio.channels != 1 or audio.format != "WAV"
                or audio.subtype not in ("PCM_16", "FLOAT") or audio.frames <= 0):
            raise RuntimeError(f"Expected nonempty 16-kHz mono PCM16/float32 evaluation WAV: {path}")
        frames, rate = audio.frames, audio.samplerate
        samples = audio.read(dtype="float32")
        if len(samples) != frames or not np.isfinite(samples).all():
            raise RuntimeError(f"Truncated or non-finite evaluation WAV: {path}")
        return frames / rate


def score(model, records, decoder, repo):
    import torch
    sys.path.insert(0, str(Path(repo) / "tools"))
    from validate_stt import normalize, distance, peak_memory_bytes
    from prepare_speech_finetune import native_text
    model.change_decoding_strategy(decoder_type=decoder, lang_id="ta")
    model.eval()
    torch.cuda.reset_peak_memory_stats()
    rows = []
    for row in records:
        if digest(row["file"]) != row["sha256"]:
            raise RuntimeError("Evaluation WAV checksum mismatch")
        duration = evaluation_wav_duration(row["file"])
        torch.cuda.synchronize()
        start = time.perf_counter()
        with torch.inference_mode():
            text = extract_text(model.transcribe([row["file"]], batch_size=1, logprobs=False, language_id="ta"))
        torch.cuda.synchronize()
        elapsed = (time.perf_counter() - start) * 1000
        ref, hyp = normalize(row["reference"]), normalize(text)
        rows.append({"row": row.get("row"), "audio_sha256": row["sha256"], "reference": row["reference"],
                     "output": text, "native_script": native_text("ta", text), "transcribe_ms": elapsed,
                     "audio_seconds": duration, "word_errors": distance(ref.split(), hyp.split()),
                     "words": len(ref.split()), "char_errors": distance(ref, hyp), "chars": len(ref)})
    times = sorted(r["transcribe_ms"] for r in rows)
    return {"decoder": decoder, "summary": {"count": len(rows),
            "wer": sum(r["word_errors"] for r in rows) / sum(r["words"] for r in rows),
            "cer": sum(r["char_errors"] for r in rows) / sum(r["chars"] for r in rows),
            "native_script_count": sum(r["native_script"] for r in rows),
            "gpu_transcribe_ms_mean_including_loading": statistics.mean(times),
            "gpu_transcribe_ms_p95_including_loading": times[math.ceil(.95 * len(times)) - 1],
            "rtf_including_loading": sum(times) / 1000 / sum(r["audio_seconds"] for r in rows),
            "peak_gpu_allocated_bytes": torch.cuda.max_memory_allocated(),
            "peak_process_ram_bytes": peak_memory_bytes()}, "rows": rows}


def freeze_layers(model, mode):
    layers = model.encoder.layers
    if len(layers) < 2 or mode not in ("freeze-lower", "full"):
        raise RuntimeError("Unexpected encoder layers or freeze mode")
    for param in model.parameters():
        param.requires_grad_(True)
    frozen = len(layers) // 2 if mode == "freeze-lower" else 0
    for layer in list(layers)[:frozen]:
        for param in layer.parameters():
            param.requires_grad_(False)
    return {"encoder_layers": len(layers), "frozen_encoder_layers": frozen,
            "trainable_parameters": sum(p.numel() for p in model.parameters() if p.requires_grad),
            "total_parameters": sum(p.numel() for p in model.parameters())}


def run_baseline(repo, report):
    import torch
    from huggingface_hub import hf_hub_download
    if not os.environ.get("HF_TOKEN"):
        raise RuntimeError("Enable HF_TOKEN in Kaggle Secrets and accept the Tamil model's access terms")
    checkpoint = Path(hf_hub_download(MODEL_REPO, MODEL_FILE, revision=MODEL_REVISION,
                                     cache_dir=str(Path(repo) / "model-cache")))
    report.update(checkpoint=str(checkpoint), checkpoint_bytes=checkpoint.stat().st_size,
                  checkpoint_sha256=digest(checkpoint), model_repo=MODEL_REPO, model_revision=MODEL_REVISION)
    model = restore(checkpoint, torch.device("cuda:0"))
    report["model_class"] = type(model).__name__
    manifest = Path(repo) / "tools/stt_models/five-language-validation/manifest-ta-30.json"
    records = json.loads(manifest.read_text(encoding="utf-8"))
    report["ctc"] = score(model, records, "ctc", repo)
    write_json(Path(repo) / "baseline-report.json", report)
    report["rnnt"] = score(model, records, "rnnt", repo)
    report["ctc_baseline_gate_passed"] = baseline_gate(report["ctc"]["summary"])
    report["reference_wer"] = REFERENCE_WER
    report["maximum_absolute_gap"] = BASELINE_TOLERANCE
    if not report["ctc_baseline_gate_passed"]:
        raise RuntimeError("Tamil native-script/CTC baseline differs from the recorded desktop baseline; review before training")
    report["status"] = "Tamil source CTC/RNNT baseline measured; pilot not run"


def run_pilot(repo, plan, report, mode, seed):
    import torch
    import pytorch_lightning as pl
    from pytorch_lightning.callbacks import EarlyStopping
    from pytorch_lightning.loggers import CSVLogger
    from omegaconf import OmegaConf
    from nemo.collections.asr.modules import SpectrogramAugmentation
    baseline = json.loads((Path(repo) / "baseline-report.json").read_text(encoding="utf-8"))
    if (not baseline.get("ctc_baseline_gate_passed") or not baseline_gate(baseline["ctc"]["summary"])
            or baseline.get("plan_sha256") != digest(Path(repo) / "pilot-plan.json")
            or baseline["model_revision"] != MODEL_REVISION
            or digest(baseline["checkpoint"]) != baseline["checkpoint_sha256"]):
        raise RuntimeError("A passing baseline from these exact prepared files/checkpoint is required")
    if seed not in SEEDS or mode not in plan["modes"]:
        raise RuntimeError("Only the bounded two-seed freeze/full pilot is supported")
    run_dir = Path(repo) / f"pilot-{mode}-{seed}"
    if (run_dir / "report.json").exists():
        raise RuntimeError("Run already has a report; preserve it instead of overwriting")
    run_dir.mkdir(exist_ok=True)
    # Apply the seed to Python, NumPy, Torch and data workers, not just a folder name.
    pl.seed_everything(seed, workers=True)
    report.update(seed=seed, mode=mode, max_steps=MAX_STEPS, plan_sha256=digest(Path(repo) / "pilot-plan.json"),
                  baseline_report_sha256=digest(Path(repo) / "baseline-report.json"))
    history = []

    class Evidence(pl.Callback):
        def on_train_batch_end(self, trainer, module, outputs, batch, batch_idx):
            loss = float(outputs["loss"].detach().float().cpu())
            if not math.isfinite(loss):
                raise RuntimeError("Non-finite training loss")
            history.append({"step": trainer.global_step, "loss": loss})
            report.update(status="Pilot running; final checks not passed", history=history,
                          global_step=trainer.global_step)
            write_json(run_dir / "report.json", report)

        def on_before_optimizer_step(self, trainer, module, optimizer):
            if any(not torch.isfinite(p.grad).all() for p in module.parameters() if p.grad is not None):
                raise RuntimeError("Non-finite gradients; review precision/runtime")

        def on_validation_end(self, trainer, module):
            if trainer.sanity_checking:
                return
            metric = trainer.callback_metrics.get("val_wer_ctc")
            if metric is None or not math.isfinite(float(metric)):
                raise RuntimeError("Missing/non-finite val_wer_ctc; cannot claim dev evaluation")
            history.append({"step": trainer.global_step, "dev_ctc_wer": float(metric)})
            write_json(run_dir / "report.json", report)

    # FP16 produced non-finite gradients before the first update in the reviewed pilot.
    report["training_precision"] = "32-true"
    trainer = pl.Trainer(accelerator="gpu", devices=1, precision=report["training_precision"],
                         max_steps=MAX_STEPS, max_epochs=10, accumulate_grad_batches=4,
                         gradient_clip_val=1.0, val_check_interval=40, check_val_every_n_epoch=None,
                         log_every_n_steps=1, num_sanity_val_steps=0, enable_checkpointing=False,
                         logger=CSVLogger(str(run_dir), name="metrics"),
                         callbacks=[Evidence(), EarlyStopping(monitor="val_wer_ctc", mode="min", patience=3,
                                                             check_finite=True, strict=True)])
    model = restore(baseline["checkpoint"], torch.device("cpu"), trainer=trainer)
    report["parameters"] = freeze_layers(model, mode)
    if not 0 < model.ctc_loss_weight < 1:
        raise RuntimeError("Source model must preserve both RNNT and CTC training losses")
    model.change_decoding_strategy(decoder_type="ctc", lang_id="ta")
    multi = "multisoftmax" in model.cfg.decoder
    for side in ("train", "dev"):
        cfg = {"manifest_filepath": plan["manifests"][side], "sample_rate": 16000, "batch_size": 1,
               "shuffle": side == "train", "num_workers": 0, "pin_memory": True,
               "min_duration": 1, "max_duration": 10, "trim_silence": False, "is_tarred": False,
               "return_sample_id": multi, "return_language_id": multi}
        if side == "train":
            cfg["augmentor"] = {"speed": {"prob": .5, "sr": 16000, "resample_type": "kaiser_fast",
                                          "min_speed_rate": .9, "max_speed_rate": 1.1, "num_rates": 3,
                                          "rng": seed}}
            model.setup_training_data(OmegaConf.create(cfg))
        else:
            model.setup_validation_data(OmegaConf.create(cfg))
    batch = next(iter(model.train_dataloader()))
    if multi and (len(batch) != 6 or any(lang != "ta" for lang in batch[-1])):
        raise RuntimeError("Actual dataloader did not return the six-field Tamil batch required by the fork")
    model.spec_augmentation = SpectrogramAugmentation(freq_masks=2, time_masks=2, freq_width=16, time_width=.05)
    model.setup_optimization(OmegaConf.create({"name": "adamw", "lr": 2e-5, "betas": [.9, .98],
                                              "weight_decay": .001,
                                              "sched": {"name": "CosineAnnealing", "warmup_steps": 2,
                                                        "min_lr": 1e-6, "max_steps": MAX_STEPS}}))
    def fingerprint():
        return hashlib.sha256(b"".join(p.detach().float().flatten()[:64].cpu().numpy().tobytes()
                              for p in model.parameters() if p.requires_grad)).hexdigest()
    before = fingerprint()
    OmegaConf.save(model.cfg, run_dir / "effective-model-config.yaml")
    report["ctc_loss_weight"] = float(model.ctc_loss_weight)
    report["actual_batch_fields"] = len(batch)
    del batch
    torch.cuda.reset_peak_memory_stats()
    start = time.perf_counter()
    try:
        trainer.fit(model)
        trainer.validate(model, verbose=False)
    finally:
        report["history"] = history
        report["elapsed_seconds"] = time.perf_counter() - start
        report["global_step"] = trainer.global_step
        report["peak_gpu_allocated_bytes"] = torch.cuda.max_memory_allocated()
        report["peak_gpu_reserved_bytes"] = torch.cuda.max_memory_reserved()
    report["weights_changed"] = before != fingerprint()
    if not report["weights_changed"] or trainer.global_step <= 0 or not any("dev_ctc_wer" in r for r in history):
        raise RuntimeError("Pilot did not produce actual weight updates and measured dev CTC WER")
    # Pilot weights are deliberately not exported or promoted. Keep a resumable
    # checkpoint in private output; this is a final pilot snapshot, not a best model.
    trainer.save_checkpoint(str(run_dir / "last-pilot.ckpt"))
    sys.path.insert(0, str(Path(repo) / "tools"))
    from validate_stt import peak_memory_bytes
    report["peak_process_ram_bytes"] = peak_memory_bytes()
    report["status"] = "Bounded pilot executed; full trial and held-out comparison not run"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("baseline", "pilot"))
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--mode", choices=("freeze-lower", "full"))
    parser.add_argument("--seed", type=int, choices=SEEDS)
    args = parser.parse_args()
    if args.stage == "pilot" and (args.mode is None or args.seed is None):
        parser.error("Pilot requires --mode and --seed")
    output = (args.repo / "baseline-report.json" if args.stage == "baseline"
              else args.repo / f"pilot-{args.mode}-{args.seed}/report.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise RuntimeError("Preserve the existing report; use a new notebook session for a fresh run")
    report = {"status": "Not run", "language": "ta", "stage": args.stage,
              "full_trial": "Not run", "heldout_after_training": "Not run", "export": "Not run",
              "phone_accuracy": "Not verified", "app_promotion": "Not done"}
    try:
        plan = verify_plan(args.repo)
        report["plan_sha256"] = digest(args.repo / "pilot-plan.json")
        report["runtime"] = runtime()
        if args.stage == "baseline":
            run_baseline(args.repo, report)
        else:
            run_pilot(args.repo, plan, report, args.mode, args.seed)
    except Exception as exc:
        report["status"] = "Failed; stop and review report"
        # Avoid logging arbitrary library errors that might include a credential.
        message = str(exc).replace(os.environ.get("HF_TOKEN") or "<no-token>", "[REDACTED]")
        report["error"] = {"type": type(exc).__name__, "message": message[:2000]}
        report["error"]["traceback"] = traceback.format_exc().replace(
            os.environ.get("HF_TOKEN") or "<no-token>", "[REDACTED]")[-12000:]
        raise RuntimeError(report["error"]["message"]) from None
    finally:
        write_json(output, report)
        print("Report saved:", output, flush=True)


if __name__ == "__main__":
    main()
