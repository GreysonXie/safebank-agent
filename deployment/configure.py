"""Generate local settings without reading or sharing anyone else's secrets."""
from pathlib import Path
import secrets

if __name__=='__main__':
    root=Path(__file__).resolve().parents[1]
    path=root/'.env'
    if path.exists():raise SystemExit('.env already exists; edit it locally, do not overwrite it.')
    example=(root/'.env.example').read_text(encoding='utf-8-sig')
    path.write_text(example.replace('REPLACE_WITH_LOCAL_RANDOM_PASSWORD',secrets.token_hex(32)),encoding='utf-8')
    try:path.chmod(0o600)
    except OSError:pass
    print('Created local .env; mock mode by default. Never commit or send this file.')
