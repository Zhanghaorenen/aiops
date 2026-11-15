"""Create an isolated snapshot database; never changes the source database."""
import os,pathlib,subprocess,json
ROOT=pathlib.Path(__file__).resolve().parents[3]
PRIVATE=ROOT/'data/local-run'
TARGET=ROOT/'tests/agent_eval/reports/part1'
TARGET.mkdir(parents=True,exist_ok=True)
props=dict(line.split('=',1) for line in (PRIVATE/'dev-secrets.properties').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
env={**os.environ,'MYSQL_PWD':props['spring.datasource.password']}
bin=pathlib.Path(r'D:/Program Files/MySQL/MySQL Server 8.0/bin')
database='aiops_eval_part1_20261113'
def sql(s):
    r=subprocess.run([str(bin/'mysql.exe'),'-h','127.0.0.1','-u','root','-N','-e',s],env=env,capture_output=True,check=True)
    return r.stdout.decode()
if sql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+database+"'").strip():raise SystemExit('Isolated database exists; refusing to overwrite')
backup=PRIVATE/'part1-source-20261113.sql'
subprocess.run([str(bin/'mysqldump.exe'),'-h','127.0.0.1','-u','root','--single-transaction','--set-gtid-purged=OFF','--result-file='+str(backup),'aiops'],env=env,check=True,capture_output=True)
sql('CREATE DATABASE '+database+' CHARACTER SET utf8mb4')
with backup.open('rb') as f:subprocess.run([str(bin/'mysql.exe'),'-h','127.0.0.1','-u','root',database],stdin=f,env=env,check=True,capture_output=True)
print('Isolated DB ready',database,'MySQL',sql('SELECT VERSION()').strip())
