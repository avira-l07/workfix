"""Score tested rows in the phone CSV using the existing desktop normalization.

Blank hypotheses count as recognition failures. Untested rows are excluded.
Different devices, conditions, pack hashes, APK versions and mic modes stay separate.
"""
import argparse
import csv
import json
from pathlib import Path

from validate_stt import normalize, distance

GROUP = ('language', 'condition', 'device', 'apk_version', 'stt_model_sha256', 'mic_mode')


def score(rows):
    totals, seen = {}, set()
    for index, row in enumerate(rows, 2):
        tested = (row.get('tested') or '').strip().lower()
        if tested in ('', 'no', 'false', '0'):
            continue
        if tested not in ('yes', 'true', '1'):
            raise ValueError(f'Row {index}: tested must be yes or no')
        key = tuple((row.get(k) or '').strip() for k in GROUP)
        if key[0] not in ('hi', 'en', 'bn', 'gu', 'mr', 'kn', 'ml', 'ta', 'te', 'or'):
            raise ValueError(f'Row {index}: unknown language')
        sample = (key, row.get('sample_id'))
        if not sample[1] or sample in seen:
            raise ValueError(f'Row {index}: missing or duplicate sample ID in this test group')
        seen.add(sample)
        reference = normalize(row.get('reference') or '').split()
        hypothesis = normalize(row.get('hypothesis') or '').split()
        if not reference:
            raise ValueError(f'Row {index}: reference must contain words')
        entry = totals.setdefault(key, dict(zip(GROUP, key), clips=0, reference_words=0,
                                            word_errors=0, reference_characters=0, character_errors=0))
        entry['clips'] += 1
        entry['reference_words'] += len(reference)
        entry['word_errors'] += distance(reference, hypothesis)
        entry['reference_characters'] += len(''.join(reference))
        entry['character_errors'] += distance(''.join(reference), ''.join(hypothesis))
    for entry in totals.values():
        entry['wer_percent'] = 100*entry['word_errors']/entry['reference_words']
        entry['cer_percent'] = 100*entry['character_errors']/entry['reference_characters']
    return list(totals.values())


def self_check():
    base = dict(language='en', condition='quiet', device='phone', apk_version='debug',
                stt_model_sha256='test', mic_mode='manual', tested='yes', sample_id='one',
                reference='We need help', hypothesis='We need water')
    result = score([base, dict(base, sample_id='two', hypothesis=''),
                    dict(base, sample_id='untested', tested='', hypothesis='nonsense')])[0]
    assert result['clips'] == 2 and result['word_errors'] == 4 and result['reference_words'] == 6
    assert abs(result['wer_percent']-100*4/6) < 1e-9
    assert len(score([base, dict(base, condition='noisy')])) == 2
    assert score([dict(base, reference='CAFÉ!', hypothesis='cafe\u0301')])[0]['wer_percent'] == 0
    assert score([dict(base, reference='one', hypothesis='one two three')])[0]['wer_percent'] == 200
    try:
        score([base, base])
    except ValueError:
        pass
    else:
        raise AssertionError('Duplicate samples must be rejected')
    print('PASS: counts, blank failures, untested exclusion, group separation, Unicode and duplicate detection')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv_file', type=Path, nargs='?')
    parser.add_argument('--self-check', action='store_true')
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return
    if not args.csv_file:
        parser.error('Provide a completed phone CSV or use --self-check')
    with args.csv_file.open(encoding='utf-8-sig', newline='') as f:
        results = score(csv.DictReader(f))
    if not results:
        parser.error('No tested rows; fill hypothesis and mark tested=yes (blank failed transcripts also count)')
    destination = args.csv_file.with_suffix('.scores.json')
    destination.write_text(json.dumps(dict(scope='User-recorded phone tests, separate from desktop results',
                                           test_groups=results), indent=2, ensure_ascii=False), encoding='utf-8')
    print(f'Saved {destination}')
    for result in results:
        print(f"{result['language']} | {result['condition']} | {result['device']} | {result['clips']} clips | WER {result['wer_percent']:.2f}% | CER {result['cer_percent']:.2f}%")


if __name__ == '__main__':
    main()
