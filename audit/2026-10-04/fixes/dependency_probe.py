"""Query primary dependency registries and OSV; never scans/uploads source or secrets."""
import concurrent.futures, json, re, time
from pathlib import Path
import requests
OUT=Path(__file__).resolve().parent
modules=json.loads((OUT/'dependencies.json').read_text())
request={'queries':[{'package':{'ecosystem':'Maven','name':r['group']+':'+r['artifact']},'version':r['version']}
                    for r in modules]}
report={'scope':'Resolved debug runtime Maven modules only; local AAR/native/tool/test packages excluded',
        'count':len(modules),'osv':'Not verified','outdated_checks':[]}
try:
    response=requests.post('https://api.osv.dev/v1/querybatch',json=request,timeout=(15,40))
    response.raise_for_status(); data=response.json()
    assert len(data['results'])==len(modules)
    report['osv']=[dict(module=r,result=v) for r,v in zip(modules,data['results'])]
    ids=sorted({v['id'] for item in data['results'] for v in item.get('vulns',[])})
    report['advisories']={}
    for id in ids:
        response=requests.get('https://api.osv.dev/v1/vulns/'+id,timeout=(15,30)); response.raise_for_status()
        report['advisories'][id]=response.json()
except Exception as error:
    report['osv_error']=str(error)
DIRECT=[('androidx.room','room-runtime'),('androidx.datastore','datastore-preferences'),
        ('androidx.lifecycle','lifecycle-viewmodel-compose'),('com.google.mlkit','translate'),
        ('com.google.android.gms','play-services-nearby'),('org.jetbrains.kotlinx','kotlinx-coroutines-android'),
        ('org.jetbrains.kotlinx','kotlinx-serialization-json'),('net.zetetic','sqlcipher-android')]
def check(pair):
    group,artifact=pair
    current=next((r['version'] for r in modules if r['group']==group and r['artifact']==artifact),'Not resolved')
    registry='https://dl.google.com/dl/android/maven2/' if group.startswith(('androidx','com.google')) else 'https://repo.maven.apache.org/maven2/'
    url=registry+group.replace('.','/')+'/'+artifact+'/maven-metadata.xml'
    result={'group':group,'artifact':artifact,'current':current,'primary_registry':url}
    try:
        response=requests.get(url,timeout=(15,25)); response.raise_for_status()
        versions=[v for v in re.findall(r'<version>([^<]+)</version>',response.text) if re.fullmatch(r'\d+(?:\.\d+)+',v)]
        newest=max(versions,key=lambda v:tuple(map(int,v.split('.'))))
        result.update(latest_stable=newest,outdated=current!=newest)
    except Exception as error: result['error']=str(error)
    return result
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    report['outdated_checks']=list(pool.map(check,DIRECT))
(OUT/'dependency-security.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({'modules':report['count'],'osv_error':report.get('osv_error'),
                 'advisory_ids':list(report.get('advisories',{})),
                 'outdated_checks':report['outdated_checks']},indent=2))
