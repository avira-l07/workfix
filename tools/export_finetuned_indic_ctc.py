"""Export a TRAINED AI4Bharat hybrid checkpoint's CTC head for sherpa-onnx.

Requires AI4Bharat NeMo (nemo-v2), torch, onnx, onnxruntime and sherpa-onnx.
This has not been run on this CPU-only host. Never overwrite the shipped pack:
  python tools/export_finetuned_indic_ctc.py --language ta \
    --checkpoint /outside-repo/best.nemo --trace-wav /train-or-dev/sample.wav \
    --output-dir /outside-repo/ta-candidate
"""

import argparse
import hashlib
import json
from pathlib import Path


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def export(language, checkpoint, trace_wav, output_dir):
    import nemo.collections.asr as nemo_asr
    import numpy as np
    import onnx
    import onnxruntime as ort
    import sherpa_onnx
    import soundfile as sf
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from onnxruntime.quantization.shape_inference import quant_pre_process

    output_dir.mkdir(parents=True, exist_ok=True)
    if any(output_dir.iterdir()):
        raise RuntimeError("Candidate export directory must be empty; preserve prior results")
    audio, rate = sf.read(trace_wav, dtype="float32")
    if rate != 16000 or audio.ndim != 1:
        raise RuntimeError("Trace audio must be a TRAIN/DEV 16-kHz mono WAV, never held-out test audio")
    model = nemo_asr.models.ASRModel.restore_from(str(checkpoint))
    model.cur_decoder = "ctc"
    model.set_export_config({"decoder_type": "ctc"})
    model.eval().cpu()
    tokenizer = model.tokenizer
    offset = tokenizer.token_id_offset[language]
    vocab = tokenizer.vocab
    blank_id = len(vocab)
    lang_size = tokenizer.tokenizers_dict[language].vocab_size
    if offset + lang_size > blank_id:
        raise RuntimeError("Language token range exceeds shared vocabulary")

    class MaskedCTC(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.model = model
            mask = torch.full((blank_id + 1,), float("-inf"))
            mask[offset:offset + lang_size] = 0
            mask[blank_id] = 0
            self.register_buffer("mask", mask)

        def forward(self, audio_signal, length):
            return self.model.forward_for_export(input=audio_signal, length=length) + self.mask

    with torch.inference_mode():
        samples = torch.from_numpy(np.asarray(audio)).unsqueeze(0)
        signal, length = model.preprocessor(input_signal=samples,
                                            length=torch.tensor([samples.shape[1]]))
        fp32 = output_dir / "model.fp32.onnx"
        torch.onnx.export(MaskedCTC().eval(), (signal, length), str(fp32),
                          input_names=["audio_signal", "length"],
                          output_names=["logprobs"],
                          dynamic_axes={"audio_signal": {0: "batch", 2: "time"},
                                        "length": {0: "batch"},
                                        "logprobs": {0: "batch", 1: "time"}},
                          opset_version=16, dynamo=False)
    onnx.checker.check_model(str(fp32))
    preprocessed = output_dir / "model.preprocessed.onnx"
    int8 = output_dir / "model.int8.onnx"
    quant_pre_process(input_model=str(fp32), output_model_path=str(preprocessed))
    quantize_dynamic(model_input=str(preprocessed), model_output=str(int8),
                     per_channel=True, weight_type=QuantType.QUInt8,
                     op_types_to_quantize=["MatMul"])
    metadata = {"vocab_size": str(blank_id + 1), "normalize_type": "per_feature",
                "subsampling_factor": "4", "model_type": "EncDecCTCModelBPE",
                "version": "1", "model_author": "ai4bharat",
                "comment": f"iTantra candidate {language}; CTC masked; MatMul INT8"}
    for exported in (fp32, int8):
        graph = onnx.load(str(exported))
        onnx.helper.set_model_props(graph, metadata)
        onnx.save(graph, str(exported))
        onnx.checker.check_model(str(exported))
    tokens = output_dir / "tokens.txt"
    tokens.write_text("".join(f"{token} {i}\n" for i, token in enumerate(vocab)) +
                      f"<blk> {blank_id}\n", encoding="utf-8")
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(int8), tokens=str(tokens), num_threads=2,
        decoding_method="greedy_search")
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
    recognizer.decode_stream(stream)
    if not stream.result.text.strip():
        raise RuntimeError("INT8 sherpa smoke decode was empty")
    report = {"language": language, "checkpoint_sha256": sha256(checkpoint),
              "files": {name: {"bytes": path.stat().st_size, "sha256": sha256(path)}
                        for name, path in (("model.fp32.onnx", fp32),
                                           ("model.int8.onnx", int8), ("tokens.txt", tokens))},
              "sherpa_smoke_text": stream.result.text}
    (output_dir / "export-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=("ta", "te", "bn", "gu"), required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--trace-wav", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    export(args.language, args.checkpoint, args.trace_wav, args.output_dir)
