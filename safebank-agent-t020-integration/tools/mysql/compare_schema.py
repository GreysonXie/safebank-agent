"""Compare actual exported H2 and MySQL metadata. Exit nonzero on unexplained changes."""
import argparse,json
from pathlib import Path

def compare_schema(h2_structure:dict,mysql_structure:dict)->list[dict]:
    changes=[]
    for table,source in h2_structure.items():
        target=mysql_structure.get(table)
        if target is None:raise ValueError(f'Missing table: {table}')
        sc={x['name']:x for x in source['columns']};tc={x['name']:x for x in target['columns']}
        if sc.keys()!=tc.keys():raise ValueError(f'Column set mismatch: {table}')
        for name,s in sc.items():
            t=tc[name];a=s['type'].upper();b=t['type'].upper();reason='Same SQL domain'
            if a.startswith('TIMESTAMP') and b=='DATETIME':reason='UTC DATETIME(6); offsets normalized, nanos truncated to micros'
            elif (a in ('CHARACTER LARGE OBJECT','CLOB') or a in ('CHARACTER VARYING','VARCHAR') and s['size']>=4000) and b=='LONGTEXT':reason='Raw long text preserved; avoids utf8mb4 row-size limit'
            elif a=='BOOLEAN' and b in ('BIT','TINYINT'):reason='MySQL BOOLEAN alias with explicit 0/1 domain CHECK'
            elif a.replace('CHARACTER VARYING','VARCHAR').replace('CHARACTER','CHAR').replace('INTEGER','INT')!=b:raise ValueError(f'Unexpected type: {table}.{name}: {a}->{b}')
            if s['nullable']!=t['nullable']:raise ValueError(f'Nullability changed: {table}.{name}')
            def default(x):return None if x is None or str(x).upper()=='NULL' else str(x).strip("'").lower().replace('false','0').replace('true','1')
            if default(s['default'])!=default(t['default']):raise ValueError(f'Default changed: {table}.{name}')
            if a in ('CHARACTER VARYING','VARCHAR','CHARACTER','CHAR') and b in ('VARCHAR','CHAR') and s['size']!=t['size']:raise ValueError(f'Length changed: {table}.{name}')
            collation=target.get('collations',{}).get(name)
            if collation and collation!='utf8mb4_0900_bin':raise ValueError(f'Unexpected collation: {table}.{name}')
            changes.append(dict(table=table,column=name,source=s,target=t,collation=collation,reason=reason+('; binary NO PAD preserves case/trailing spaces' if collation else '')))
        if sorted(source['primary'])!=sorted(target['primary']):raise ValueError(f'Primary key changed: {table}')
        for kind in ('foreign','unique'):
            src={tuple(x) for x in source[kind]};dst={tuple(x) for x in target[kind]}
            if not src.issubset(dst):raise ValueError(f'{kind} key removed: {table}: {src-dst}')
        expected_checks=len(source['checks'])+(1 if table=='risk_user_guards' else 0)
        if len(target['checks'])!=expected_checks:raise ValueError(f'CHECK cardinality changed: {table}')
    if set(mysql_structure)-set(h2_structure)!={'agent_service_grants','agent_tool_call_receipts'}:raise ValueError('Unexpected additional tables')
    return changes

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source');p.add_argument('target');p.add_argument('output');args=p.parse_args()
    changes=compare_schema(json.loads(Path(args.source).read_text()),json.loads(Path(args.target).read_text()))
    source=json.loads(Path(args.source).read_text());target=json.loads(Path(args.target).read_text())
    checks=[dict(table=t,source=body['checks'],target=target[t]['checks'],reason='Original DDL predicates moved to named table CHECK; database rendering differs. risk_user_guards adds BOOLEAN 0/1 domain. Cardinality checked; this is not a general SQL semantic equivalence proof.') for t,body in source.items() if body['checks']]
    Path(args.output).write_text(json.dumps(dict(columns=changes,checks=checks,addedTables=['agent_service_grants','agent_tool_call_receipts']),ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'Explained {len(changes)} columns; all source primary/unique/foreign keys retained.')
