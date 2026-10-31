"""Fresh-process offline recovery fixture; prints no configuration or credentials."""
import json,os,sys
from safebank_agent.store import Store
from safebank_agent.runtime import Runtime
from .test_graph_runtime import Model,Bank
config=dict(json.loads(os.environ['SAFEBANK_AGENT_TEST_DB_CONFIG']),database=sys.argv[1])
model=Model();model.responses=[{'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]},{'kind':'analysis','answer':'本月支出为{{statement_2026_09.expense_minor}}。'}]
runtime=Runtime(Store(config),config,Bank(),model)
try:
 run=runtime.accept(json.loads(sys.argv[2]));runtime.execute(run['id']);saved=runtime.store.get(run['id'])
 print(json.dumps({'state':saved['state'],'threadId':saved['thread_id'],'modelCalls':model.calls}))
finally:runtime.close()
