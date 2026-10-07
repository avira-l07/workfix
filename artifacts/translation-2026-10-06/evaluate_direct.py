"""Evaluate the pinned ONNX candidate using the exporter's actual inference helper."""
import importlib.util
import importlib
import json
import sys
import time
import types
from pathlib import Path

root = Path(__file__).resolve().parent
# Load the actual inference processor directly. Toolkit's package initializer also
# imports an unused training collator tied to transformers 4; this evaluator does
# not need that collator or a PyTorch runtime.
package = types.ModuleType("IndicTransToolkit")
package.__path__ = [str(root / "python/IndicTransToolkit")]
sys.modules["IndicTransToolkit"] = package
package.IndicProcessor = importlib.import_module("IndicTransToolkit.processor").IndicProcessor
spec = importlib.util.spec_from_file_location("direct_reference", root / "direct-translate-reference.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
model = module.IndicTransONNX(root / "direct-int8")
tags = {"hi": "hin_Deva", "gu": "guj_Gujr"}
report = {"model": "IndicTrans2 Indic-Indic 320M, ONNX INT8",
          "model_files": json.loads((root / "direct-model-verification.json").read_text()),
          "canned_answers": False, "human_quality_review": "Required", "results": []}
for case in json.loads((root / "cases.json").read_text(encoding="utf-8")):
    if case["source"] not in tags or case["target"] not in tags:
        continue
    start = time.perf_counter()
    output = model.translate(case["text"], tags[case["source"]], tags[case["target"]])
    row = {**case, "output": output, "latency_seconds": round(time.perf_counter() - start, 3)}
    report["results"].append(row)
    (root / "direct-model-results.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(row, ensure_ascii=False), flush=True)
