"""Validate intent graphs only. No authorization or bank execution lives here."""
import copy,re
from .contracts import ProtocolError,canonical

FIELDS={'schemaVersion','goal','savingsPolicy','nodes'}
PARAMS={'mandate_lookup':set(),'account_query':set(),'mandate_revoke':{'merchantQuery'},'compute_savings':set(),'schedule_create':{'sourceQuery','recipientQuery','firstDate','endDate','dayOfMonth'}}

def workflow_from_receipts(receipts):
    for receipt in reversed(receipts):
        data=receipt.get('data',{})
        if receipt.get('tool') not in ('savings_task_prepare','savings_task_status') or not isinstance(data,dict) or 'code' in data:continue
        identifier=data.get('id')
        if isinstance(identifier,str) and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}',identifier):return identifier
    return None

def validate_task_plan(value):
    def require(ok):
        if not ok:raise ProtocolError('INVALID_TASK_PLAN')
    require(isinstance(value,dict) and FIELDS<=set(value)<=FIELDS|{'analysisMonth'})
    require(type(value['schemaVersion']) is int and value['schemaVersion']==1)
    require(isinstance(value['goal'],str) and 1<=len(value['goal'].strip())<=200)
    require(value['savingsPolicy']=='verified_success_subset')
    month=value.get('analysisMonth')
    require(month is None or isinstance(month,str) and re.fullmatch(r'20\d{2}-(0[1-9]|1[0-2])',month))
    try:require(len(canonical(value).encode('utf-8'))<=16384)
    except (ValueError,TypeError):raise ProtocolError('INVALID_TASK_PLAN') from None
    nodes=value['nodes'];require(isinstance(nodes,list) and 5<=len(nodes)<=8)
    by={};groups={k:[] for k in PARAMS}
    for node in nodes:
        require(isinstance(node,dict) and set(node)=={'id','type','params','dependsOn'})
        key=node['id'];kind=node['type'];params=node['params'];deps=node['dependsOn']
        require(isinstance(key,str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,31}',key) and key not in by)
        require(isinstance(kind,str) and kind in PARAMS and isinstance(params,dict) and set(params)==PARAMS[kind])
        require(isinstance(deps,list) and all(isinstance(d,str) for d in deps) and len(set(deps))==len(deps))
        for field,arg in params.items():
            if field=='dayOfMonth':require(type(arg) is int and 1<=arg<=31)
            else:
                require(isinstance(arg,str) and 1<=len(arg.strip())<=100)
                if field.endswith('Date'):require(re.fullmatch(r'20\d{2}-\d{2}-\d{2}',arg))
        by[key]=node;groups[kind].append(node)
    require(all(len(groups[k])==1 for k in PARAMS if k!='mandate_revoke') and 1<=len(groups['mandate_revoke'])<=4)
    services=[n['params']['merchantQuery'].strip().casefold() for n in groups['mandate_revoke']]
    require(len(set(services))==len(services))
    seen=set()
    while len(seen)<len(nodes):
        ready={k for k,n in by.items() if k not in seen and set(n['dependsOn'])<=seen}
        require(ready);seen|=ready
    m=groups['mandate_lookup'][0];a=groups['account_query'][0];s=groups['compute_savings'][0];t=groups['schedule_create'][0]
    require(not m['dependsOn'] and not a['dependsOn'])
    require(all(n['dependsOn']==[m['id']] for n in groups['mandate_revoke']))
    require(set(s['dependsOn'])=={n['id'] for n in groups['mandate_revoke']})
    require(set(t['dependsOn'])=={s['id'],a['id']})
    return copy.deepcopy(value)
