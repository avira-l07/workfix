"""CPU-only safety checks; none of these are speech/model training results."""

import json
import ast
import math
import os
import shutil
import subprocess
import sys
import tempfile
import wave
import zipfile
from pathlib import Path
from unittest.mock import patch
from contextlib import nullcontext, chdir
from types import SimpleNamespace, ModuleType

from kathbath_gate_b_data import prepare
from kathbath_tamil_pilot import (BASELINE_TOLERANCE, REFERENCE_WER, baseline_gate,
                                 evaluation_wav_duration, extract_text, freeze_layers, make_plan, subset, verify_plan, restore)
from test_kathbath_gate_b_data import fixture, rejects


def check_restore_transcribe_config():
    # Exercise the actual pinned NeMo loader without importing its GPU stack.
    path = Path(__file__).parent / 'stt_results/tamil-gate-b-preparation-2026-10-04/nemo__collections__asr__models__hybrid_rnnt_ctc_bpe_models.py'
    tree = ast.parse(path.read_text(encoding='utf-8'))
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'EncDecHybridRNNTCTCBPEModel')
    method = next(n for n in cls.body if isinstance(n, ast.FunctionDef) and n.name == '_setup_transcribe_dataloader')
    method.returns = None
    for arg in method.args.args:
        arg.annotation = None
    scope = {'os': os, 'DictConfig': lambda x: x}
    exec(compile(ast.Module(body=[method], type_ignores=[]), str(path), 'exec'), scope)
    models = ModuleType('nemo.collections.asr.models')
    omega = ModuleType('omegaconf')
    omega.open_dict = lambda config: nullcontext()
    for original in ({'use_start_end_token': True}, None):
        config = SimpleNamespace(train_ds={'manifest_filepath': '/stale/train'}, validation_ds=original,
                                 test_ds={'manifest_filepath': '/stale/test'},
                                 preprocessor=SimpleNamespace(sample_rate=16000))
        model = SimpleNamespace(cfg=config, preprocessor=SimpleNamespace(_sample_rate=16000),
                                _setup_dataloader_from_config=lambda config: config)
        class FakeASR:
            @staticmethod
            def restore_from(checkpoint, **kwargs):
                if kwargs.get('return_config'):
                    return config
                assert kwargs['override_config_path'] is config
                assert kwargs['strict'] is True
                return model
        models.ASRModel = FakeASR
        with patch.dict(sys.modules, {'omegaconf': omega, 'nemo.collections.asr.models': models}):
            actual = restore('unused.nemo', 'cpu')
        assert actual.cfg.train_ds is None and actual.cfg.test_ds is None
        assert actual.cfg.validation_ds['manifest_filepath'] is None
        assert actual.cfg.validation_ds['defer_setup'] is True
        loader = scope['_setup_transcribe_dataloader'](actual, {'manifest_filepath': '/temporary/transcribe.json', 'batch_size': 1})
        assert loader['use_start_end_token'] is (original is not None)
        assert loader['manifest_filepath'] == '/temporary/transcribe.json'


def check_cuda_binding_recovery():
    recovery = Path(__file__).with_name('kathbath_tamil_cuda_binding_recovery.txt').read_text(encoding='utf-8')
    original_cwd, original_env = Path.cwd(), dict(os.environ)
    try:
        with tempfile.TemporaryDirectory(prefix='itantra-binding-recovery-') as directory:
            work = Path(directory)
            (work / 'logs').mkdir()
            (work / 'pilot-freeze-lower-17').mkdir()
            baseline = work / 'baseline-report.json'
            baseline.write_text('{"ctc_baseline_gate_passed": true}')
            failed = work / 'logs/pilot-freeze-lower-17.log'
            failed.write_text('Fatal Python error: Segmentation fault\nnumba/cuda/cudadrv/driver.py\nas_cuda_array\nreturncode=-11')
            before = {p: p.read_bytes() for p in (baseline, failed)}
            calls, packed = [], []
            def command(args, name):
                calls.append((list(map(str, args)), name))
                assert os.environ['NUMBA_CUDA_USE_NVIDIA_BINDING'] == '1'
                if name == 'rnnt-cuda-check':
                    raise RuntimeError('Synthetic CUDA smoke-test failure')
            def stage(name, action):
                try:
                    action()
                finally:
                    packed.append(name)
            namespace = dict(work=work, train_python=sys.executable, summary={}, command=command,
                             stage=stage, pack_reports=lambda: None)
            rejects(lambda: exec(recovery, namespace), 'Synthetic CUDA')
            assert len(calls) == 3 and calls[0][0][-1] == 'cuda-python==12.4.0'
            assert not any('pilot' in args or 'baseline' in args for args, _ in calls)
            assert packed == ['nvidia-cuda-binding-recovery']
            assert all(p.read_bytes() == content for p, content in before.items())
            probe = (work / 'kathbath_rnnt_cuda_check.py').read_text(encoding='utf-8')
            compile(probe, 'cuda-smoke-probe', 'exec')
            runner = Path(__file__).with_name('kathbath_tamil_pilot.py').read_text(encoding='utf-8')
            func = next(n for n in ast.parse(runner).body if isinstance(n, ast.FunctionDef) and n.name == 'rnnt_cuda_preflight')
            assert ast.get_source_segment(runner, func) in probe
            calls.clear()
            def passing_command(args, name):
                calls.append((list(map(str, args)), name))
                if name == 'rnnt-cuda-check':
                    (work / 'rnnt-cuda-check-report.json').write_text('{"status":"synthetic pass"}')
            namespace['command'] = passing_command
            exec(recovery, namespace)
            training = [args for args, _ in calls if 'pilot' in args]
            assert len(training) == 1 and training[0][-4:] == ['--mode', 'freeze-lower', '--seed', '17']
            assert all(p.read_bytes() == content for p, content in before.items())
            os.chdir(original_cwd)
    finally:
        os.chdir(original_cwd)
        os.environ.clear()
        os.environ.update(original_env)


def check_fp32_recovery():
    cell = Path(__file__).with_name('kathbath_tamil_fp32_recovery.txt').read_text(encoding='utf-8')
    original_cwd, original_env = Path.cwd(), dict(os.environ)
    try:
        with tempfile.TemporaryDirectory(prefix='itantra-fp32-recovery-') as directory:
            work = Path(directory).resolve()
            source = Path(__file__).with_name('kathbath_tamil_pilot.py').read_text(encoding='utf-8')
            nodes = ast.parse(cell).body
            values = {n.targets[0].id: ast.literal_eval(n.value) for n in nodes
                      if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name)
                      and n.targets[0].id in ('old', 'new')}
            old_runner = source.replace(values['new'], values['old'])
            runner = work / 'kathbath_tamil_pilot.py'
            runner.write_text(old_runner, encoding='utf-8')
            run = work / 'pilot-freeze-lower-17'
            run.mkdir()
            (work / 'logs').mkdir()
            report = run / 'report.json'
            failure = {'status': 'Failed; stop and review report', 'error': {'message': 'Non-finite gradients; review precision/runtime'},
                       'global_step': 0, 'seed': 17, 'mode': 'freeze-lower', 'runtime': {'precision': '16-mixed'}}
            baseline = work / 'baseline-report.json'
            baseline.write_text('{"ctc_baseline_gate_passed": true}')
            (work / 'rnnt-cuda-check-report.json').write_text(json.dumps(dict(
                status='Tiny RNNT CUDA forward/backward passed; pilot not run',
                numba_cuda_use_nvidia_binding=True, cuda_python='12.4.0')))
            report.write_text(json.dumps(dict(failure, status='Bounded pilot executed; full trial and held-out comparison not run')))
            before = report.read_bytes(), baseline.read_bytes(), runner.read_bytes()
            calls, packed = [], []
            namespace = dict(work=work, train_python=sys.executable, summary={}, pack_reports=lambda: packed.append(1),
                             command=lambda args, name: calls.append((args, name)), stage=lambda name, action: action())
            try:
                exec(cell, namespace)
            except AssertionError as error:
                assert 'Preserve the existing result' in str(error)
            else:
                raise AssertionError('FP32 recovery must refuse successful results')
            assert (report.read_bytes(), baseline.read_bytes(), runner.read_bytes()) == before
            report.write_text(json.dumps(failure))
            before_failure = report.read_bytes()
            with patch('shutil.disk_usage', return_value=SimpleNamespace(free=20 * 1024**3)):
                exec(cell, namespace)
            assert (work / 'pilot-freeze-lower-17-before-fp32-report.json').read_bytes() == before_failure
            assert (work / 'kathbath_tamil_pilot_before_fp32.py').read_text(encoding='utf-8') == old_runner
            assert runner.read_text(encoding='utf-8') == source and baseline.read_bytes() == before[1]
            assert len(calls) == 1 and calls[0][0][-4:] == ['--mode', 'freeze-lower', '--seed', '17']
            assert calls[0][1] == 'pilot-freeze-lower-17-fp32' and packed
            try:
                exec(cell, namespace)
            except AssertionError as error:
                assert 'already prepared' in str(error)
            else:
                raise AssertionError('FP32 recovery must not overwrite an earlier attempt')
            os.chdir(original_cwd)
    finally:
        os.chdir(original_cwd)
        os.environ.clear()
        os.environ.update(original_env)


def check_continue_fp32():
    cell = Path(__file__).with_name('kathbath_tamil_continue_fp32.txt').read_text(encoding='utf-8')
    original_cwd, original_env = Path.cwd(), dict(os.environ)
    try:
        with tempfile.TemporaryDirectory(prefix='itantra-continue-fp32-') as directory:
            work = Path(directory).resolve()
            (work / 'pilot-plan.json').write_text('{}')
            (work / 'baseline-report.json').write_text('{}')
            from kathbath_gate_b_data import digest
            def result(mode, seed):
                return dict(status='Bounded pilot executed; full trial and held-out comparison not run',
                            language='ta', mode=mode, seed=seed, max_steps=20, training_precision='32-true',
                            global_step=20, weights_changed=True, history=[{'loss': 1.0}, {'dev_ctc_wer': .25}],
                            plan_sha256=digest(work / 'pilot-plan.json'),
                            baseline_report_sha256=digest(work / 'baseline-report.json'),
                            peak_gpu_allocated_bytes=100, elapsed_seconds=1.0)
            def write_result(mode, seed):
                directory = work / f'pilot-{mode}-{seed}'
                directory.mkdir(exist_ok=True)
                (directory / 'report.json').write_text(json.dumps(result(mode, seed)))
                (directory / 'last-pilot.ckpt').write_bytes(b'synthetic checkpoint')
            write_result('freeze-lower', 17)
            first = work / 'pilot-freeze-lower-17/report.json'
            before = first.read_bytes()
            calls = []
            def command(args, name):
                mode, seed = args[args.index('--mode') + 1], int(args[args.index('--seed') + 1])
                calls.append((mode, seed))
                assert os.environ['NUMBA_CUDA_USE_NVIDIA_BINDING'] == '1'
                write_result(mode, seed)
            namespace = dict(work=work, train_python=sys.executable, summary={'error': {'message': 'old attempt'}},
                             command=command, stage=lambda name, action: action(), pack_reports=lambda: None)
            with patch('shutil.disk_usage', return_value=SimpleNamespace(free=20 * 1024**3)):
                exec(cell, namespace)
                assert calls == [('freeze-lower', 29), ('full', 17), ('full', 29)]
                assert first.read_bytes() == before
                assert len(json.loads((work / 'pilot-comparison-report.json').read_text())['runs']) == 4
                assert namespace['summary']['previous_attempt_errors'][0]['error']['message'] == 'old attempt'
                calls.clear()
                exec(cell, namespace)
                assert not calls and first.read_bytes() == before
                bad = work / 'pilot-freeze-lower-29/report.json'
                failed = dict(result('freeze-lower', 29), status='Failed; stop and review report')
                bad.write_text(json.dumps(failed))
                preserved = bad.read_bytes()
                rejects(lambda: exec(cell, namespace), 'not a verified completed FP32 pilot')
                assert not calls and bad.read_bytes() == preserved and first.read_bytes() == before
            os.chdir(original_cwd)
    finally:
        os.chdir(original_cwd)
        os.environ.clear()
        os.environ.update(original_env)


def check_same_dev_baseline():
    cell = Path(__file__).with_name('kathbath_tamil_same_dev_baseline.txt').read_text(encoding='utf-8')
    tree = ast.parse(cell)
    script = next(ast.literal_eval(n.args[0]) for n in ast.walk(tree)
                  if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == 'write_text')
    assert '.fit(' not in script and '.backward(' not in script and 'optimizer' not in script
    original_cwd = Path.cwd()
    try:
        with tempfile.TemporaryDirectory(prefix='itantra-same-dev-') as directory, chdir(original_cwd):
            root = Path(directory).resolve()
            (root / 'source.nemo').write_bytes(b'original source checkpoint')
            (root / 'pilot-plan.json').write_text('{}')
            from kathbath_gate_b_data import digest
            baseline = dict(ctc_baseline_gate_passed=True, checkpoint=str(root / 'source.nemo'),
                            checkpoint_sha256=digest(root / 'source.nemo'))
            (root / 'baseline-report.json').write_text(json.dumps(baseline))
            before = {}
            for mode in ('freeze-lower', 'full'):
                for seed in (17, 29):
                    path = root / f'pilot-{mode}-{seed}/report.json'
                    path.parent.mkdir()
                    path.write_text(json.dumps(dict(status='Bounded pilot executed; full trial and held-out comparison not run',
                        weights_changed=True, global_step=20, training_precision='32-true',
                        history=[{'dev_ctc_wer': .35}], plan_sha256=digest(root / 'pilot-plan.json'),
                        baseline_report_sha256=digest(root / 'baseline-report.json'))))
                    before[path] = path.read_bytes()
            model = SimpleNamespace(cfg=SimpleNamespace(decoder={'multisoftmax': {}}),
                                    change_decoding_strategy=lambda **kwargs: None)
            configs = []
            model.setup_validation_data = configs.append
            torch = ModuleType('torch')
            torch.device = lambda device: device
            pl = ModuleType('pytorch_lightning')
            pl.seed_everything = lambda *args, **kwargs: None
            class Trainer:
                def __init__(self, **kwargs):
                    assert kwargs['precision'] == '32-true' and kwargs['devices'] == 1
                def validate(self, actual, **kwargs):
                    assert actual is model
                    return [{'val_wer_ctc': .30}]
                def fit(self, *args, **kwargs):
                    raise AssertionError('This check must never train')
            pl.Trainer = Trainer
            omega = ModuleType('omegaconf')
            omega.OmegaConf = SimpleNamespace(create=lambda cfg: cfg)
            import kathbath_tamil_pilot as pilot
            os.chdir(root)
            with patch.dict(sys.modules, {'torch': torch, 'pytorch_lightning': pl, 'omegaconf': omega}), \
                 patch.object(pilot, 'verify_plan', return_value={'counts': {'dev': 64}, 'manifests': {'dev': '/same-dev.jsonl'}}) as seal, \
                 patch.object(pilot, 'runtime', return_value={'gpu': 'synthetic'}), \
                 patch.object(pilot, 'restore', return_value=model) as restored:
                exec(script, {})
                seal.assert_called_once_with(root)
                assert restored.call_args.args[0] == str(root / 'source.nemo')
            assert configs[0]['manifest_filepath'] == '/same-dev.jsonl' and configs[0]['shuffle'] is False
            assert configs[0]['return_language_id'] is True and configs[0]['batch_size'] == 1
            result = json.loads((root / 'same-dev-baseline-report.json').read_text())
            assert result['count'] == 64 and result['source_dev_ctc_wer'] == .30
            assert all(math.isclose(row['change_from_source_percentage_points'], 5) for row in result['comparisons'])
            assert all(p.read_bytes() == content for p, content in before.items())
            namespace = dict(work=root, train_python=sys.executable, command=lambda *args: None,
                             stage=lambda *args: None, pack_reports=lambda: None, summary={})
            try:
                exec(cell, namespace)
            except AssertionError as error:
                assert 'already has a report' in str(error)
            else:
                raise AssertionError('Do not overwrite a same-dev baseline report')
            os.chdir(original_cwd)
    finally:
        os.chdir(original_cwd)


def check_validation_config_recovery():
    root_source = Path(__file__).parent
    current = (root_source / 'kathbath_tamil_pilot.py').read_text(encoding='utf-8')
    recovery = (root_source / 'kathbath_tamil_validation_config_recovery.txt').read_text(encoding='utf-8')
    parsed = ast.parse(recovery)
    literals = {n.targets[0].id: ast.literal_eval(n.value) for n in parsed.body
                if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name) and n.targets[0].id in ('old', 'new')}
    old = current.replace(literals['new'], literals['old']).replace('import traceback\n', '')
    old = old.replace('        report["error"]["traceback"] = traceback.format_exc().replace(\n'
                      '            os.environ.get("HF_TOKEN") or "<no-token>", "[REDACTED]")[-12000:]\n', '')
    with tempfile.TemporaryDirectory(prefix='itantra-config-recovery-') as tmp:
        root = Path(tmp)
        runner = root / 'kathbath_tamil_pilot.py'
        runner.write_text(old, encoding='utf-8')
        report = root / 'baseline-report.json'
        report.write_text(json.dumps({'status': 'Failed; stop and review report',
                                      'error': {'message': "'NoneType' object has no attribute 'get'"}}))
        failure = report.read_bytes()
        namespace = {'work': root, 'train_python': sys.executable, 'pilot': object(),
                     'summary': {'error': 'old failure'}, 'pack_reports': lambda: None}
        with patch('importlib.reload', side_effect=lambda module: module):
            exec(recovery, namespace)
            assert runner.read_text(encoding='utf-8') == current
            assert not report.exists()
            assert (root / 'baseline-before-validation-config-fix-report.json').read_bytes() == failure
            exec(recovery, namespace)
            report.write_text('{"status": "Successful baseline"}')
            try:
                exec(recovery, namespace)
            except AssertionError as error:
                assert 'different result' in str(error)
            else:
                raise AssertionError('Recovery must preserve a successful baseline')
            assert report.read_text() == '{"status": "Successful baseline"}'


def check_recovery_cell():
    import numpy as np
    import soundfile as sf
    root_source = Path(__file__).parent
    recovery = (root_source / "kathbath_tamil_float_wav_recovery.txt").read_text(encoding="utf-8")
    current = (root_source / "kathbath_tamil_pilot.py").read_text(encoding="utf-8")
    tree = ast.parse(current)
    node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "evaluation_wav_duration")
    old = current.replace(ast.get_source_segment(current, node) + "\n\n\n", "")
    old = old.replace('duration = evaluation_wav_duration(row["file"])', 'duration = wav_duration(row["file"])')
    old = old.replace("from kathbath_gate_b_data import digest\n", "from kathbath_gate_b_data import digest, wav_duration\n")
    assert "def evaluation_wav_duration" not in old
    with tempfile.TemporaryDirectory(prefix="itantra-float32-recovery-") as tmp:
        root = Path(tmp)
        runner = root / "kathbath_tamil_pilot.py"
        runner.write_text(old, encoding="utf-8")
        shutil.copyfile(root_source / "kathbath_gate_b_data.py", root / "kathbath_gate_b_data.py")
        audio = root / "evaluation.wav"
        sf.write(audio, np.ones(1600, dtype=np.float32) * .1, 16000, subtype="FLOAT")
        from kathbath_gate_b_data import digest
        manifest = root / "tools/stt_models/five-language-validation/manifest-ta-30.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps([{"file": str(audio), "sha256": digest(audio)}] * 30), encoding="utf-8")
        failed_path = root / "baseline-report.json"
        failed_path.write_text(json.dumps({"status": "Failed; stop and review report",
                                          "error": {"message": "unknown format: 3"}}), encoding="utf-8")
        failed_bytes = failed_path.read_bytes()
        log = root / "logs/source-baseline.log"
        log.parent.mkdir()
        log.write_text("unknown format: 3\n", encoding="utf-8")
        def command(args, name):
            env = dict(os.environ, PYTHONPATH=str(root))
            subprocess.run(list(map(str, args)), cwd=root, env=env, check=True, capture_output=True, text=True)
        namespace = {"work": root, "train_python": Path(sys.executable), "command": command,
                     "pilot": object(), "summary": {"error": "previous failure"}, "pack_reports": lambda: None}
        with patch("importlib.reload", side_effect=lambda module: module):
            exec(recovery, namespace)
            assert runner.read_text(encoding="utf-8") == current
            assert (root / "baseline-before-float32-fix-report.json").read_bytes() == failed_bytes
            assert not failed_path.exists()
            assert (root / "logs/source-baseline-before-float32-fix.log").read_bytes() == log.read_bytes()
            assert "error" not in namespace["summary"]
            exec(recovery, namespace)  # Repeating a completed patch changes no source/audio.
            assert runner.read_text(encoding="utf-8") == current
            failed_path.write_text('{"status": "Successful baseline"}', encoding="utf-8")
            try:
                exec(recovery, namespace)
            except AssertionError as error:
                assert "different baseline" in str(error)
            else:
                raise AssertionError("Recovery must preserve successful results")
            assert json.loads(failed_path.read_text())["status"] == "Successful baseline"


def main():
    check_restore_transcribe_config()
    check_validation_config_recovery()
    import numpy as np
    import soundfile as sf
    with tempfile.TemporaryDirectory(prefix="itantra-float32-eval-") as tmp:
        root = Path(tmp)
        samples = np.linspace(-.25, .25, 32000, dtype=np.float32)
        for subtype in ("FLOAT", "PCM_16"):
            path = root / f"{subtype}.wav"
            sf.write(path, samples, 16000, subtype=subtype)
            if subtype == "FLOAT":
                try:
                    with wave.open(str(path), "rb"):
                        pass
                except wave.Error as exc:
                    assert "unknown format: 3" in str(exc)
                else:
                    raise AssertionError("Expected the original PCM-only reader to reject format 3")
            before = path.read_bytes()
            assert evaluation_wav_duration(path) == 2
            assert path.read_bytes() == before
        for name, audio, rate in (("stereo", np.stack([samples, samples], axis=1), 16000),
                                  ("wrong-rate", samples, 8000), ("empty", samples[:0], 16000),
                                  ("non-finite", np.array([float("nan")], dtype=np.float32), 16000)):
            path = root / f"{name}.wav"
            sf.write(path, audio, rate, subtype="FLOAT")
            rejects(lambda: evaluation_wav_duration(path), "evaluation WAV")
    # Recheck the exact hashed desktop benchmark audio, not synthetic speech.
    manifest = Path(__file__).parent / "stt_models/five-language-validation/manifest-ta-30.json"
    if manifest.exists():
        from kathbath_gate_b_data import contained_file, digest
        for row in json.loads(manifest.read_text(encoding="utf-8")):
            path = contained_file(Path(__file__).resolve().parents[1], row["file"])
            assert digest(path) == row["sha256"]
            assert evaluation_wav_duration(path) > 0

    rows = [{"duration": 2, "speaker_group": f"speaker-{i % 8}", "source_id": str(i)} for i in range(160)]
    selected = subset(rows + [{"duration": 15, "speaker_group": "long", "source_id": "excluded"}], 16)
    assert len(selected) == 16 and len({r["speaker_group"] for r in selected}) == 8
    assert selected == subset(rows, 16) and all(r["source_id"] != "excluded" for r in selected)
    rejects(lambda: subset(rows[:3], 4), "eligible")
    assert extract_text((["வணக்கம்"], None)) == "வணக்கம்"
    rejects(lambda: extract_text(["one", "two"]), "structure")
    good = {"count": 30, "native_script_count": 30, "wer": REFERENCE_WER}
    assert baseline_gate(good)
    for change in ({"count": 29}, {"native_script_count": 29}, {"wer": float("nan")},
                   {"wer": REFERENCE_WER + BASELINE_TOLERANCE + .001}):
        assert not baseline_gate(dict(good, **change))

    class Parameter:
        requires_grad = True
        def requires_grad_(self, value):
            self.requires_grad = value
        def numel(self):
            return 10
    class Layer:
        def __init__(self):
            self.param = Parameter()
        def parameters(self):
            return [self.param]
    class Model:
        def __init__(self):
            self.encoder = type("Encoder", (), {"layers": [Layer() for _ in range(4)]})()
            self.head = Parameter()
        def parameters(self):
            return [layer.param for layer in self.encoder.layers] + [self.head]
    model = Model()
    counts = freeze_layers(model, "freeze-lower")
    assert counts["frozen_encoder_layers"] == 2 and counts["trainable_parameters"] == 30
    assert not model.encoder.layers[0].param.requires_grad and model.head.requires_grad
    assert freeze_layers(model, "full")["trainable_parameters"] == 50

    with tempfile.TemporaryDirectory(prefix="itantra-pilot-check-") as tmp:
        root = Path(tmp)
        saved, report_sha = fixture(root)
        repo = root / "working"
        data_report = prepare(saved, repo, reviewed_sha=report_sha)
        # Expand only synthetic TRAIN/DEV references for a real make_plan run.
        for side, count in (("train", 128), ("dev", 64)):
            path = repo / f"tools/stt_training/manifests/ta-{side}.jsonl"
            base = json.loads(path.read_text(encoding="utf-8").splitlines()[0])
            path.write_text("".join(json.dumps(dict(base, source_id=f"fixture:{side}:{i}")) + "\n"
                                    for i in range(count)), encoding="utf-8")
        plan = make_plan(repo, data_report)
        assert plan["counts"] == {"train": 128, "dev": 64}
        assert plan["seeds"] == [17, 29] and plan["max_steps_per_run"] == 20
        assert verify_plan(repo)["language"] == "ta"
        path = Path(plan["manifests"]["train"])
        assert all(json.loads(line)["lang"] == "ta" for line in path.read_text(encoding="utf-8").splitlines())
        path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        rejects(lambda: verify_plan(repo), "changed")
        plan = make_plan(repo, data_report)
        audio = Path(next(iter(plan["sealed_pilot_audio"])))
        audio.write_bytes(audio.read_bytes() + b"changed")
        rejects(lambda: verify_plan(repo), "audio changed")
        rejects(lambda: make_plan(repo, dict(data_report, language="te")), "Tamil")

    # Execute the real notebook bootstrap locally with only its output root
    # rebased. Prove failed stages still produce a downloadable, redacted ZIP.
    notebook_path = Path(__file__).with_name("kathbath_tamil_pilot.ipynb")
    notebook = json.loads(notebook_path.read_text(encoding="utf-8"))
    code = ["".join(c["source"]) for c in notebook["cells"] if c["cell_type"] == "code"]
    for i, source in enumerate(code):
        compile(source, f"pilot-notebook-cell-{i}", "exec")
    embedded = {}
    for node in ast.walk(ast.parse(code[0])):
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr == "write_text" and isinstance(node.func.value, ast.BinOp)
                and isinstance(node.func.value.right, ast.Constant)
                and node.func.value.right.value in ("kathbath_gate_b_data.py", "kathbath_tamil_pilot.py")):
            embedded[node.func.value.right.value] = ast.literal_eval(node.args[0])
    for name in ("kathbath_gate_b_data.py", "kathbath_tamil_pilot.py"):
        assert embedded[name] == Path(__file__).with_name(name).read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="itantra-pilot-notebook-check-") as tmp:
        namespace = {}
        exec(compile(code[0].replace("/kaggle/working", tmp.replace("\\", "/")), "bootstrap-check", "exec"), namespace)
        original = os.environ.get("HF_TOKEN")
        try:
            os.environ["HF_TOKEN"] = "synthetic-test-secret"
            def fail():
                raise RuntimeError("Failed with synthetic-test-secret")
            rejects(lambda: namespace["stage"]("failure-check", fail), "[REDACTED]")
            crash_args = [sys.executable, '-c', "import os; print(os.environ['HF_TOKEN'], flush=True); raise SystemExit(7)"]
            rejects(lambda: namespace['stage']('exit-check', lambda: namespace['command'](crash_args, 'exit-check')), 'returncode=7')
            assert namespace['summary']['process_exit_codes']['exit-check'] == 7
            exit_log = (Path(tmp) / 'itantra-tamil-pilot/logs/exit-check.log').read_text(encoding='utf-8')
            assert '[REDACTED]' in exit_log and 'synthetic-test-secret' not in exit_log
            assert '[process_exit] exit-check: returncode=7' in exit_log
            archive = Path(tmp) / "itantra_tamil_pilot_reports.zip"
            with zipfile.ZipFile(archive) as saved:
                handoff = json.loads(saved.read("handoff-report.json"))
                assert handoff["failure-check"] == "Failed"
                assert "synthetic-test-secret" not in handoff["error"]["message"]
        finally:
            if original is None:
                os.environ.pop("HF_TOKEN", None)
            else:
                os.environ["HF_TOKEN"] = original
    check_recovery_cell()
    check_cuda_binding_recovery()
    check_fp32_recovery()
    check_continue_fp32()
    check_same_dev_baseline()
    print("Tamil pilot safety checks passed: PCM16/float32 evaluation reading, recovery cell, balanced fixed selection, scope, native baseline gate, "
          "freeze/full parameter behavior, real leakage guard, post-plan tamper rejection, "
          "embedded source parity and failure-report ZIP redaction. "
          "NeMo/GPU/training: Not run.")


if __name__ == "__main__":
    main()
