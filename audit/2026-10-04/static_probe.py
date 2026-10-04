"""Source inventories; none of these results are rendered UI or device verification."""
import hashlib, json, re
from html.parser import HTMLParser
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).resolve().parent
preview=ROOT/'itantra_light_ui'
js=(preview/'app.js').read_text(encoding='utf-8')
class Links(HTMLParser):
    def __init__(self): super().__init__(); self.assets=[]
    def handle_starttag(self,tag,attrs):
        for key,value in attrs:
            if key in ('src','href') and value and not value.startswith(('http','data:','#')):
                self.assets.append(value)
parser=Links(); parser.feed((preview/'index.html').read_text(encoding='utf-8'))
static={'scope':'Static inspection only; browser file policy blocked visual and interactive tests',
    'preview_asset_links':[{'path':a,'exists':(preview/a).is_file()} for a in parser.assets],
    'preview_routes':re.findall(r"\['(hub|connect|chat|notes|packs|settings|diagnostics)'",js),
    'preview_declared_action_cases':sorted(set(re.findall(r"case\s*'([^']+)'",js))),
    'preview_literal_data_actions':sorted(set(re.findall(r'data-action="([a-z-]+)"',js))),
    'production_sources':sum(1 for _ in (ROOT/'app/src/main/java').rglob('*.kt')),
    'secret_literal_candidates':[]}
patterns=[('HF token',re.compile(r'\bhf_[A-Za-z0-9]{30,}\b')),
          ('GitHub token',re.compile(r'\b(?:ghp_|github_pat_)[A-Za-z0-9_]{30,}\b')),
          ('AWS access ID',re.compile(r'\bAKIA[A-Z0-9]{16}\b')),
          ('Private key',re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'))]
files=list((ROOT/'app/src/main').rglob('*.kt'))+list((ROOT/'app/src/main').rglob('*.xml'))
files+=list((ROOT/'tools').glob('*.py'))+list((ROOT/'tools').glob('*.ipynb'))
files+=list(preview.glob('*.js'))+list(ROOT.glob('*.properties'))+list(ROOT.glob('*.kts'))
for file in files:
    for number,line in enumerate(file.read_text(encoding='utf-8',errors='replace').splitlines(),1):
        for label,pattern in patterns:
            if pattern.search(line):
                static['secret_literal_candidates'].append({'type':label,'path':str(file),'line':number,
                                                             'value':'REDACTED - verify locally'})
def luminance(hex):
    rgb=[int(hex[i:i+2],16)/255 for i in (1,3,5)]
    rgb=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in rgb]
    return sum(a*b for a,b in zip(rgb,(.2126,.7152,.0722)))
def contrast(a,b):
    x,y=sorted([luminance(a),luminance(b)])
    return round((y+.05)/(x+.05),2)
static['native_static_color_contrast']=[{'foreground':a,'background':b,'ratio':contrast(a,b)}
    for a,b in [('#64748B','#FFFFFF'),('#334155','#1E293B'),('#0F172A','#1E293B'),
                ('#059669','#FFFFFF'),('#D97706','#FFFFFF')]]
(OUT/'static-inventory.json').write_text(json.dumps(static,indent=2),encoding='utf-8')
print(json.dumps({'assets':len(parser.assets),'missing_assets':[a for a in parser.assets if not (preview/a).is_file()],
    'routes':static['preview_routes'],'declared_action_cases':len(static['preview_declared_action_cases']),
    'secret_candidate_count':len(static['secret_literal_candidates']),
    'native_contrast':static['native_static_color_contrast']},indent=2))
