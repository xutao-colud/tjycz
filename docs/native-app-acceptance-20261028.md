# 双端 App 工程验收 · 2026-10-28

交付状态：iOS/Android 工程与 App 前端资源已生成；原生编译、安装包和签名分发被本机环境阻塞，未标记为完成。操作指南为 `web/APP.md`。

## 改动范围

复用现有 Vue 3、TypeScript、后端会话与权限，新增 Capacitor 8.5.3 原生工程，App 8.1.2 与 Browser 8.0.5 插件。使用手机专用路由、导航、安全区和角色首页；App 使用 hash 路由、本地打包资源与绝对 API 地址。管理后台仍使用原路由和开发入口，小程序源码与镜像未修改。

新增成长时间轴、教师班级工作台、勾选学生记录成长/同任务批改、关注沟通和本人签到。原业务表单、附件、接送授权、异常处理、课时和财务继续复用原服务。录音、拍照与视频在文件提交处提供入口，摄像头/麦克风只在用户主动操作时请求。

公开服务地址可配置；生产构建强制 HTTPS，拒绝 URL 凭据与查询参数；附件只接受同一机构服务签发的文件地址。保留 RSA-OAEP 密码传输与权限/校区切换后的取消、旧响应隔离。原生运行使用 CapacitorHttp 的 fetch 补丁，不通过放宽后端 CORS 解决跨域。未采集身份证、未假装人脸识别已接入。

## 已执行

| 检查 | 结论与证据 |
| --- | --- |
| iOS 工程生成 | 成功，SPM 路径；`.run/native-app-ios-create.log` |
| Android 工程生成 | 成功；`.run/native-app-android-create.log` |
| App TypeScript 与资源构建 | `build:app:dev` 通过；`.run/native-app-build-final.log` |
| 双端资源/插件同步 | `sync:app` 通过；`.run/native-app-sync-final.log` |
| 管理后台回归构建 | `build` 通过；`.run/native-app-web-regression-final.log` |
| 原附件音视频回归 | 通过短票据续期、上海时间、播放位置、离页/权限切换隔离；`.run/native-app-audio.log` |
| App 边界自检 | HTTPS/凭据/附件地址、录音文件/拒绝/卸载/后台释放、Release网络与权限配置通过；`.run/native-app-tests-final.log` |
| 正式构建配置门禁 | 未提供机构HTTPS地址时按预期拒绝构建；`.run/native-app-release-guard.log`。这不等于正式构建通过 |
| 原生资源与工程静态检查 | 56份复制资源与`dist-app`逐文件哈希一致；plist/XML可解析，Xcode项目语法可解析；不等于原生编译通过 |
| 手机尺寸交互 | iframe真实390 × 640 CSS视口；家长/教师/校区校长/总管理员均实际切换，截图`.run/native-app-shots/` |
| 家长业务实操 | 留言保存、详情与阅读回执入口；作业图片/音频/视频入口可见，未触发真实设备授权 |
| 教师业务实操 | 本人签到后显示时间且去掉重复入口；所选学生成长记录成功；两份同任务作业批改成功，进度/积分/时间轴同步；时间轴直接打开对应作业详情 |

交互验收仅在18084内存H2和独立附件目录，15175代理指向该隔离服务。写入仅包括一条虚构留言、一次老师签到、一条成长点评和两份作业批改。没有写入日常18080业务数据，也未改变生产数据库或外部账户。

浏览器长时间开发iframe在热更时记录过一条 `MutationObserver.observe` 非Node错误，源代码无此调用，来源未确认。最终全新直接App实例在工作台、成长页和作业详情实操后error/warn为0；分别保留 `console.json` 与 `console-final.json`，没有抹除旧记录或声称整轮日志始终为0。

运行时依赖审计0项；开发CLI依赖链xcode→uuid有3项moderate记录，位于构建工具，未用强制降级或全仓依赖升级处理。当前xcode调用uuid.v4，审计所述为v3/v5/v6缓冲区边界；未据此声称漏洞已经修复。原始记录 `.run/native-app-npm-audit.json` 与 `.run/native-app-runtime-audit.json`。

最终结构化结果为 `.run/native-app-acceptance-result.json`。源码工程包为 `artifacts/天健悦成长-App工程-20261028.zip`，包含独立Web/App工程、双端平台工程、已编译调试资源及构建说明，不含数据库、上传附件、会话、证书或node_modules。

## “全部”面板布局修复

复现了390 × 640预览中账号文字竖排、菜单重叠和退出按钮被横向拉长的问题。原因是App弹层与后台导航按钮共用 `.mobile-menu`，后台窄屏规则将弹层设为 `display:inline-flex`。App弹层及其标题选择器改用独立的 `.mobile-menu-sheet`，后台按钮规则未修改。

教师、总管理员和家长菜单已实际打开检查：账号信息和身份选择纵向排列，功能保持三列；总管理员的17项功能能纵向滚动到底，退出登录可打开确认框并取消；角色下拉、关闭面板、家长进入接送页后自动收起均通过。最后在接送页重新打开总管理员“全部”面板留作预览。截图位于 `.run/native-app-shots/menu-fix/`，含修复前、修复后、下拉与滚动底部证据。

本次 `build:app:dev`、`test:app`、`sync:app` 均通过，日志分别为 `.run/native-app-menu-build.log`、`.run/native-app-menu-tests.log`、`.run/native-app-menu-sync.log`。双端内置资源已更新，复制文件与 `dist-app` 的逐文件哈希核对结果记录在 `.run/native-app-menu-layout-result.json`；工程ZIP已同步更新，旧包保存在本次源码备份目录。仅修改App弹层类名及对应CSS选择器，没有添加业务逻辑或写入业务数据；原生编译与真机验收仍未运行。

## 原生构建阻塞与未验范围

本日后续增加了定位签到，见 `docs/attendance-location-acceptance-20261028.md`；小程序、后台、App与本地18080已更新。此前“未修改小程序”的描述仅适用于初次App工程生成阶段。

后续新增班级日报，见 `docs/class-daily-report-acceptance-20261028.md`。后端完整回归增至103项、小程序页面流程增至27项，均通过；已升级V14并更新App工程资源。

后续新增校区总结与独立班级点评，见 `docs/campus-reports-acceptance-20261028.md`。完整回归增至后端108项、小程序31项，均通过；本地服务升级V15并更新双端资源。

本机仅有 Xcode Command Line Tools；`xcodebuild -version` 明确要求完整Xcode。未发现Android SDK，已安装JDK为17和8，当前Capacitor Android需要21。环境记录 `.run/native-app-environment.json`。因此 APK/AAB/IPA 均未生成，双端原生编译及真机安装未运行。

未自动接受SDK许可、安装大型系统工具、配置签名证书、上传商店或使用外部云构建。正式构建还缺机构HTTPS接口配置和自身签名/应用标识。当前手机原生权限、相机/麦克风、音视频编解码、系统返回、HTTPS证书链和外部附件浏览均需要安装后的设备验收。

这是一版基于现有业务的双端工程，未宣称所有小程序页面和原生行为已逐一完成真机等价验收。推送、微信登录、在线支付和人脸服务延续原项目的未接入状态。
