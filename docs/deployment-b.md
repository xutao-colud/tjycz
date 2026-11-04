# B 机部署

目标：`8.163.74.71`，SSH 用户 `codex`，目录 `/home/codex/tjycz`。项目使用独立的 Compose 名称 `tjycz`；该名称决定数据库、附件、OCR 模型卷及网络。不要使用全局 Docker 清理或删除数据卷。

管理后台为 `http://8.163.74.71/`，小程序接口为 `http://8.163.74.71/api`。Nginx 的80端口统一转发 API；后端18080仅绑定本机，数据库、Redis及OCR不发布宿主机端口。正式微信发布仍需机构 HTTPS 合法域名，公网IP的HTTP仅作当前部署联调入口。

## 私有配置

服务器 `.env` 存放独立的数据库凭据、首次管理员凭据及以下绑定项，权限应为0600：

```dotenv
ADMIN_USERNAME=admin
APP_PORT=18080
WEB_PORT=80
WEB_BIND_ADDRESS=0.0.0.0
```

`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`、`ADMIN_PASSWORD` 使用部署时生成的随机值，不复制样例口令，不提交 Git。OCR 内部密钥位于 `.run/ocr.key`，通过 Compose secret 挂载，不能放进小程序。首次管理员口令仅在空库初始化时使用，已有账号不会因修改环境变量自动改密。

## 构建和运行

具备网络和构建资源的机器可直接在仓库根目录执行：

```bash
docker compose -p tjycz -f compose.yaml -f compose.b.yaml build
docker compose -p tjycz -f compose.yaml -f compose.b.yaml up -d
```

本次服务器没有 Java、Maven 和 Node，本机已有通过测试的构建环境。可先在本机打包 Java 17 JAR 和 Web 静态资源，再经 SSH 传到服务器 `.run/runtime/backend/app.jar`、`.run/runtime/web/dist/`，并将 `web/nginx.conf` 放入 `.run/runtime/web/nginx.conf`。核对传输前后的SHA256后，使用已缓存的运行时镜像构建：

```bash
docker build -f deploy/backend-runtime.Dockerfile -t tjycz-backend .run/runtime/backend
docker build -f deploy/web-runtime.Dockerfile -t tjycz-web .run/runtime/web
docker compose -p tjycz -f compose.yaml -f compose.b.yaml build ocr
docker compose -p tjycz -f compose.yaml -f compose.b.yaml up -d --no-build
```

两种方式使用同一业务源码与数据库结构；JAR、静态资源、数据库、上传文件及模型缓存均不提交 Git。运行时镜像应附带对应的源码 revision 标签，便于核对发布版本。

## 检查和维护

```bash
docker compose -p tjycz -f compose.yaml -f compose.b.yaml ps
curl --fail http://127.0.0.1:18080/api/health
curl --fail http://127.0.0.1/api/health
docker compose -p tjycz -f compose.yaml -f compose.b.yaml logs --tail 60 backend
docker compose -p tjycz -f compose.yaml -f compose.b.yaml logs --tail 60 ocr
```

OCR 初次加载会下载模型并预热，进程启动不等于识别已就绪。识别可用性还须用内部健康接口及已认证的 `/api/teaching/scan-status` 核对。CPU和并发条件改变后，扫描耗时需要重新测量，不能沿用开发机的性能结论。

正式库只做读取验证；会创建演示学生或账目记录的 smoke 脚本不指向本机生产服务。更新已有数据库前，先备份MySQL一致快照及同一维护窗口的附件，见 [备份恢复说明](backup-restore.md)。保留旧应用镜像与源码 revision 供回滚；发生迁移失败时先检查状态，不删除卷重试。
