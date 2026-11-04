# 本地开发环境

仓库包含 Java 后端、Web 管理后台、微信小程序、Capacitor App 工程及独立 OCR 服务。业务数据库、上传照片、OCR 密钥、模型缓存、依赖目录和本机调试配置均在本地生成，不随源码分发。历史备份及 App 工程 ZIP 保留在原电脑，不作为当前源码目录维护。

## 依赖与获取代码

- Git、JDK 17、Maven 3.9+。
- Node.js 22.22.2 和 npm，版本记录在根目录 `.nvmrc`；安装 nvm 后可执行 `nvm install && nvm use`。
- Python 3.11，版本记录在 `.python-version`。OCR 需要独立虚拟环境和 uv；其他启动脚本使用标准库。
- 微信开发者工具用于小程序编译。Docker 仅用于可选的 MySQL 容器环境；本地开发默认用 H2，无需启动 Docker。

```bash
git clone --branch codex/local-setup-20261104 https://github.com/xutao-colud/tjycz.git
cd tjycz
```

macOS 终端若继承了失效的 `JAVA_HOME`，先选择已安装的 JDK 17。以下设置只作用于当前终端；Linux、Windows 按自己的 JDK 安装路径配置 `JAVA_HOME`。

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
java -version
mvn -version
node --version
python3 --version
```

Windows 建议在 WSL2 中执行 Bash 启动脚本；微信开发者工具继续使用可访问的项目目录及后端地址。

## 后端与管理后台

完整前台启动：

```bash
bash scripts/dev.sh
```

脚本首次安装 Web 锁定依赖并打包后端；就绪后打开 `http://127.0.0.1:15173`。后端固定使用 `18080`，健康检查为 `http://127.0.0.1:18080/api/health`。端口已被占用时脚本会退出，避免误停现有服务。按 Ctrl+C 仅停止本脚本启动的进程。

也可将后端与前端分开：

```bash
bash scripts/backend-local.sh start --build
bash scripts/backend-local.sh status
npm --prefix web ci
npm --prefix web run dev
```

后端日志及进程记录在 `.run/`，H2 数据及私有附件在 `backend/data/`。已有数据库会保留，Flyway 启动时应用迁移；更新已有业务库前先按 [备份恢复说明](backup-restore.md) 做离线备份。前台 `dev.sh` 与独立后台启动方式二选一，避免重复占用端口。

本地演示账号为 `admin / admin`，小程序可选家长、教师、校长、管理员身份。此凭据仅用于 local 的虚构数据环境；正式环境由管理员配置机构账号，不能使用本地共享演示凭据。

## 微信小程序

1. 微信开发者工具导入仓库中的 `miniprogram/`；使用机构实际 AppID 及开发者权限。原生 JavaScript/WXML/WXSS 无需 npm 构建。
2. 编辑 `miniprogram/utils/config.js`：模拟器可用 `http://127.0.0.1:18080/api`；手机改为电脑实际局域网 IPv4，例如 `http://192.168.1.10:18080/api`。
3. 仅局域网开发时关闭合法域名校验。手机和电脑需互通，iPhone 的微信需允许访问本地网络；先用手机浏览器验证 `/api/health` 返回 `status: UP`。
4. 重新编译后登录。开发者工具自动化需要在“安全设置”启用服务端口，并允许客户端连接；普通手动编译无需自动化连接。

源码中的当前 IP 不是其他电脑的固定服务地址。正式发布配置机构的 HTTPS 合法域名，不能沿用局域网 HTTP。

## App 浏览器预览与工程

```bash
cp web/.env.app-dev.example web/.env.app-dev.local
npm --prefix web run dev:app
```

预览地址为 `http://127.0.0.1:15175/app-preview.html`，开发代理使用本机 `18080`。构建给手机安装的调试资源前，将 `.env.app-dev.local` 的 `VITE_API_BASE_URL` 改为手机可访问的后端地址，再执行：

```bash
npm --prefix web run build:app:dev
npm --prefix web run sync:app
```

正式配置示例为 `web/.env.app.example`，复制成 `.env.app.local` 后填写真实 HTTPS 地址。以上构建只生成 Web 资源并同步原生工程；APK/AAB/IPA 还需 Android SDK、JDK 21 或完整 Xcode 及相应签名。详见 [App 工程说明](../web/APP.md)。

## 可选 OCR

```bash
uv venv --python 3.11 .run/ocr-venv
uv pip install --python .run/ocr-venv/bin/python -r ocr-service/requirements.txt
bash scripts/ocr-local.sh start
bash scripts/ocr-local.sh status
```

首次安装及模型预热需要联网，模型缓存不进入 Git。OCR 仅监听 `127.0.0.1:18086`，密钥由启动脚本生成于 `.run/ocr.key`；小程序只访问 Java 后端。首次接入 OCR 后，后端需通过 `scripts/backend-local.sh` 重启以读取本地 OCR 配置；先保存工作并按已有数据备份规则操作。未启用 OCR 时，普通作业、考勤和小结功能仍可使用。

完整依赖快照及识别边界见 [OCR 说明](../ocr-service/README.md) 和 `ocr-service/requirements-lock.txt`。

## 自检

```bash
mvn -f backend/pom.xml test
npm --prefix web run build
npm --prefix web run test:app
node miniprogram/scripts/validate.js
node miniprogram/scripts/check-ui.js
node miniprogram/scripts/check-business-flows.js
python3 scripts/check-backend-local.py
```

安装 OCR 依赖后运行：

```bash
.run/ocr-venv/bin/python -m unittest discover -s ocr-service -p 'test_*.py' -v
```

后端集成测试使用独立测试配置；小程序脚本使用本地 wx 模拟。会创建业务记录的 smoke 脚本只能显式指向独立验收环境，不能对现有业务库直接运行。真机网络、相机、录音、微信配置及原生签名需要单独验收。
