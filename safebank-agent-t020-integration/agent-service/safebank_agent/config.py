"""Explicit local configuration. No secrets appear in repr or command output."""
import json
import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit
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
        mode = os.getenv('SAFEBANK_MODEL_MODE','mock')
        key = os.getenv('SAFEBANK_MODEL_API_KEY','') if mode == 'openai' else os.getenv('DEEPSEEK_API_KEY','')
        config = cls(json.loads(os.environ['SAFEBANK_AGENT_DB_CONFIG']), os.environ['SAFEBANK_BANK_URL'], os.environ['SAFEBANK_AGENT_SERVICE_KEY'], mode, os.getenv('SAFEBANK_MODEL_NAME','deepseek-flash'), os.getenv('SAFEBANK_MODEL_URL','https://api.deepseek.com').rstrip('/'), key, int(os.getenv('SAFEBANK_AGENT_PORT','8135')))
        if config.bank_url not in ('http://127.0.0.1:8134','http://127.0.0.1:8136') or len(config.service_key) < 64 or config.model_mode not in ('mock','deepseek','openai'):
            raise ProtocolError('INVALID_CONFIGURATION')
        if config.model_mode == 'deepseek' and (not config.model_key or config.model_url != 'https://api.deepseek.com'):
            raise ProtocolError('INVALID_MODEL_CONFIGURATION')
        if config.model_mode == 'openai':
            try:
                endpoint = urlsplit(config.model_url)
                valid = endpoint.scheme == 'https' and bool(endpoint.hostname) and endpoint.username is None and endpoint.password is None and not endpoint.query and not endpoint.fragment
                endpoint.port  # Reject malformed ports before sending credentials.
            except ValueError:
                valid = False
            if not valid or not config.model_key.strip() or not config.model_name.strip():
                raise ProtocolError('INVALID_MODEL_CONFIGURATION')
        return config
