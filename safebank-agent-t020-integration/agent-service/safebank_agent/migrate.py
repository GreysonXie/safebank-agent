"""Deployment-only schema creation/checksum verification with migration credentials."""
import hashlib
import re
from pathlib import Path
import pymysql
from langgraph.checkpoint.mysql.pymysql import PyMySQLSaver
from .contracts import ProtocolError

def migrate(config):
    source=(Path(__file__).resolve().parents[1]/'migrations/001_runtime.sql').read_text(encoding='utf-8')
    checksum=hashlib.sha256(source.encode()).hexdigest()
    with pymysql.connect(**dict(config,cursorclass=pymysql.cursors.DictCursor),autocommit=True) as connection:
        with connection.cursor() as c:
            c.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_schema_migrations'")
            exists=c.fetchone()['n']
            if exists:
                c.execute('SELECT checksum FROM agent_schema_migrations WHERE version=1');row=c.fetchone()
                if not row or row['checksum']!=checksum:raise ProtocolError('SCHEMA_CHECKSUM_MISMATCH')
            else:
                c.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'agent_%'")
                if c.fetchone()['n']:raise ProtocolError('PARTIAL_SCHEMA_REQUIRES_REVIEW')
                for statement in re.sub(r'^--.*$','',source,flags=re.M).split(';'):
                    if statement.strip():c.execute(statement)
                c.execute('INSERT INTO agent_schema_migrations(version,checksum,applied_at) VALUES(1,%s,UTC_TIMESTAMP(6))',(checksum,))
        PyMySQLSaver(connection).setup()
