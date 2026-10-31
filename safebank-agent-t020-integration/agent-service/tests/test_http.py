import threading,unittest
import httpx
from safebank_agent.server import AgentServer

class HTTPTest(unittest.TestCase):
 def test_authentication_body_limit_and_unknown_turn(self):
  class Runtime:
   def close(self):pass
   class Store:
    def get(self,id):return None
   store=Store()
  server=AgentServer(('127.0.0.1',0),Runtime(),'x'*64);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  try:
   with httpx.Client(base_url='http://127.0.0.1:'+str(server.server_address[1])) as client:
    self.assertEqual(client.get('/internal/agent/turns/t').status_code,403)
    headers={'Authorization':'Bearer '+'x'*64}
    self.assertEqual(client.get('/internal/agent/turns/t',headers=headers).json()['state'],'NO_RUN')
    self.assertEqual(client.post('/internal/agent/turns',headers=headers,content=b'x'*65537).status_code,413)
    self.assertEqual(client.post('/internal/agent/turns',headers=headers,json={'grant':'injected'}).status_code,409)
  finally:server.shutdown();server.server_close();thread.join()
