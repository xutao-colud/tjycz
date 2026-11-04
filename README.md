# 晚辅智管

面向中小学托管机构的作业、错题与教务管理 MVP。包含 Java 后端、Vue 管理后台、教师/家长微信原生小程序。各端共享同一套真实业务接口；演示环境使用虚构数据。

## 项目结构

```text
backend/       Java 17 + Spring Boot 3 + JDBC + Flyway
web/           Vue 3 + TypeScript + Vite 管理后台，以及 Capacitor iOS/Android App
miniprogram/   微信原生小程序
scripts/       本地启动与业务验收脚本
docs/          接口约定、验收与上线说明
compose.yaml   MySQL + Redis + 后端 + Web 部署
```

## 本地运行

建议使用 Java 17、Maven 3.9+、Node.js 22（`.nvmrc`）和 Python 3.11（`.python-version`）。克隆后的环境配置、小程序联调、OCR 安装与自检步骤见 [本地开发环境](docs/local-development.md)。

```bash
bash scripts/dev.sh
```

- 管理后台：<http://localhost:15173>
- 健康检查：<http://localhost:18080/api/health>
- 日志：`.run/backend.log`、`.run/web.log`
- 在启动脚本终端按 `Ctrl+C` 停止本次启动的服务。

开发端口避开了本机已有其他项目的 8080 和 5173。脚本会检查端口占用，不会停止其他项目的服务。

首次启动安装 Web 依赖并构建后端。默认 local profile 使用 H2 文件数据库，数据保存在 `backend/data/`；重启后数据保留。演示初始化只在 local 启用，默认提供两个班级、八位虚构学生、作业和课时记录。请勿将真实学生信息录入演示环境。

只调试小程序时，推荐独立后台启动后端，终端退出不会停止服务：

```bash
bash scripts/backend-local.sh start
bash scripts/backend-local.sh status
# 修改后端代码后更新服务：
bash scripts/backend-local.sh restart --build
```

固定监听 `0.0.0.0:18080`，本机使用 `http://127.0.0.1:18080/api`。脚本检查健康状态和密码公钥端点，就绪后再登录；PID及日志保存在 `.run/`，只停止自己启动的进程。已有其他方式启动的服务会复用，不会被强制停止。

脚本在启动时将构建 JAR 复制到 `.run/backend-runtime.jar` 运行，后续打包不会覆盖正在加载的文件。更新代码后仍需使用上述 `restart --build`。

也可以分别前台启动：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

```bash
cd web
npm ci
npm run dev
```

管理后台的本地演示入口支持管理员、教师、家长角色。切换角色会建立相应会话，后端也会校验权限。演示教师只能访问授权班级，演示家长只能访问已绑定的孩子。

本地体验统一使用账号 `admin`、密码 `admin`。小程序先选择家长、老师或管理员，再通过账号密码登录；后台按所选身份签发对应演示会话，保留学生与班级权限。管理 Web 未传身份时默认管理员。该共享凭据仅在 local 演示环境启用。原有本地机构账号 `admin / Admin123!`、`teacher / Teacher123!`、`parent / Parent123!` 继续兼容，生产环境不会创建这些演示账号。

## 微信小程序

iOS/Android App 工程现位于 `web/ios`、`web/android`，采用同一套账号与业务接口；构建步骤和当前安装包限制见 [App说明](web/APP.md)，本轮验证见 [App验收记录](docs/native-app-acceptance-20261028.md)。

用微信开发者工具导入本仓库的 `miniprogram/`，无需安装小程序构建依赖。本地体验支持家长、教师、校长和管理员，账号与密码均输入 `admin`。具体操作和页面说明见 [小程序说明](miniprogram/README.md)。

接口地址在 `miniprogram/utils/config.js`；克隆到另一台电脑后须改为该电脑的局域网 IPv4，端口为 `18080`、路径为 `/api`。后端监听 `0.0.0.0:18080`，手机需能访问电脑；IP 变化后重新配置并编译。微信局域网开发调试需关闭合法域名校验，必要时开启真机调试及手机本地网络权限；正式发布仍需 HTTPS 合法域名。

小程序使用同一后端登录会话。开发模式可体验教师与家长身份；真实微信身份交换、监护人邀请绑定和订阅消息需要后续配置微信主体、AppID、密钥与模板。

## 密码传输加密

账号登录与创建账号使用 RSA-2048 / OAEP-SHA256（MGF1-SHA256）加密密码，先获取 60 秒、一次性 challenge，再提交 `challengeId` 与 `encryptedPassword`。后端拒绝明文 `password`，过期、篡改和重放请求返回 400，错误凭据返回 401；数据库继续使用 BCrypt 存储密码。Web 优先使用原生 WebCrypto；普通 HTTP/IP 页面使用按需加载的 node-forge 1.4.1 和 `crypto.getRandomValues` 完成同一加密流程。小程序使用同版本公钥模块和 `wx.getRandomValues`，安全随机源不可用时不降级。密码加密不能代替 HTTPS 对页面和会话的保护。运行 `npm --prefix web run test:password` 验证 Web 两种加密路径与后端协议的兼容性。

本地 HTTP 仅用于开发。生产必须配置可信 HTTPS 合法域名，保护公钥分发、登录会话及全部业务数据；应用层 RSA 不替代 TLS。当前仓库不包含生产证书，不能将本地 HTTP 宣称为完整的传输安全部署。接口详情见 `docs/api-contract.md`。

## 业务操作

建议按以下顺序体验：

1. 进入管理后台，查看今日概览、学生及剩余课时。
2. 在签到页勾选学生，批量到校签到。
3. 在作业页布置任务、提交批改，选择完成或需订正。
4. 在错题页记录错题、提交订正、教师复核，并查看复习任务。
5. 在体能与积分页确认打卡，查看对应积分流水。
6. 查看课程报名和当天排课，再签退学生，检查对应课程的课时消耗。
7. 登记线下续费并确认入账，查看购买、赠送和消耗流水。
8. 生成日小结草稿，补充评语后发布，切换家长身份查看与反馈。

生产空库首次配置顺序：管理员登录 → 建班 → 建课程 → 建学生 → 创建教师/家长并分配班级/学生 → 报名 → 续费确认 → 排课 → 开展日常教学。

## 关键规则

家长端现支持逐课程余额预警、续费意向、今日订餐、线下收款码入口和孩子头像；老师、校长按权限查看意向与订餐。日小结反馈为满意/不满意，不满意必填原因。学生接送取消定位与人脸，老师本人签到仍按校区定位范围校验。功能入口、配置与验证范围见 [家长服务验收](docs/family-services-acceptance-20261029.md)。

- 到校记录与课时结算分离。只有有效报名与排课相匹配时，签退才触发该课程消课。
- 同一排课场次只结算一次。重复签退、重试、并发确认续费不应重复记账。
- 余额不足保留待处理消课，不阻断离校登记；续费后可从后台重试。
- 课时流水入账后不可直接编辑或删除。错误消耗通过关联原流水的冲正记录退回。
- 作业、错题、体能奖励按业务事件防重；规则修改不会重写已入账的历史积分。
- 日小结发布后保存内容快照；家长只能看到自己孩子已发布的小结。
- 微信通知未配置时明确展示未配置，系统不会声称消息发送成功。
- 文件读取校验上传者或关联学生权限，不能仅凭文件 ID 访问。

## 验证

```bash
mvn -f backend/pom.xml test
npm --prefix web run build
npm --prefix web run test:audio
node miniprogram/scripts/check-audio.js
python3 scripts/smoke.py --base http://127.0.0.1:18082/api
```

`smoke.py` 仅在 local demo 环境运行，会创建独立命名的验收班级、学生和课程并保留用于核对，不修改演示学生的课时。脚本覆盖权限隔离、并发续费、防重复消课、冲正、作业状态、错题复习、体能奖励和日报发布。

接口详情见 [接口约定](docs/api-contract.md)。本次实际执行的验证结果和范围见 [验收记录](docs/acceptance.md)。录音试听修复见 [音频播放说明](docs/audio-playback.md)；多校区角色与权限见 [校区权限约定](docs/campus-access.md) 和 [校区验收说明](docs/campus-acceptance.md)。

## MySQL 部署

```bash
cp .env.example .env
# 编辑 .env，更换数据库和管理员密码后再启动
# 端口默认后端18080、Web18088，可在 .env 修改
docker compose up -d --build
```

Web 默认地址 <http://localhost:18088>。容器对外只绑定本机地址，部署到服务器时应由 HTTPS 反向代理统一提供访问。

生产配置使用 MySQL 8，禁用演示登录与虚构学生初始化。数据库迁移由 Flyway 执行。管理员通过环境变量初始化，密码需要满足后端强度校验。已有管理员的密码不会因为重启被覆盖。

环境变量：

| 变量 | 用途 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 生产使用 `prod` |
| `DB_URL` | MySQL JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库凭据 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 首次管理员初始化 |
| `APP_UPLOAD_DIR` | 私有附件目录 |
| `PORT` | 后端监听端口，local默认18080、prod默认8080 |
| `OCR_URL` / `OCR_KEY_FILE` | 专用OCR内部地址与服务凭据文件，仅由后端持有 |

MySQL 数据、附件分别存于独立 Docker 卷。Redis 作为后续缓存/任务协调服务预留；当前业务幂等和账目一致性依赖 MySQL 事务及唯一约束，不依赖 Redis。当前文件适配器使用私有本地目录，迁移对象存储需要替换文件服务实现。

## 首版边界

当前交付聚焦单机构的教学与线下收费闭环。以下能力需要后续外部接入或业务扩展：

- 正式微信登录、微信监护人邀请绑定、订阅消息实际发送。
- 微信在线支付、退款、线上财务对账。
- AI 分析、相似题、跨页识题和复杂公式的精排导出。
- 多机构入驻、多校区运营、套餐到期和退费审批、复杂请假调课。
- 企业对象存储、分布式部署、生产监控与备份自动化。

人工签到、账号登录、权限绑定及小程序内查看日小结可用于本地业务验收；正式接入微信和未成年人敏感信息前，需完成对应资质与上线要求。

整页拍照识题已提供小程序首版：专用PaddleOCR服务、候选题框、人工调框校对与勾选入库、裁题PDF。安装与启动见[OCR服务说明](ocr-service/README.md)，实施结果和未验收范围见[扫描验收记录](docs/question-scan-acceptance-20261031.md)。

扫描性能已优化为轻量模型、启动预热、提交后即时派发和快速取回结果。本机隔离环境六次完整 HTTP 流程为 1.82～2.13 秒，见[扫描速度验收](docs/scan-speed-acceptance-20261031.md)。随后在微信开发者工具进行真实上传及页面结果渲染测试，三轮为 3.125、2.837、2.453 秒；首轮超3秒，且仍有漏字和对白排序问题，见[开发者工具扫描验收](docs/scan-devtools-acceptance-20261031.md)。

### 家长对孩子的单独授权

当前默认关闭（`GUARDIAN_CONSENT_REQUIRED=false`）。上传头像、作业图片、录音、视频和扫描题目不再要求家长分别确认SERVICE/MEDIA；界面隐藏同意/撤回操作。账号登录、家长与孩子绑定、任教班级、校区范围、附件访问票据和资料脱敏保护继续生效。历史授权仅保留记录，不自动补授权。需要恢复时将环境变量设为`true`后重启后端。
# tjycz
