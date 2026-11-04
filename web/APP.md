# 天健悦成长 · iOS / Android App

本目录同时提供原 Vue 管理后台和 Capacitor 双端 App。App 页面资源随安装包内置，通过同一后端访问业务数据；不依赖微信运行时。

当前交付为可继续构建的双端源码工程和已编译的 App 前端资源，**尚未产出 APK、AAB 或 IPA**。本机缺完整 Xcode、Android SDK 和 JDK 21，未执行原生编译、安装及签名。

## 工程与功能

- `android/`：Android Studio / Gradle 工程，最低 API 24，编译/目标 API 36，使用 JDK 21。
- `ios/App/App.xcodeproj`：Xcode 工程，Swift Package Manager 管理 Capacitor 依赖，最低 iOS 15。
- `src/mobile/`：手机导航、家长成长时间轴、教师工作台、关注沟通、老师签到、现场录音和平台地址适配。
- `dist-app/`：最近一次 `build:app:dev` 的调试资源，连接 `.env.app-dev` 指定的局域网服务。
- `resources/icon.svg`：沿用现有 Lucide Sprout 品牌图标；双端图标和启动图已经生成，许可证随工程保留。

家长、教师、校区校长、老板继续共用原账号与服务端权限。App 有手机首页、底部导航、安全区、安卓返回处理；复用作业、错题、体能积分、接送、请假、课程、账本、续费退款、小结和隐私页面。教师可勾选学生录入成长记录，并按同一作业任务批改，保留逐份结果；家长可留言、回复和反馈配合情况。现有部分经营表格保留横向滚动。

学生接送不采集身份证、人脸或定位；保留接送授权、接送码及老师现场确认。微信登录、订阅消息和微信支付不会自动变成 App 原生登录、推送或支付服务。

## 本地手机预览

需要 Node 22+。先保证本项目后端已就绪，然后在本目录执行：

```bash
npm ci
npm run dev:app
```

入口 `http://localhost:15175/`；390 × 640 尺寸预览 `http://localhost:15175/app-preview.html`。默认开发代理指向 `http://127.0.0.1:18080`；需要独立环境时通过 `APP_DEV_PROXY` 指定后端地址。不要把开发代理当作安装后手机访问服务器的地址。

## Android 调试包

安装 Android Studio 2025.2.1+、JDK 21、Android SDK 36，并由操作者完成 SDK 许可证确认。设置本机的 `ANDROID_HOME` 或 `android/local.properties`；不要提交该本机路径文件。

在 `.env.app-dev.local` 配置手机可访问的测试接口，例如 `VITE_API_BASE_URL=http://电脑局域网IP:18080/api`。手机与电脑需要能互相访问。仓库 `.env.app-dev` 仅提供当前小程序同一地址作为开发默认值。

```bash
npm run build:app:dev
npm run sync:app
cd android
./gradlew assembleDebug
```

预期输出 `android/app/build/outputs/apk/debug/app-debug.apk`。此文件当前尚不存在，不能将工程 ZIP 当成安装包。

## iOS 调试与分发

安装完整 Xcode 26+ 并完成首次启动配置，然后执行：

```bash
npm run build:app:dev
npm run sync:app
npm run open:ios
```

在 Xcode 选择模拟器运行。真机安装和 Archive 分发需要设置自己的 Team、有效 Bundle ID 与签名配置。当前 `cn.tianjianyue.growth` 是开发占位标识，尚未向 Apple 或商店注册。

工程使用 SPM，未要求安装 CocoaPods。iOS `Info-Debug.plist` 的 HTTP 例外只用于 Debug；Release 使用 `Info.plist`。Android 的明文访问例外也只位于 `src/debug/AndroidManifest.xml`。

## 正式构建

复制 `.env.app.example` 为 `.env.app.local`，替换成机构实际 HTTPS 接口；然后：

```bash
npm run build:app
npm run sync:app
```

缺少服务地址、明文 HTTP、地址中夹带账号密码或查询参数时构建会拒绝继续。之后用 Android Studio 生成带签名的 AAB/APK，或用 Xcode Archive 分发。不能直接把当前调试资源用于正式发布。

Capacitor 原生 HTTP 适配保留现有 fetch / FormData；附件短票据地址会指向同一配置服务，外部来源附件被拒绝。Release 不开放全局明文网络，也未设置 `server.url` 或任意网页导航白名单。Android 禁用了应用数据自动备份。

## 验证与边界

```bash
npm run test:app
npm run test:audio
npm run build
npm run build:app:dev
npm run sync:app
```

本轮已通过以上相关检查。另在隔离后端实际走通四角色、家长留言、教师签到、成长记录、两份作业批改以及时间轴直接进入对应作业详情。录音的成功、拒绝授权、授权期间离页、切到后台停止和音轨释放采用模拟媒体设备自检，不代表真实 iPhone/Android 麦克风验收。

原生编译、模拟器/真机安装、摄像头/相册、录音、系统返回、外部附件浏览和签名分发尚未验证。推送、在线支付仍需实际平台接入。

官方依据：[环境要求](https://capacitorjs.com/docs/getting-started/environment-setup)、[集成既有 Web 工程](https://capacitorjs.com/docs/getting-started)、[原生 HTTP 与 FormData](https://capacitorjs.com/docs/apis/http)。
## 课程余额、续费意向与今日订餐

家长从“全部 → 课程与订餐”进入；首页仅在低余额时提供简短提醒入口，，按绑定孩子查看每门课程的可用课时和预警线，选择“有意向续费 / 暂不续费”，选择今天订餐或不订餐，并上传孩子头像。老师和校长在“续费意向与订餐”按权限查看对应孩子、家长、课程和用餐选择。

校长和老板在该页底部选择授权校区，配置每份餐费及上传收款二维码；餐费留空暂停订餐。未上传收款码时家长端明确提示未配置。二维码用于机构线下收款，系统不自动确认到账、增加课时或标记餐费已付；餐费保存下单时的价格快照，订餐不修改课时账本。

需要后端V16。日小结反馈改为“满意 / 不满意”，不满意必须说明原因；历史数字评分按原语义转换为满意度并保留旧记录。学生接送授权采用填写、确认两步，不要求人脸。验收记录见 `../docs/family-services-acceptance-20261029.md`。

## 老师定位签到与学生接送

后台与App的“校区管理 → 设置签到范围”维护每个校区的签到地址、WGS84经纬度和允许半径（50–3000米）。在校区现场可点击“使用当前位置”获取中心点，再核对地址并保存。总管理员可设置全部校区，校长仅可设置授权校区，教师与家长不能修改。

老师本人签到使用所属校区的范围。服务端按当前校区配置校验距离、定位精度和采集时间；未配置地点、未提交位置、超范围、定位超过两分钟或精度不足时不登记。学生到校由老师人工确认，无需定位；学生离校仍核对接送授权和接送码，并按原规则结算课时。

需要配套后端V16。`PUT /api/campuses/{id}/check-in-location` 保存 `{address,latitude,longitude,radiusMeters}`；仅老师本人签到请求携带 `{campusId,location:{latitude,longitude,accuracy,capturedAt}}`，`capturedAt`为Unix毫秒。学生考勤与接送不要求位置字段。

App使用固定版本`@capacitor/geolocation@8.2.4`，已声明iOS用途说明和Android精确/粗略定位权限。只在主动设置地点或签到时取一次位置，不持续追踪，不保存签到者的经纬度。平台配置依据[Capacitor定位插件文档](https://github.com/ionic-team/capacitor-geolocation/blob/main/README.md)，具体实现以当前安装包的README及类型为准。浏览器调试使用localhost或HTTPS；原生安装后的系统定位授权、真实精度及设备行为需另行验收。

## 班级日报

教师从“全部 → 班级日报”进入自己的班级，选择日期后填写班级情况、需跟进事项和后续安排，出勤与作业进度自动汇总。也可从“全部 → 班级日报”进入。校长和老板按校区权限查看与维护，家长无法访问整班记录。

需要后端V14。同班同日保存一份，可编辑回看；版本冲突时须先核对他人的更新。个人成长小结继续沿用已有审核发布流程。

## 校区总结与班级点评

校长和老板从工作台或“全部 → 校区总结”进入，按日期记录所负责校区的总结、重点跟进与后续安排，自动查看出勤、老师签到、作业和班级情况。支持多校区权限，校长仅能操作授权校区。

校区页每个班级都有“点评班级”，也可在班级日报页追加点评。对应班级老师在“班级日报 → 校长点评”查看；点评与老师日报正文单独保存。需要配套后端V15，教师和家长无法访问内部校区总结。点评保存后可在系统内查看，尚未接入外部消息推送。



## 简洁首页与教师拍照工具

需要后端V17。老师首页按孩子展示作业、签到签退和作业消息，拍照操作直接放在孩子卡片内；批量处理点击后展开。家长首页展示作业清单、到离校时间和老师留言，完整成长记录默认收起。底部为首页、作业、消息、全部；积分奖品、课程订餐、增补课时、日报等在全部功能中。

“拍照录题”支持上传照片、手工确认题干、保存错题或作业；OCR与微信订阅尚未接入。错题本可生成私有PDF供打印。到离校会给对应绑定家长生成站内通知，离校仍校验接送授权。老师可对同校区已有课程增加课时并填写原因，收费确认由管理员处理。

“积分与奖品”支持固定规则加分、库存与图片配置、现场兑换及照片记录。日小结审核发布时默认加2分，同次发布防重；规则由管理员设置。完整验收见`../docs/teacher-tools-and-focused-home-acceptance-20261029.md`。
