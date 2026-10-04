from pathlib import Path
here = Path(__file__).resolve().parent
base = here.parent
security = (base / 'dependency_probe.py').read_text(encoding='utf-8')
(here / 'dependency_probe.py').write_text(security, encoding='utf-8')
package = (base / 'package_probe.py').read_text(encoding='utf-8')
package = package.replace('parents[2]', 'parents[3]')
package = package.replace("OUT/'build/app/outputs/apk'", "OUT.parent/'build/app/outputs/apk'")
(here / 'package_probe.py').write_text(package, encoding='utf-8')
