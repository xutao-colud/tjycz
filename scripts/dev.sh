#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for command_name in java mvn node npm python3; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf '缺少运行环境：%s\n' "$command_name" >&2
    exit 1
  fi
done
python3 - <<'PY'
import socket
for port in (18080, 15173):
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', port)) == 0:
            raise SystemExit(f'端口 {port} 已在使用。请使用正在运行的服务，或先停止占用端口的进程。')
PY
if [[ ! -d "$PROJECT_ROOT/web/node_modules" ]]; then
  npm --prefix "$PROJECT_ROOT/web" ci
fi
mvn -f "$PROJECT_ROOT/backend/pom.xml" -DskipTests package
mkdir -p "$PROJECT_ROOT/.run"
BACKEND_PID=''
FRONTEND_PID=''
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$FRONTEND_PID" ]]; then kill "$FRONTEND_PID" 2>/dev/null || true; fi
  if [[ -n "$BACKEND_PID" ]]; then kill "$BACKEND_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM
(cd "$PROJECT_ROOT/backend" && exec java -jar target/wanfu-backend-1.0.0.jar --spring.profiles.active=local --server.port=18080 --server.address=0.0.0.0) >"$PROJECT_ROOT/.run/backend.log" 2>&1 &
BACKEND_PID=$!
(cd "$PROJECT_ROOT/web" && exec node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15173 --strictPort) >"$PROJECT_ROOT/.run/web.log" 2>&1 &
FRONTEND_PID=$!
python3 - "$BACKEND_PID" "$FRONTEND_PID" <<'PY'
import json, os, sys, time, urllib.error, urllib.request
pids = [int(pid) for pid in sys.argv[1:]]
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
deadline = time.monotonic() + 30
ready = False
detail = 'health 未就绪'
while time.monotonic() < deadline:
    for pid in pids:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            raise SystemExit('服务启动失败，请检查 .run/backend.log 与 .run/web.log。')
    try:
        timeout = max(0.001, min(1, deadline - time.monotonic()))
        with http.open('http://127.0.0.1:18080/api/health', timeout=timeout) as response:
            health_up = response.status == 200 and json.load(response).get('status') == 'UP'
        if health_up and time.monotonic() < deadline:
            detail = 'health=UP，challenge 尚未就绪'
            timeout = max(0.001, min(1, deadline - time.monotonic()))
            with http.open('http://127.0.0.1:18080/api/auth/challenge?purpose=LOGIN', timeout=timeout) as response:
                ready = response.status == 200
            if ready:
                break
    except urllib.error.HTTPError as error:
        detail = f'接口返回 HTTP {error.code}'
        if error.code in (404, 405):
            detail += '，请确认后端包含最新 challenge 接口'
        error.close()
    except (OSError, ValueError, AttributeError):
        pass
    time.sleep(min(0.25, max(0, deadline - time.monotonic())))
if not ready:
    raise SystemExit(f'后端在 30 秒内未就绪：{detail}。请检查 .run/backend.log。')
PY
printf '\n晚辅智管已启动\n管理后台：http://127.0.0.1:15173\n后端接口：http://127.0.0.1:18080/api/health\n后端监听：0.0.0.0:18080；真机使用电脑的局域网 IPv4\n日志：.run/backend.log、.run/web.log\n按 Ctrl+C 停止本脚本启动的服务。\n\n'
wait "$BACKEND_PID" "$FRONTEND_PID"
