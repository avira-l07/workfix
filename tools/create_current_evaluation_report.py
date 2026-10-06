"""Build a chart and test sheet from saved measurements; performs no inference."""
import csv
import json
import math
from pathlib import Path
import statistics

from validate_stt import normalize, distance

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/evaluation-2026-10-06'
NAMES = dict(hi='Hindi', en='English', bn='Bengali', gu='Gujarati', mr='Marathi',
             kn='Kannada', ml='Malayalam', ta='Tamil', te='Telugu', or_='Odia')
NAMES['or'] = NAMES.pop('or_')


def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8-sig'))


def write_csv(name, rows):
    with (OUT / name).open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    replay = read('artifacts/tts-replay-2026-10-06/all-language-tts-check.json')
    replay_rows = {r['language']: r for r in replay['languages']}
    stats, cases = [], []
    for lang, name in NAMES.items():
        stt_path = ('audit/2026-10-04/fixes/five-language-kn-30.json' if lang == 'kn'
                    else f'tools/stt_results/five-language-{lang}-30.json')
        tts_path = ('tools/stt_results/marathi-tts-repair-2026-10-04/piper-sid0-1threads.json' if lang == 'mr'
                    else f'tools/stt_results/five-language-{lang}-tts-30.json')
        stt, tts = read(stt_path), read(tts_path)
        manifest = read(f'app/src/main/assets/language_packs/{lang}_dev_manifest.json')
        assert stt['model_sha256'] in manifest['sttModel']['checksumsSha256'].values()
        assert tts['model_sha256'] in manifest['ttsModel']['checksumsSha256'].values()
        assert replay_rows[lang]['passed'] and len(replay_rows[lang]['trials']) == 2
        assert replay_rows[lang]['model_sha256'] == tts['model_sha256']
        corpus = read('tools/stt_models/fleurs-hi-test-manifest.json' if lang == 'hi'
                      else f'tools/stt_models/five-language-validation/manifest-{lang}-30.json')
        corpus = [r for r in corpus if r['language'] == lang][:30]
        assert len(corpus) == len(stt['rows']) == stt['summary']['count'] == 30
        for ref, measured in zip(corpus, stt['rows']):
            assert ref['row'] == measured['row']
            words = normalize(ref['reference']).split()
            hypothesis = normalize(measured['output']).split()
            assert distance(words, hypothesis) == measured['word_errors']
            assert distance(''.join(words), ''.join(hypothesis)) == measured['char_errors']
            assert len(words) == measured['words']
            assert len(''.join(words)) == measured['chars']
            cases.append(dict(language=lang, sample_id=f"{lang}-{ref['row']}",
                              reference_source_id=ref['id'], reference_split='test' if lang == 'hi' else 'validation', tested='',
                              reference=ref['reference'], hypothesis='', condition='quiet',
                              device='', apk_version='', stt_model_sha256=stt['model_sha256'],
                              mic_mode='manual', speaker_id=''))
        s, t = stt['summary'], tts['summary']
        assert t.get('count', t.get('trials')) == 30
        errors = sum(r['word_errors'] for r in stt['rows'])
        words = sum(r['words'] for r in stt['rows'])
        assert math.isclose(errors / words, s['wer'], abs_tol=1e-10)
        assert math.isclose(sum(r['char_errors'] for r in stt['rows']) / sum(r['chars'] for r in stt['rows']), s['cer'], abs_tol=1e-10)
        assert math.isclose(statistics.mean(r['decode_ms'] for r in stt['rows']), s['decode_ms_mean'], abs_tol=1e-5)
        stats.append(dict(language=name, code=lang, stt_clips=s['count'], reference_words=words,
                          word_errors=errors, wer_percent=100*s['wer'], cer_percent=100*s['cer'],
                          stt_mean_ms=s['decode_ms_mean'], stt_p95_ms=s['decode_ms_p95_nearest_rank'],
                          stt_rtf=s['rtf'], stt_peak_desktop_ram_mib=s['peak_desktop_working_set_bytes']/2**20,
                          tts_trials=t.get('count', t.get('trials')), tts_mean_ms=t['mean_synthesis_ms'],
                          tts_p95_ms=t.get('p95_synthesis_ms_nearest_rank', t.get('p95_synthesis_ms')),
                          tts_rtf=t['rtf'], tts_sample_rate_hz=t['sample_rate_hz'],
                          fresh_tts_non_silent_trials=2, phone_wer='Not measured',
                          human_tts_quality='Not rated', stt_model_sha256=stt['model_sha256'],
                          tts_model_sha256=tts['model_sha256'], stt_report=stt_path, tts_report=tts_path))
    write_csv('evaluation-stats.csv', stats)
    write_csv('phone-test-template.csv', cases)
    heldout = []
    for lang in ('bn', 'gu', 'ta', 'te', 'ml'):
        path = (f'tools/stt_results/cheap-{lang}-baseline-100.json' if lang != 'ml'
                else 'tools/stt_results/ml-indicconformer-whole-100.json')
        s = read(path)['summary']
        assert s['count'] == 100
        heldout.append(dict(language=NAMES[lang], code=lang, clips=s['count'],
                            wer_percent=s['wer']*100, cer_percent=s['cer']*100, source=path))
    report = dict(as_of='2026-10-06', scope='Saved desktop measurements of current selected packs, not phone or field accuracy',
                  method='Corpus WER/CER: NFC, lowercase, punctuation stripped; character comparison excludes spaces; lower is better',
                  corpus='FLEURS: Hindi uses 30 test recordings; other nine use 30 validation recordings per language',
                  stt_clips=300, timing_scope='STT decode only; TTS waveform synthesis only; different workloads, not end-to-end latency',
                  languages=stats, additional_100_clip_tests=heldout,
                  tamil_same_dev_experiment=read('tools/stt_results/tamil-gate-b-same-dev-review-2026-10-06/review-summary.json'),
                  app_verification=read('artifacts/tts-replay-2026-10-06/verification.json'))
    (OUT/'evaluation-stats.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    draw_chart(stats)
    print('Verified 300 reference/hypothesis pairs and all selected model hashes; chart, stats and 300-row phone sheet written.')


def draw_chart(stats):
    from reportlab.graphics.charts.barcharts import HorizontalBarChart
    from reportlab.graphics.shapes import Drawing, String, Rect
    from reportlab.graphics import renderPDF
    from reportlab.lib.colors import HexColor
    import pypdfium2

    rows = sorted(stats, key=lambda r: r['wer_percent'], reverse=True)
    drawing = Drawing(840, 585)
    drawing.add(Rect(0, 0, 840, 585, fillColor=HexColor('#ffffff'), strokeColor=None))
    drawing.add(String(35, 552, 'iTantra: current speech-recognition error rates', fontName='Helvetica-Bold', fontSize=20, fillColor=HexColor('#172b45')))
    drawing.add(String(35, 528, '30 clean FLEURS clips per language | Desktop results | Snapshot: 6 Oct 2026', fontSize=11, fillColor=HexColor('#536579')))
    chart = HorizontalBarChart()
    chart.x, chart.y, chart.width, chart.height = 128, 83, 652, 420
    chart.data = [[r['wer_percent'] for r in rows], [r['cer_percent'] for r in rows]]
    chart.categoryAxis.categoryNames = [r['language'] for r in rows]
    chart.categoryAxis.labels.fontSize = 11
    chart.categoryAxis.labels.fillColor = HexColor('#172b45')
    chart.valueAxis.valueMin = 0
    chart.valueAxis.valueMax = math.ceil(max(r['wer_percent'] for r in rows)/5)*5+5
    chart.valueAxis.valueStep = 5
    chart.valueAxis.labels.fontSize = 10
    chart.valueAxis.visibleGrid = True
    chart.valueAxis.gridStrokeColor = HexColor('#e3e8ef')
    chart.bars[0].fillColor = HexColor('#146da5')
    chart.bars[1].fillColor = HexColor('#85bcb5')
    chart.bars.strokeColor = None
    chart.barLabelFormat = '%.2f%%'
    chart.barLabels.fontSize = 9
    chart.barLabels.boxAnchor = 'w'
    chart.barLabels.nudge = 5
    chart.groupSpacing = 9
    drawing.add(chart)
    drawing.add(Rect(128, 511, 11, 8, fillColor=HexColor('#146da5'), strokeColor=None))
    drawing.add(String(146, 510, 'Word error rate (WER)', fontSize=10))
    drawing.add(Rect(301, 511, 11, 8, fillColor=HexColor('#85bcb5'), strokeColor=None))
    drawing.add(String(319, 510, 'Character error rate (CER)', fontSize=10))
    drawing.add(String(454, 49, 'Error rate (%) - lower is better', textAnchor='middle', fontSize=11))
    drawing.add(String(35, 24, 'Selected model checksums match app manifests. Phone WER and human-rated TTS quality are not measured.', fontSize=9, fillColor=HexColor('#536579')))
    pdf = OUT/'current-accuracy-chart.pdf'
    renderPDF.drawToFile(drawing, str(pdf))
    with pypdfium2.PdfDocument(pdf) as document:
        bitmap = document[0].render(scale=2)
        bitmap.to_pil().save(OUT/'current-accuracy-chart.png')
        bitmap.close()


if __name__ == '__main__':
    main()
