"""Explicit local configuration. No secrets appear in repr or command output."""
import json
import os
from dataclasses import dataclass, field
from .contracts import ProtocolError


@dataclass(frozen=True)
class Config:
    database: dict = field(repr=False)
    bank_url: str
    service_key: str = field(repr=False)
    model_mode: str = 'mock'
    model_name: str = 'deepseek-flash'
    model_url: str = 'https://api.deepseek.com'
    model_key: str = field(default='', repr=False)
    port: int = 8135

    @classmethod
    def environment(cls):
        config = cls(json.loads(os.environ['SAFEBANK_AGENT_DB_CONFIG']), os.environ['SAFEBANK_BANK_URL'], os.environ['SAFEBANK_AGENT_SERVICE_KEY'], os.getenv('SAFEBANK_MODEL_MODE','mock'), os.getenv('SAFEBANK_MODEL_NAME','deepseek-flash'), os.getenv('SAFEBANK_MODEL_URL','https://api.deepseek.com'), os.getenv('DEEPSEEK_API_KEY',''), int(os.getenv('SAFEBANK_AGENT_PORT','8135')))
        if config.bank_url not in ('http://127.0.0.1:8134','http://127.0.0.1:8136') or len(config.service_key) < 64 or config.model_mode not in ('mock','deepseek'):
            raise ProtocolError('INVALID_CONFIGURATION')
        # A missing key no longer blocks startup: the persisted runtime settings
        # may hold it, and the provider is only called when a key is configured.
        if config.model_mode == 'deepseek' and not config.model_url.startswith(('http://', 'https://')):
            raise ProtocolError('INVALID_MODEL_CONFIGURATION')
        return config
