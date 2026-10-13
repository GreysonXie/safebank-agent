"""Local team demo bootstrap; separate DML users, explicit fresh databases."""
import json,os,re,secrets,signal,subprocess,sys,time,urllib.request
from pathlib import Path
import pymysql

ROOT=Path('/app')
STATE=Path('/var/lib/safebank')
BANK_DB='safebank_team_bank'
AGENT_DB='safebank_team_agent'
USERS={'bank':'safebank_team_bank','agent':'safebank_team_agent','migration':'safebank_team_migrate'}

def database(user,password,database=None):
    return dict(host=os.getenv('MYSQL_HOST','mysql'),port=int(os.getenv('MYSQL_PORT','3306')),user=user,password=password,charset='utf8mb4',ssl={'check_hostname':False},**({'database':database} if database else {}))

def provision():
    path=STATE/'identity.json'
    root_password=os.environ['MYSQL_ROOT_PASSWORD']
    if len(root_password)<20 or root_password.startswith('REPLACE_'):raise RuntimeError('Generate local configuration first')
    admin=database('root',root_password)
    for attempt in range(90):
        try:connection=pymysql.connect(**admin,autocommit=True);break
        except pymysql.MySQLError:
            if attempt==89:raise
            time.sleep(2)
    with connection,connection.cursor() as c:
        if path.exists():
            p=json.loads(path.read_text(encoding='utf-8'))
            if p.get('bankDatabase')!=BANK_DB or p.get('agentDatabase')!=AGENT_DB:raise RuntimeError('Unexpected stored identity')
            for db in (BANK_DB,AGENT_DB):
                c.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s',(db,))
                if c.fetchone()[0]!=1:raise RuntimeError('Stored identity and database volumes disagree')
            return p
        for db in (BANK_DB,AGENT_DB):
            c.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s',(db,))
            if c.fetchone()[0]:raise RuntimeError('Refusing to overwrite an existing unowned database')
        for user in USERS.values():
            c.execute('SELECT COUNT(*) FROM mysql.user WHERE user=%s',(user,))
            if c.fetchone()[0]:raise RuntimeError('Refusing to reuse an existing unowned database user')
        p={key:secrets.token_hex(32) for key in ('bankPassword','agentPassword','migrationPassword','serviceKey')}
        p.update(bankDatabase=BANK_DB,agentDatabase=AGENT_DB)
        for db in (BANK_DB,AGENT_DB):c.execute('CREATE DATABASE `'+db+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin')
        for kind,db in (('bank',BANK_DB),('agent',AGENT_DB),('migration',BANK_DB)):
            user=USERS[kind]
            c.execute("CREATE USER %s@'%%' IDENTIFIED BY %s",(user,p[kind+'Password']))
            grants='ALL PRIVILEGES' if kind=='migration' else 'SELECT, INSERT, UPDATE, DELETE'
            c.execute('GRANT '+grants+' ON `'+db+"`.* TO %s@'%%'",(user,))
        c.execute('GRANT ALL PRIVILEGES ON `'+AGENT_DB+"`.* TO %s@'%%'",(USERS['migration'],))
        STATE.mkdir(parents=True,exist_ok=True)
        path.write_text(json.dumps(p),encoding='utf-8');path.chmod(0o600)
        return p

def seed(p):
    with pymysql.connect(**database(USERS['bank'],p['bankPassword'],BANK_DB)) as connection,connection.cursor() as c:
        c.execute('SELECT COUNT(*) FROM accounts');count=c.fetchone()[0]
        if count:return  # preserve every previously executed demo action
        c.execute('SELECT COUNT(*) FROM transactions')
        if c.fetchone()[0]:raise RuntimeError('Partial data found; refusing fixture overwrite')
        source=re.sub(r'^--.*$','',(ROOT/'seed.sql').read_text(encoding='utf-8'),flags=re.M)
        for statement in source.split(';'):
            if statement.strip():c.execute(statement)
        c.execute('SELECT COUNT(*) FROM accounts');assert c.fetchone()[0]==5
        c.execute('SELECT COUNT(*) FROM transactions');assert c.fetchone()[0]==114
        connection.commit()

def environment(p):
    mode=os.getenv('SAFEBANK_MODEL_MODE','mock')
    if mode not in ('mock','deepseek'):raise RuntimeError('Invalid model mode')
    if mode=='deepseek' and not os.getenv('DEEPSEEK_API_KEY'):raise RuntimeError('Your own DeepSeek key is required')
    cap=int(os.getenv('SAFEBANK_MODEL_SMOKE_CAP','20'))
    if not 1<=cap<=100:raise RuntimeError('Request limit must be between 1 and 100')
    host=os.getenv('MYSQL_HOST','mysql');port=int(os.getenv('MYSQL_PORT','3306'))
    env=dict(os.environ)
    env.update(SPRING_PROFILES_ACTIVE='mysql,agent-gateway,langgraph-agent',SERVER_ADDRESS='0.0.0.0',SAFEBANK_PORT='8134',SAFEBANK_AGENT_PORT='8135',SAFEBANK_BANK_URL='http://127.0.0.1:8134',SAFEBANK_AGENT_BASE_URL='http://127.0.0.1:8135',SAFEBANK_AGENT_SERVICE_KEY=p['serviceKey'],SAFEBANK_MYSQL_URL=f'jdbc:mysql://{host}:{port}/{BANK_DB}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=false&useSSL=true&verifyServerCertificate=false',SAFEBANK_MYSQL_USER=USERS['bank'],SAFEBANK_MYSQL_PASSWORD=p['bankPassword'],SAFEBANK_MYSQL_MIGRATION_USER=USERS['migration'],SAFEBANK_MYSQL_MIGRATION_PASSWORD=p['migrationPassword'],SAFEBANK_AGENT_DB_CONFIG=json.dumps(database(USERS['agent'],p['agentPassword'],AGENT_DB)),SAFEBANK_MODEL_MODE=mode,SAFEBANK_MODEL_SMOKE_CAP=str(cap),SAFEBANK_MODEL_BUDGET_PATH=str(STATE/'model-usage.json'),BANK_MODEL_DEBUG_BUDGET_LIMIT=str(cap),BANK_MODEL_API_KEY='',SAFEBANK_FAULTS_ENABLED='false',LANGSMITH_TRACING='false',LANGCHAIN_TRACING_V2='false',PYTHONPATH=str(ROOT/'agent-service'))
    return env

def main():
    p=provision()
    from safebank_agent.migrate import migrate
    migrate(database(USERS['migration'],p['migrationPassword'],AGENT_DB))
    env=environment(p);children=[]
    def stop(*args):
        for child in children:
            if child.poll() is None:child.terminate()
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    try:
        bank=subprocess.Popen(['java','-Dfile.encoding=UTF-8','-jar',str(ROOT/'bank.jar')],env=env,cwd=ROOT);children.append(bank)
        for attempt in range(120):
            if bank.poll() is not None:raise RuntimeError('Bank service exited during startup')
            try:
                with urllib.request.urlopen('http://127.0.0.1:8134/api/session',timeout=2):break
            except Exception:
                if attempt==119:raise RuntimeError('Bank startup timeout')
                time.sleep(1)
        seed(p)
        agent=subprocess.Popen([sys.executable,'-m','safebank_agent.server'],env=env,cwd=ROOT/'agent-service');children.append(agent)
        print('Team demo started. Use the host loopback webpage; all bank data is synthetic.',flush=True)
        while all(child.poll() is None for child in children):time.sleep(1)
        if any(child.returncode not in (None,0,-15) for child in children):raise RuntimeError('A service exited unexpectedly')
    finally:
        stop()
        for child in children:
            try:child.wait(timeout=10)
            except subprocess.TimeoutExpired:child.kill();child.wait()

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Team startup failed ('+type(error).__name__+'). Check local configuration and container service logs.',file=sys.stderr)
        raise SystemExit(1)
