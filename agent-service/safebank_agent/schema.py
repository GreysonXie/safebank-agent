"""Read-only startup schema assertion; runtime credentials cannot repair DDL."""
import hashlib
import json
from pathlib import Path
from .contracts import ProtocolError

def manifest(cursor):
    cursor.execute("SELECT table_name,column_name,column_type,is_nullable,column_default,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position")
    columns=cursor.fetchall()
    cursor.execute("SELECT table_name,index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index")
    indexes=cursor.fetchall()
    cursor.execute("SELECT tc.table_name,tc.constraint_name,tc.constraint_type,cc.check_clause FROM information_schema.table_constraints tc LEFT JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.table_schema=DATABASE() ORDER BY tc.table_name,tc.constraint_name")
    constraints=cursor.fetchall()
    return {'columns':columns,'indexes':indexes,'constraints':constraints}

def verify(store):
    folder=Path(__file__).resolve().parents[1]/'migrations'
    expected=json.loads((folder/'001_manifest.json').read_text(encoding='utf-8'))
    checksum=hashlib.sha256((folder/'001_runtime.sql').read_text(encoding='utf-8').encode()).hexdigest()
    with store.transaction() as c:
        if manifest(c)!=expected:raise ProtocolError('AGENT_SCHEMA_MISMATCH')
        c.execute('SELECT checksum FROM agent_schema_migrations WHERE version=1')
        row=c.fetchone()
        if not row or row['checksum']!=checksum:raise ProtocolError('AGENT_SCHEMA_CHECKSUM_MISMATCH')
