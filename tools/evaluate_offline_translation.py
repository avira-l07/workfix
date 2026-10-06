"""Run genuine local IndicTrans2 inference; inputs and outputs stay in the report.

Requires ctranslate2, sentencepiece, indic-nlp-library and sacremoses.
No phrase lookup, expected-answer substitution or per-example correction is used.
"""
import argparse
import json
import time
from pathlib import Path

import ctranslate2
import sentencepiece
from indicnlp.normalize.indic_normalize import IndicNormalizerFactory
from indicnlp.tokenize import indic_tokenize, indic_detokenize
from indicnlp.transliterate.unicode_transliterate import UnicodeIndicTransliterator
from sacremoses import MosesTokenizer, MosesDetokenizer, MosesPunctNormalizer

TAGS = {"hi": "hin_Deva", "en": "eng_Latn", "gu": "guj_Gujr"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--indic-en", type=Path, required=True)
    parser.add_argument("--en-indic", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = json.loads(args.input.read_text(encoding="utf-8"))
    models = {}
    for direction, directory in (("indic-en", args.indic_en), ("en-indic", args.en_indic)):
        models[direction] = (
            ctranslate2.Translator(str(directory), device="cpu", compute_type="int8",
                                  inter_threads=1, intra_threads=4),
            sentencepiece.SentencePieceProcessor(model_file=str(directory / "vocab/model.SRC")),
            sentencepiece.SentencePieceProcessor(model_file=str(directory / "vocab/model.TGT")),
        )
    normalizers = {lang: IndicNormalizerFactory().get_normalizer(lang) for lang in TAGS if lang != "en"}
    en_tok, en_detok, en_norm = MosesTokenizer("en"), MosesDetokenizer("en"), MosesPunctNormalizer()

    def translate(text, source, target):
        direction = "en-indic" if source == "en" else "indic-en"
        translator, src_sp, tgt_sp = models[direction]
        if source == "en":
            prepared = " ".join(en_tok.tokenize(en_norm.normalize(text.strip()), escape=False))
        else:
            prepared = " ".join(indic_tokenize.trivial_tokenize(normalizers[source].normalize(text.strip()), source))
            prepared = UnicodeIndicTransliterator.transliterate(prepared, source, "hi").replace(" ् ", "्")
        tokens = [TAGS[source], TAGS[target], *src_sp.encode(prepared, out_type=str)]
        result = translator.translate_batch([tokens], beam_size=5, max_decoding_length=256,
                                            max_input_length=0)[0]
        output = tgt_sp.decode(result.hypotheses[0])
        if target == "en":
            return en_detok.detokenize(output.split())
        return indic_detokenize.trivial_detokenize(
            UnicodeIndicTransliterator.transliterate(output, "hi", target), target)

    report = {"runtime": f"ctranslate2 {ctranslate2.__version__}, CPU INT8",
              "model_directories": {"indic-en": str(args.indic_en), "en-indic": str(args.en_indic)},
              "canned_answers": False, "human_quality_review": "Required", "results": []}
    for case in cases:
        start = time.perf_counter()
        text, source, target = case["text"], case["source"], case["target"]
        pivot = None
        if source == target:
            output = text
        elif source != "en" and target != "en":
            pivot = translate(text, source, "en")
            output = translate(pivot, "en", target)
        else:
            output = translate(text, source, target)
        row = {**case, "output": output, "pivot_english": pivot,
               "latency_seconds": round(time.perf_counter() - start, 3)}
        report["results"].append(row)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(row, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
