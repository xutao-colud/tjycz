#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 - "$TASK_ROOT" "$@" <<'PY'
import fcntl,json,os,secrets,signal,socket,subprocess,sys,time,urllib.request
from pathlib import Path
ROOT=Path(sys.argv[1]);RUN=ROOT/'.run';PYTHON=RUN/'ocr-venv/bin/python';SERVER=ROOT/'ocr-service/server.py'
OWNER=RUN/'ocr.owner.json';KEY=RUN/'ocr.key';LOG=RUN/'ocr-service.log'
COMMAND=[str(PYTHON),'-u',str(SERVER)]
FRAMEWORK_PYTHON=PYTHON.resolve().parent.parent/'Resources/Python.app/Contents/MacOS/Python'
action=sys.argv[2] if len(sys.argv)==3 else 'start' if len(sys.argv)==2 else ''
def fail(text):raise RuntimeError(text)
def field(pid,name):
    result=subprocess.run(['ps','-ww','-p',str(pid),'-o',name+'='],capture_output=True,text=True,env={**os.environ,'LC_ALL':'en_US.UTF-8','LANG':'en_US.UTF-8'})
    return result.stdout.strip() if result.returncode==0 else ''
def owner():
    if not OWNER.is_file():return None
    record=json.loads(OWNER.read_text());pid=record['pid']
    commands=[' '.join(COMMAND)]
    if FRAMEWORK_PYTHON.is_file():commands.append(' '.join([str(FRAMEWORK_PYTHON),'-u',str(SERVER)]))
    return record if isinstance(pid,int) and pid>1 and field(pid,'command') in commands and field(pid,'lstart')==record['started'] else None
def busy():
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        try:probe.bind(('127.0.0.1',18086));return False
        except OSError:return True
def health():
    if not KEY.is_file():return None
    try:
        request=urllib.request.Request('http://127.0.0.1:18086/health',headers={'X-OCR-Key':KEY.read_text().strip()})
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=2) as response:return json.load(response)
    except (OSError,ValueError):return None
def stop():
    record=owner()
    if record is None:
        if busy():fail('18086已有其他进程，归属不匹配，未发送停止信号')
        print('没有本脚本管理的OCR进程');return
    if owner()!=record:fail('停止前归属已变化')
    os.kill(record['pid'],signal.SIGTERM)
    deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        if not field(record['pid'],'command') or field(record['pid'],'stat').startswith('Z'):
            OWNER.unlink(missing_ok=True);print('OCR进程已正常停止');return
        time.sleep(.2)
    fail('OCR进程未在10秒内退出，未强制结束，请查看日志')
def start():
    if busy():
        result=health()
        if result and owner():print(result['message']);return
        fail('18086已被占用，未重复启动或认领进程')
    if not PYTHON.is_file():fail('请先按ocr-service/README.md安装独立Python环境')
    if not KEY.exists():KEY.write_text(secrets.token_hex(32)+'\n');KEY.chmod(0o600)
    config=RUN/'ocr-config.json';config.write_text(json.dumps({'url':'http://127.0.0.1:18086','keyFile':str(KEY)},ensure_ascii=False)+'\n');config.chmod(0o600)
    with LOG.open('ab',buffering=0) as output:
        process=subprocess.Popen(COMMAND,cwd=ROOT,env={**os.environ,'OCR_KEY_FILE':str(KEY),'OCR_HOST':'127.0.0.1','OCR_PORT':'18086'},stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
    OWNER.write_text(json.dumps({'pid':process.pid,'started':field(process.pid,'lstart')})+'\n');OWNER.chmod(0o600)
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        if process.poll() is not None:fail('OCR启动退出，请查看.run/ocr-service.log')
        result=health()
        if result and result.get('available'):print('OCR已就绪：http://127.0.0.1:18086（仅供后端调用）');return
        time.sleep(.5)
    print('OCR进程已启动，模型仍在加载；使用status查看，不要重复启动')
try:
    if action not in ('start','status','stop','restart'):fail('用法：scripts/ocr-local.sh [start|status|stop|restart]')
    if action=='status':
        result=health();print(json.dumps({'managed':owner() is not None,'health':result},ensure_ascii=False));sys.exit(0 if result and result.get('available') else 1)
    RUN.mkdir(exist_ok=True)
    with (RUN/'ocr.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if action in ('stop','restart'):stop()
        if action in ('start','restart'):start()
except (RuntimeError,OSError,KeyError,ValueError) as error:
    print('错误：'+str(error),file=sys.stderr);sys.exit(1)
PY
