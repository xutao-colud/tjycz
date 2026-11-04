#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v python3 >/dev/null 2>&1; then
  printf '缺少运行环境：python3\n' >&2
  exit 1
fi
exec python3 - "$PROJECT_ROOT" "$@" <<'PY'
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

# macOS Java and ps need UTF-8 when the project and OCR key paths contain Chinese characters.
if sys.platform == 'darwin':
    os.environ['LC_ALL'] = 'en_US.UTF-8'
    os.environ['LANG'] = 'en_US.UTF-8'

ROOT = Path(sys.argv[1])
BACKEND = ROOT / 'backend'
JAR = BACKEND / 'target/wanfu-backend-1.0.0.jar'
RUN = ROOT / '.run'
RUNTIME_JAR = RUN / 'backend-runtime.jar'
PID_FILE = RUN / 'backend.pid'
OWNER_FILE = RUN / 'backend.owner.json'
LOG = RUN / 'backend.log'
BASE = 'http://127.0.0.1:18080/api'
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
HELP = '''用法：scripts/backend-local.sh [start|status|stop|restart] [--build]
默认 start。仅在 JAR 缺失或显式 --build 时打包；代码变化后运行 restart --build。
start/restart 使用当前磁盘上的 JAR、local profile、0.0.0.0:18080。
start 遇到已有健康服务会复用，不认领 PID、不重打包。
stop/restart 仅操作本脚本记录且通过归属验证的进程；不会接管手动启动的服务。
就绪要求 health.status=UP 且 /auth/challenge?purpose=LOGIN 返回 HTTP 200。
日志：.run/backend.log；PID：.run/backend.pid；归属凭据：.run/backend.owner.json。
status 只读，不创建或更改本地文件，不发送进程信号。'''


def fail(message):
    raise RuntimeError(message)


def request(path, timeout):
    try:
        with HTTP.open(BASE + path, timeout=timeout) as response:
            return response.status, response.read(65536)
    except urllib.error.HTTPError as error:
        with error:
            return error.code, b''
    except (OSError, urllib.error.URLError, TimeoutError) as error:
        return None, str(error).encode()


def readiness(deadline=None):
    def remaining():
        if deadline is None:
            return 3.0
        return max(0.001, min(1.0, deadline - time.monotonic()))

    status, body = request('/health', remaining())
    try:
        health_up = status == 200 and json.loads(body).get('status') == 'UP'
    except (ValueError, AttributeError, UnicodeError):
        health_up = False
    if not health_up:
        detail = f'HTTP {status}' if status is not None else body.decode(errors='replace')
        return False, False, f'health 未就绪（{detail}）'
    if deadline is not None and time.monotonic() >= deadline:
        return True, False, 'health=UP，尚未确认 challenge'
    status, body = request('/auth/challenge?purpose=LOGIN', remaining())
    if status == 200:
        return True, True, 'health=UP，challenge=HTTP 200'
    if status in (404, 405):
        return True, False, f'health=UP，但 challenge 返回 HTTP {status}：后端版本过旧，请更新 JAR 后由服务所有者重启；现有服务保持运行'
    detail = f'HTTP {status}' if status is not None else body.decode(errors='replace')
    return True, False, f'health=UP，但 challenge 未就绪（{detail}）'


def port_busy():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('0.0.0.0', 18080))
        except OSError:
            return True
    return False


def command_for(java, token, jar=RUNTIME_JAR):
    return [java, f'-Dwanfu.backend-local.owner={token}', '-jar', str(jar),
            '--spring.profiles.active=local', '--server.port=18080', '--server.address=0.0.0.0']


def prepare_runtime():
    temporary = RUNTIME_JAR.with_suffix('.jar.tmp')
    shutil.copyfile(JAR, temporary)
    temporary.replace(RUNTIME_JAR)


def process_field(pid, field):
    result = subprocess.run(['ps', '-ww', '-p', str(pid), '-o', field + '='],
                            capture_output=True, text=True, timeout=2)
    return result.stdout.strip() if result.returncode == 0 else ''


def owner():
    if not PID_FILE.exists() and not OWNER_FILE.exists():
        return None, '没有本脚本的 PID/归属记录'
    try:
        pid = int(PID_FILE.read_text().strip())
        record = json.loads(OWNER_FILE.read_text())
        token = record['token']
        java = record['java']
        jar = record.get('jar', str(JAR))
        if (pid <= 1 or record['pid'] != pid or not isinstance(token, str)
                or re.fullmatch(r'[0-9a-f]{32}', token) is None
                or not isinstance(java, str) or not Path(java).is_absolute()
                or Path(java).name != 'java' or jar not in (str(JAR), str(RUNTIME_JAR))):
            return None, 'PID/归属记录无效，拒绝操作进程'
        command = process_field(pid, 'command')
        if not command:
            return None, f'记录中的 PID {pid} 已退出'
        if (command != ' '.join(command_for(java, token, jar))
                or process_field(pid, 'lstart') != record['started']):
            return None, f'PID {pid} 的归属、启动时间或准确 JAR/backend 路径不匹配，拒绝操作进程'
        return record, f'本脚本管理 PID {pid}'
    except (OSError, ValueError, KeyError, TypeError):
        return None, 'PID/归属记录缺失或无效，拒绝操作进程'


def atomic_write(path, text):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(text)
    temporary.replace(path)


def show_status():
    record, ownership = owner()
    up, ready, detail = readiness()
    print(f'{detail}；{ownership}。', flush=True)
    if up and record is None:
        print('已有服务不归本脚本管理；start 不会重复启动，stop/restart 不会停止它。')
    if ready:
        print(f'本机接口：{BASE}；真机使用电脑的局域网 IPv4:18080（需服务监听 0.0.0.0）。')
    return 0 if ready else 1


def stop():
    record, ownership = owner()
    if record is None:
        if port_busy():
            fail(f'{ownership}；18080 被占用，未发送任何停止信号。请由服务所有者停止。')
        print(f'{ownership}；没有可停止的本脚本服务。')
        return
    pid = record['pid']
    # Recheck immediately before signaling; never signal a PID from a stale file.
    current, reason = owner()
    if current != record:
        fail(f'停止前归属复核失败：{reason}')
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if not process_field(pid, 'command') or process_field(pid, 'stat').startswith('Z'):
            PID_FILE.unlink(missing_ok=True)
            OWNER_FILE.unlink(missing_ok=True)
            print(f'已停止本脚本启动的 PID {pid}。', flush=True)
            return
        if owner()[0] != record:
            fail(f'PID {pid} 已变化，停止等待；未发送额外信号。')
        time.sleep(0.2)
    fail(f'PID {pid} 在 10 秒内未退出；保留记录，未发送 SIGKILL。请查看 {LOG}')


def start(build):
    record, ownership = owner()
    up, ready, detail = readiness()
    if up:
        print(f'复用已有 18080 后端：{detail}；{ownership}。', flush=True)
        if record is None:
            print('未写入 PID 或认领服务；stop/restart 不会停止它。', flush=True)
        if build:
            print('已有服务仍运行，未重新打包；本脚本管理的服务可使用 restart --build。', flush=True)
        if not ready:
            fail(detail)
        return
    if record is not None:
        fail(f'{ownership}，但{detail}；未启动第二个后端。请查看 {LOG}')
    if port_busy():
        fail(f'18080 已被占用，且{detail}；未启动、未打包、未停止任何服务。')
    java = shutil.which('java')
    if not java:
        fail('缺少运行环境：java')
    java = os.path.abspath(java)
    if build or not JAR.is_file():
        mvn = shutil.which('mvn')
        if not mvn:
            fail('JAR 缺失或指定了 --build，但缺少运行环境：mvn')
        print('构建后端 JAR（已有 JAR 的普通 start 不会重打包）……', flush=True)
        subprocess.run([mvn, '-f', str(BACKEND / 'pom.xml'), '-DskipTests', 'package'],
                       cwd=BACKEND, check=True)
    if not JAR.is_file():
        fail(f'构建后仍缺少 JAR：{JAR}')
    # Another launcher may have occupied the port during the build.
    if port_busy():
        fail('构建后发现 18080 已被占用；未启动或停止任何服务。请运行 status。')
    prepare_runtime()
    ocr_config = RUN / 'ocr-config.json'
    if ocr_config.is_file():
        config = json.loads(ocr_config.read_text())
        os.environ.setdefault('OCR_URL', config['url'])
        os.environ.setdefault('OCR_KEY_FILE', config['keyFile'])
    token = uuid.uuid4().hex
    with LOG.open('ab', buffering=0) as logfile:
        logfile.write(f'\n--- backend-local start {time.strftime("%Y-%m-%d %H:%M:%S")} ---\n'.encode())
        process = subprocess.Popen(command_for(java, token), cwd=BACKEND,
                                   stdin=subprocess.DEVNULL, stdout=logfile,
                                   stderr=subprocess.STDOUT, start_new_session=True,
                                   close_fds=True)
    started = process_field(process.pid, 'lstart')
    record = {'pid': process.pid, 'java': java, 'token': token, 'started': started, 'jar': str(RUNTIME_JAR)}
    atomic_write(OWNER_FILE, json.dumps(record, ensure_ascii=False) + '\n')
    atomic_write(PID_FILE, str(process.pid) + '\n')
    print(f'后台 PID {process.pid} 已启动；日志：{LOG}', flush=True)
    deadline = time.monotonic() + 30
    detail = 'health 未就绪'
    while time.monotonic() < deadline:
        if process.poll() is not None:
            fail(f'后端启动退出（退出码 {process.returncode}）。请查看 {LOG}')
        up, ready, detail = readiness(deadline)
        if ready:
            current, reason = owner()
            if current != record or process.poll() is not None:
                fail(f'就绪时归属验证失败：{reason}；未停止任何服务。')
            print(f'后端就绪：{detail}；本机接口 {BASE}；监听 0.0.0.0:18080，真机请使用电脑的局域网 IPv4。', flush=True)
            return
        if up and '版本过旧' in detail:
            fail(detail + '；请使用 restart --build 更新本脚本启动的服务。')
        time.sleep(min(0.25, max(0, deadline - time.monotonic())))
    fail(f'后端在 30 秒内未就绪：{detail}。保留进程和归属记录；请查看 {LOG}，修正后用 restart --build。')


def main():
    arguments = sys.argv[2:]
    if arguments in (['-h'], ['--help']):
        print(HELP)
        return 0
    action = 'start'
    if arguments and arguments[0] != '--build':
        action = arguments.pop(0)
    if action not in ('start', 'status', 'stop', 'restart') or arguments not in ([], ['--build']):
        fail(HELP)
    build = '--build' in arguments
    if build and action not in ('start', 'restart'):
        fail('--build 仅支持 start/restart。\n' + HELP)
    if action == 'status':
        return show_status()
    RUN.mkdir(exist_ok=True)
    with (RUN / 'backend.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            fail('另一个 backend-local start/stop/restart 正在执行，请稍后重试。')
        if action in ('stop', 'restart'):
            stop()
        if action in ('start', 'restart'):
            start(build)
    return 0


try:
    sys.exit(main())
except (RuntimeError, OSError, subprocess.SubprocessError) as error:
    print(f'错误：{error}', file=sys.stderr, flush=True)
    sys.exit(1)
except KeyboardInterrupt:
    print('等待已中断；已独立启动的后端保持运行，可使用 status/stop 管理。', file=sys.stderr)
    sys.exit(130)
PY
