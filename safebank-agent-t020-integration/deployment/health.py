import json,urllib.request
from pathlib import Path
p=json.loads(Path('/var/lib/safebank/identity.json').read_text(encoding='utf-8'))
with urllib.request.urlopen('http://127.0.0.1:8134/api/session',timeout=3):pass
request=urllib.request.Request('http://127.0.0.1:8135/internal/agent/health',headers={'Authorization':'Bearer '+p['serviceKey']})
with urllib.request.urlopen(request,timeout=3) as response:assert json.load(response)['status']=='UP'
