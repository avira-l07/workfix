"""CPU check: reconnect preserves evidence and refuses missing/failed setup."""
import json
import os
import tempfile
from pathlib import Path
from unittest.mock import patch


def main():
    cell = Path(__file__).with_name('kathbath_tamil_session_reconnect.txt').read_text(encoding='utf-8')
    original_cwd = Path.cwd()
    original_env = dict(os.environ)
    try:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            code = cell.replace('/kaggle/working/itantra-tamil-pilot', root.as_posix())
            try:
                exec(code, {})
            except RuntimeError as error:
                assert 'Cannot reconnect' in str(error)
            else:
                raise AssertionError('Missing files must prevent reconnect')
            for name in ('venv/bin/python', 'kathbath_tamil_pilot.py', 'kathbath_gate_b_data.py',
                         'pilot-plan.json', 'runtime-report.json', 'NeMo/.git/HEAD', 'logs/old-training.log'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('preserve existing evidence', encoding='utf-8')
            (root / 'baseline-report.json').write_text(json.dumps({'ctc_baseline_gate_passed': True}))
            (root / 'handoff-report.json').write_text(json.dumps({'old-stage': 'Failed', 'error': {'message': 'old failure'}}))
            before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
            os.environ['HF_TOKEN'] = 'private-test-token'
            class Process:
                stdout = ['runtime and data verified private-test-token\n']
                def wait(self):
                    return 0
            namespace = {}
            with patch('subprocess.Popen', return_value=Process()) as popen:
                exec(code, namespace)
                args = popen.call_args.args[0]
                assert 'p.runtime()' in args[-1] and 'p.verify_plan' in args[-1]
            assert all(p.read_bytes() == content for p, content in before.items())
            assert namespace['summary']['old-stage'] == 'Failed'
            assert all(callable(namespace[name]) for name in ('command', 'stage', 'pack_reports'))
            log = next((root / 'logs').glob('session-reconnect-*.log')).read_text()
            assert '[REDACTED]' in log and 'private-test-token' not in log
            (root / 'baseline-report.json').write_text('{"ctc_baseline_gate_passed": false}')
            try:
                exec(code, {})
            except RuntimeError as error:
                assert 'baseline has not passed' in str(error)
            else:
                raise AssertionError('Failed baseline must prevent reconnect')
            os.chdir(original_cwd)
    finally:
        os.chdir(original_cwd)
        os.environ.clear()
        os.environ.update(original_env)
    print('Reconnect guard, evidence preservation and secret redaction checks passed.')


if __name__ == '__main__':
    main()
