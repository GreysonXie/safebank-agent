"""Runtime model selection persisted on the local runtime volume only.

The file never leaves this host, is written atomically with owner-only
permissions, and the HTTP surface only ever exposes a masked key tail.
"""
import json
import os
import re
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

DEFAULT_PATH = '/var/lib/safebank/model-settings.json'
DEFAULT_MODEL = 'deepseek-flash'
DEFAULT_URL = 'https://api.deepseek.com'
DEFAULT_PROVIDER = 'deepseek'
VALID_MODES = ('mock', 'deepseek')
# label/url/model/vendor: vendor selects the request protocol in models.py.
PROVIDER_PRESETS = {
    'deepseek': {'label': 'DeepSeek', 'url': DEFAULT_URL, 'model': DEFAULT_MODEL, 'vendor': 'deepseek'},
    'openai': {'label': 'OpenAI', 'url': 'https://api.openai.com/v1', 'model': 'gpt-4o-mini', 'vendor': 'openai'},
    'qwen': {'label': '通义千问（DashScope）', 'url': 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'model': 'qwen-plus', 'vendor': 'openai'},
    'kimi': {'label': 'Kimi（Moonshot）', 'url': 'https://api.moonshot.cn/v1', 'model': 'moonshot-v1-8k', 'vendor': 'openai'},
    'zhipu': {'label': '智谱 GLM', 'url': 'https://open.bigmodel.cn/api/paas/v4', 'model': 'glm-4-flash', 'vendor': 'openai'},
    'custom': {'label': '自定义 OpenAI 兼容端点', 'url': '', 'model': '', 'vendor': 'openai'},
}
_UNSET = object()


def masked_key(key):
    key = key or ''
    if not key:
        return ''
    return '…' + key[-4:] if len(key) >= 8 else '…'


def valid_base_url(url):
    if not isinstance(url, str):
        return False
    url = url.strip()
    if not url or len(url) > 200 or any(ch.isspace() for ch in url):
        return False
    parts = urlsplit(url)
    return (parts.scheme in ('http', 'https') and bool(parts.hostname)
            and not parts.username and not parts.password
            and not parts.query and not parts.fragment)


def normalize_url(url):
    return url.strip().rstrip('/')


def resolve_provider(provider, base_url):
    if provider in PROVIDER_PRESETS:
        return provider
    for key, preset in PROVIDER_PRESETS.items():
        if key != 'custom' and preset['url'] == base_url:
            return key
    return 'custom'


class ModelSettings:
    """Thread-safe selection of the model adapter. Environment seeds first use."""

    def __init__(self, path=None):
        self.path = Path(path or os.environ.get('SAFEBANK_MODEL_SETTINGS_PATH', DEFAULT_PATH))
        self._lock = threading.RLock()
        self._data = self._load() or self._seed()
        # Normalize once so an older file (or an environment seed) gains the
        # provider fields and owner-only permissions.
        self._persist(self._data)

    def _load(self):
        try:
            data = json.loads(self.path.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            return None
        if not isinstance(data, dict):
            return None
        mode = data.get('mode')
        key = data.get('apiKey')
        name = data.get('modelName')
        if mode not in VALID_MODES or not isinstance(key, str) or not isinstance(name, str) or not name:
            return None
        base = data.get('baseUrl') or data.get('modelUrl') or ''
        provider = resolve_provider(data.get('provider'), base)
        if provider != 'custom' and not valid_base_url(base):
            base = PROVIDER_PRESETS[provider]['url']
        if provider == 'custom' and not valid_base_url(base):
            provider = DEFAULT_PROVIDER
            base = DEFAULT_URL
        return {'mode': mode, 'provider': provider, 'baseUrl': normalize_url(base),
                'modelName': name, 'apiKey': key,
                'updatedAt': data.get('updatedAt') or self._now()}

    def _seed(self):
        mode = os.environ.get('SAFEBANK_MODEL_MODE', 'mock')
        if mode not in VALID_MODES:
            mode = 'mock'
        key = os.environ.get('DEEPSEEK_API_KEY', '').strip()
        if mode == 'deepseek' and not key:
            mode = 'mock'
        provider = os.environ.get('SAFEBANK_MODEL_PROVIDER', DEFAULT_PROVIDER)
        if provider not in PROVIDER_PRESETS:
            provider = DEFAULT_PROVIDER
        base = os.environ.get('SAFEBANK_MODEL_URL', '').strip()
        if not valid_base_url(base):
            base = PROVIDER_PRESETS[provider]['url'] or DEFAULT_URL
        name = (os.environ.get('SAFEBANK_MODEL_NAME', '').strip()
                or PROVIDER_PRESETS[provider]['model'] or DEFAULT_MODEL)
        return {'mode': mode, 'provider': provider, 'baseUrl': normalize_url(base),
                'modelName': name, 'apiKey': key, 'updatedAt': self._now()}

    def _persist(self, data):
        directory = self.path.parent
        directory.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=str(directory), prefix='.model-settings-')
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as handle:
                json.dump(data, handle, ensure_ascii=False)
            os.chmod(tmp, 0o600)
            os.replace(tmp, self.path)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise

    @staticmethod
    def _now():
        return datetime.now(timezone.utc).isoformat(timespec='seconds')

    def adapter_config(self):
        with self._lock:
            data = dict(self._data)
        data['vendor'] = PROVIDER_PRESETS[data['provider']]['vendor']
        return data

    def snapshot(self):
        with self._lock:
            data = dict(self._data)
        preset = PROVIDER_PRESETS[data['provider']]
        return {'mode': data['mode'], 'provider': data['provider'], 'providerLabel': preset['label'],
                'baseUrl': data['baseUrl'], 'modelName': data['modelName'],
                'keyConfigured': bool(data['apiKey']), 'keyMasked': masked_key(data['apiKey']),
                'updatedAt': data['updatedAt']}

    def update(self, mode=None, api_key=_UNSET, model_name=None, provider=None, base_url=None):
        with self._lock:
            data = dict(self._data)
            if mode is not None:
                if mode not in VALID_MODES:
                    raise ValueError('INVALID_MODE')
                data['mode'] = mode
            if provider is not None:
                if provider not in PROVIDER_PRESETS:
                    raise ValueError('INVALID_PROVIDER')
                data['provider'] = provider
                if provider != 'custom' and base_url is None:
                    data['baseUrl'] = PROVIDER_PRESETS[provider]['url']
            if base_url is not None:
                if not valid_base_url(base_url):
                    raise ValueError('INVALID_BASE_URL')
                data['baseUrl'] = normalize_url(base_url)
            if api_key is not _UNSET:
                if not isinstance(api_key, str):
                    raise ValueError('INVALID_KEY')
                api_key = api_key.strip()
                if len(api_key) > 512:
                    raise ValueError('INVALID_KEY')
                data['apiKey'] = api_key
            if model_name is not None:
                model_name = model_name.strip() if isinstance(model_name, str) else ''
                if not model_name or len(model_name) > 64 or not re.fullmatch(r'[A-Za-z0-9._:/-]+', model_name):
                    raise ValueError('INVALID_MODEL_NAME')
                data['modelName'] = model_name
            if data['provider'] == 'custom' and not data['baseUrl']:
                raise ValueError('BASE_URL_REQUIRED')
            if data['mode'] == 'deepseek' and not data['apiKey']:
                raise ValueError('MODEL_KEY_REQUIRED')
            data['updatedAt'] = self._now()
            self._persist(data)
            self._data = data
            return self.snapshot()
