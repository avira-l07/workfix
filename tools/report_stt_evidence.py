"""Summarize existing measured speech results and local footprint; no inference or device access."""
import json
import statistics
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tools/stt_results'

def footprint(directory):
    files = sorted(p for p in directory.rglob('*') if p.is_file()) if directory.exists() else []
    return {'bytes': sum(p.stat().st_size for p in files), 'file_count': len(files)}

def main():
    results = {}
    for label in ('tiny', 'small', 'small-repaired', 'tiny-hi-test', 'tiny-repaired-hi-test',
                  'hindi-small-test', 'hindi-small-final', 'tiny-en-final'):
        path = OUT / f'{label}.json'
        if not path.exists():
            continue
        report = json.loads(path.read_text(encoding='utf-8'))
        languages = {}
        for lang, summary in report['summaries'].items():
            samples = [r['seconds'] * 1000 for r in report['rows'] if r['language'] == lang]
            languages[lang] = {**summary, 'decode_ms_mean': statistics.mean(samples),
                              'decode_ms_median': statistics.median(samples), 'decode_ms_max': max(samples)}
        results[label] = {'languages': languages, 'desktop_peak_process_bytes': report.get('peak_process_working_set_bytes')}
    assets = ROOT / 'app/src/main/assets/language_packs'
    sizes = {'bundled_language_assets': footprint(assets), 'bundled_stt': footprint(assets / 'shared/stt'),
             'bundled_tts_by_language': {code: footprint(assets / code / 'tts')
                 for code in ('hi', 'en', 'bn', 'gu', 'mr', 'kn', 'ml', 'ta', 'te', 'or')}}
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    sizes['existing_apk'] = {'bytes': apk.stat().st_size,
        'packaging_verification_report': 'tools/stt_results/apk-verification.json'} if apk.exists() else None
    sizes['hindi_specialized_model'] = footprint(assets / 'shared/stt-hi-v1')
    tests = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in tests:
            tests[key] += int(suite.get(key, 0))
    candidate = dict(results.get('hindi-small-final', {}).get('languages', {}))
    candidate.update(results.get('tiny-en-final', {}).get('languages', {}))
    baseline = dict(results.get('tiny-hi-test', {}).get('languages', {}))
    baseline.update({'en': results['tiny']['languages']['en']})
    checks = {}
    if 'hi' in candidate and 'en' in candidate:
        checks = {
            'hindi_wer_at_most_35_percent': candidate['hi']['wer'] <= 0.35,
            'hindi_relative_improvement_at_least_25_percent': candidate['hi']['wer'] <= baseline['hi']['wer'] * 0.75,
            'english_wer_at_most_20_percent': candidate['en']['wer'] <= 0.20,
            'english_regression_at_most_3_percentage_points': candidate['en']['wer'] <= baseline['en']['wer'] + 0.03,
            'desktop_rtf_at_most_3_each_language': all(x['rtf'] <= 3 for x in candidate.values())
        }
    output = {'scope': 'Desktop results and local file sizes only; no device performance or TTS listener evaluation.',
              'results': results, 'storage': sizes, 'unit_tests_from_existing_xml': tests,
              'internal_candidate_checks_not_official_thresholds': checks,
              'candidate_passes_internal_desktop_gate': bool(checks) and all(checks.values()),
              'unmeasured': ['Android CPU/RAM', 'installed Android storage', 'microphone endpoint latency',
                             'received-text-to-audible-TTS latency', 'two-phone latency', 'human TTS intelligibility',
                             'seven additional supported languages'],
              'unsupported': ['Odia STT in evaluated model family']}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'evaluation-summary.json').write_text(json.dumps(output, indent=2), encoding='utf-8')
    print(json.dumps(output, indent=2))

if __name__ == '__main__':
    main()
