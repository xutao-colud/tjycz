# 一期成长页面视觉与交互验收

final result: passed

本结论覆盖原生小程序页面在本地 WXML 预览器中的布局、内容与主要交互；微信工具实际编译、手机相机/麦克风与微信原生渲染待微信环境验收。业务状态与权限另经真实后端和客户端测试验证。

## 对照依据

- 原型原件：`/var/folders/vr/7h3hfcj16t3d8jl5tt36ptd40000gn/T/codex-clipboard-1206e7e0-ff8a-4d53-bc3f-e47f7cb640ef.png`。
- 稳定副本：`/Users/xutao/.codex/visualizations/2026/10/28/01a1804b-f864-7290-92db-bdfee9dd162e/phase1-reference.png`。
- 实现预览：`http://127.0.0.1:18946/`；使用项目真实页面源码和共享组件，仅接口数据为明确标注的虚构样例。
- 原图 1146 × 1374 像素，三个手机画面。比较时裁去外部画布与状态栏，分别将三个 app 内容区域归一到 375 像素宽；不把手机边框和状态栏作为 app 内容实现。
- 最终浏览器 CSS viewport 为 375 × 812，截图为 1 CSS px 对应 1 图像像素。正文和文档 scrollWidth 均为 375，无水平溢出。长页全页截图保留固定底栏在捕获时的视口位置；其在长图中的中段位置属于截图方式，不是重复导航。
- 比较状态：正常成长、数学待订正详情、两项待处理关注及运动暂停；参考中的具体学生、照片和统计均为示例，实际画面使用独立虚构记录，不比较样例数值是否相同。

## 同图对照证据

所有截图位于 `/Users/xutao/.codex/visualizations/2026/10/28/01a1804b-f864-7290-92db-bdfee9dd162e/`。

| 证据 | 文件 | 尺寸 |
| --- | --- | --- |
| 原型与实现三组全页同图对照 | phase1-reference-comparison.jpg | 2362 × 1510 |
| 关注状态上半屏局部同图对照 | phase1-focused-comparison.jpg | 798 × 710 |
| 今日成长正常状态 | phase1-growth-normal-final.jpg | 375 × 1683 |
| 今日成长关注状态 | phase1-growth-concern-final.jpg | 375 × 1683 |
| 作业详情待订正 | phase1-homework-final.jpg | 375 × 1341 |
| 关注详情与回应区 | phase1-followup-final.jpg | 375 × 965 |
| 教师工作台批改展开 | phase1-workbench-final.jpg | 375 × 1319 |
| 四页面概览 | phase1-pages-overview.jpg | 1580 × 825 |

已打开并检查全页同图对照及局部同图对照，重点比较文字层级、统计卡片、关注提示、时间轴和批改卡片；再单独检查教师工作台与沟通表单。

## 修正记录

| 轮次 | 问题与影响 | 修正 | 复验 |
| --- | --- | --- | --- |
| 1，P2 | 详情上方显示已查看，日志仍写待家长查看，状态相互矛盾 | 已读接口成功后同步当前日志回执 | before: phase1-followup-before-read-fix.jpg；after: phase1-followup-final.jpg；客户端回归和浏览器实操通过 |
| 2，P2 | 统计名称和数值偏小，关注按钮未铺满卡片宽度，选择器出现重复边框与箭头 | 增大统计/时间轴/状态字号，关注提示固定 100% 宽，去除卡片内多余 picker 装饰 | before: phase1-focused-comparison-before-readability-fix.jpg、phase1-reference-comparison-before-readability-fix.jpg；after: 两份最终同图对照；所有最终页面 scrollWidth=375 |

当前无待修复的 P0/P1/P2 视觉问题。

## 五项视觉检查

- 字体：沿用系统 PingFang SC / Helvetica Neue 和原小程序字体系。日期、学生名、关键数值、事件标题与状态层级明确；统计名称 25rpx、数值 36rpx、时间轴标题 29rpx、状态 24rpx。中文长句可换行，不以缩小字号容纳正文。
- 间距与布局：保留日期条 → 学生信息 → 关注/摘要 → 四项统计 → 时间轴的顺序。作业详情为学科导航、任务卡片、老师反馈、附件、订正说明、提交材料、过程历史。四个页面在 375 像素宽下均无横向溢出；表单和逐份结果通过纵向滚动访问。
- 颜色：延用浅蓝页面、白色圆角卡片、蓝色主操作，以及绿/红/橙/紫的语义色。状态同时有文字说明；运动暂停使用中性橙色并说明措施。
- 图片与图标：复用项目已有 PNG 图标及原有真实文件预览组件。没有伪造学生人像、签到抓拍、现场活动照片、音频时长或卡路里。预览中的练习示意是现有预览器的明确虚构素材，不是生产附件。
- 文案：未知时间明示未记录；批改时间与完成时间分开；跨日显示含后续处理；作业未完成、需订正、待复核保持不同含义；不把普通留言计为关注，不把运动暂停写成学生失败；只说明站内提交/查看，不宣称微信推送成功。

有意适配：原系统没有学生头像、人脸签到或运动热量数据，采用现有学生图标与人工登记记录；详情按真实学科筛选并可切换同科任务，而非将不同学科混在一个选中标签下。保留现有底部导航。新增提交材料、历史和处理表单，因此页面长度随真实数据变化。

## 交互检查

浏览器实操通过：作业统计展开与详情跳转；运动暂停明细展开；关注提示进入事项列表；家长已配合操作追加记录、事项仍待跟进；批量批改填写共同值、提交前核对名单、单项冲突后保留选择；作业订正填写与提交前确认。

客户端自动检查补充覆盖：原子订正证据、网络失败保留表单、同请求重试、草稿权限隔离、失败项保留、无任务不记为完成、实际到校与当前在校分开。音频的开始、暂停、错误重试、离页释放通过 8 项模拟回归。实际文件上传下载通过真实 HTTP 联调。

所检查的最终正常、关注、详情、沟通和工作台页面控制台错误均为空。空态、错误态使用现有 state-view 组件及重试入口。

## 后续检查

P3：使用机构实际数据在微信真机检查系统字体、长姓名、照片比例与屏幕字号偏好；相机和麦克风授权、微信音频解码依赖实际设备。本次未伪造这些检查结果，也未执行正式发布。

---

# 接送原型增量验收 · 2026-10-28

final result: passed

本节覆盖本轮接送页面和成长入口；前文保留一期成长页面的历史证据。通过范围是本地浏览器渲染与交互，不能代替微信真机或真实人脸服务验收。

## 对照目标与尺度

- 接送原型：`/var/folders/vr/7h3hfcj16t3d8jl5tt36ptd40000gn/T/codex-clipboard-d0937d5c-f8c9-40ec-9596-8706130f7dbe.png`，1536 × 1024，多屏画板。
- 成长参考：`/var/folders/vr/7h3hfcj16t3d8jl5tt36ptd40000gn/T/codex-clipboard-5ba662b0-edf2-4659-a3fe-a96df586f7f2.png`，1145 × 1374。
- 实现：`http://127.0.0.1:18947/?page=safety&role=PARENT`。预览器读取真实 WXML/WXSS/页面 JS，数据均为标注的虚构样例，不连接日常数据库。
- 浏览器截图为 1280 × 720、deviceScaleFactor 1，居中 app 列 375 CSS px。对照图裁去预览工具栏与外部画布；原型各 app 内容按宽 375 px 等比归一。源图手机系统栏不是应用自绘内容。长表单可滚动，不能将不同高度画板中的首屏内容量当成相同视口。
- 比较状态：家长常用人、填写信息、人脸待接入、确认授权、有效/历史授权、撤销弹窗、教师异常提醒。示例姓名、人数和日期不同，只比较对应结构与状态。

## 已打开的同幅对照与局部证据

以下文件均位于项目 `.run/`，已实际打开后作出结论：

| 状态 | 原型与实现同幅对照 | 实现截图 |
| --- | --- | --- |
| 常用接送人，底部授权常驻 | pickup-contacts-cta-comparison-final.png | pickup-contacts-cta-final.png |
| 填写授权信息 | pickup-step1-comparison-final.png | pickup-step1-final.png |
| 人脸待接入 | pickup-step2-comparison-final.png | pickup-step2-final.png |
| 确认授权与知情勾选 | pickup-step3-comparison-final.png | pickup-step3-final.png |
| 撤销确认 | pickup-revoke-comparison-final.png | pickup-revoke-confirm.png |
| 教师异常提醒 | pickup-teacher-alert-comparison-final.png | pickup-workbench-alert-final.png |

各对照以单屏 375 px 列呈现，文字、按钮和输入框可读，足以作局部检查。额外单独打开成长入口 `pickup-growth-entry.png` 和后台人脸/授权/异常处理截图，检查新增卡片与表单的层级。

## 发现、修正与复拍记录

| 严重度 | 初次证据与问题 | 修正 | 复验依据 |
| --- | --- | --- | --- |
| P2 | pickup-step1.png / pickup-step2-pending.png：底栏最大宽 960rpx，在宽浏览器里超出 375 px 正文 | 授权底栏与管理操作栏最大宽改为 750rpx | pickup-step1-final.png、pickup-step2-final.png、pickup-step3-final.png、pickup-list-final.png及对应同幅图 |
| P2 | pickup-workbench-alert.png：提醒弹窗过宽，和正文不齐 | 弹窗最大宽改为 660rpx | pickup-workbench-alert-final.png、pickup-teacher-alert-comparison-final.png |
| P2 | pickup-contacts-before.png：添加按钮未铺满；pickup-contacts-comparison-final.png：主要授权入口需要下滑，首屏底部为导航 | 添加按钮全宽；两个家长页签共用固定授权栏，移除重复按钮，保留页面底部滚动余量 | pickup-contacts-cta-final.png、pickup-contacts-cta-comparison-final.png；375 px 无横溢出且主要操作可见 |
| P2 | pickup-prototype-shots/03-face-not-configured.png：后台人脸标签直接显示内部枚举 NOT_CONFIGURED | 列表、步骤、确认与历史统一使用中文状态映射，内部判断不变 | pickup-prototype-shots/14-face-chinese-final.png，已打开确认显示“待接入” |

最终比较没有待修复的 P0/P1/P2 视觉问题。

## 五项视觉检查与有意适配

- **字体与内容层级**：沿用项目系统中文字体及字重；标题、分组、接送人姓名、辅助说明和状态有明确层级。表单标签与输入区分开，长说明换行，不压缩为小字以塞满一屏。系统字体并非从原型图准确识别出的专用字体。
- **间距与布局**：复用白色圆角卡片、柔和页面背景和统一输入/选择器；三步进度和固定操作栏保持一致。孩子/常用人选择增加表单长度，是多孩子与复用联系人所需；时间信息可滚动访问。后台采用原有自适应双列布局与同风格弹窗。
- **颜色与状态**：蓝色主操作/选中项、浅蓝说明、浅绿默认与有效状态、浅橙人脸说明、浅红异常和撤销动作均含文字，颜色不作为唯一信息。成长页增加蓝/橙/绿/紫指标色与浅色反馈/订正区。
- **图片与资产**：复用项目已有 PNG 图标和私有附件头像组件；没有从原型复制示例人像当作实际家长。无授权头像时保留原有默认用户图标。供应商未接入时不展示假人脸照片、扫描动画或绿色通过标记。这是用户指定供应商后补下的真实空态，非资产遗漏。
- **文案与状态**：按用户要求移除所有身份证上传、证件号和证件校验界面；保留“人脸核验”步骤与“待接入”。明确区分登记授权、现场核验和实际离校；“先登记，现场核验”需要用户主动选择，确认页还有知情勾选。告警解除文案不承诺可以直接离校，站内提醒不写成微信推送成功。

## 交互与剩余检查

小程序预览完成常用人、三步授权、成功显码、有效/历史、撤销和异常 HOLD 的可视交互；业务脚本 18/18、21 路由结构校验通过。工作台自动提醒的关闭、同版本不重复弹出、新版本再提示、范围切换清理和 HOLD 请求有客户端回归。

后台在独立 15174/18082 环境走通联系人维护、三步授权、人脸真实空态、显码复制、撤销及教师 OPEN → HOLD → RESOLVED；实际结果详见业务验收报告。没有实际拨打示例家长电话。

系统分享拒绝时回退复制，取消时静默；六个分支自检通过。已打开后台最终中文状态与复制出口截图 `pickup-prototype-shots/14-face-chinese-final.png`、`15-share-copy-feedback-final.png`，确认文案与布局。IAB复制和手工复制出口实测通过，未观察到原生分享完成回执，不将其记为消息已发出。

该后台验收tab本轮至结束的error/warn日志检查为0条（limit100，无消息模式过滤、无排除404），记录 `pickup-prototype-shots/console-check.json`。完整实操结果为同目录 `acceptance-result.json`。

实现清单：已修底栏宽度、提醒宽度与首屏主入口；已比较字体/间距/颜色/资产/内容；保留微信原生渲染、系统字号偏好和手机相机权限为设备验收项。人脸供应商适配与正式发布不在本次通过范围。

## 微信原生按钮宽度反馈后的修复

用户随后提供微信开发者工具截图 `codex-clipboard-050691a5-f659-482f-bd6f-d350a986a975.png`，实际显示接送状态卡片收缩居中、四项快捷入口横向溢出。这暴露了前述本地预览的限制：预览 reset 未包含 style v2 的 `button:not([size=mini])` 规则，之前通过不覆盖该原生布局。

本机微信基础库直接含有 `wx-button:not([size=mini]){margin-left:auto;margin-right:auto;width:184px}`，其优先级高于原来的单类样式。将此规则补入预览后，用真实成长页代码复现同一现象：347px 内容区中卡片仅184px，快捷区scrollWidth=450px；证据 `.run/growth-layout-before.json`、`.run/growth-layout-before.jpg`。

仅对成长页的卡片、快捷按钮及同类关注/明细/留言按钮提高选择器优先级并明确宽度和边距；不改全局按钮或业务逻辑。修复后卡片347px，四入口各81.5px，快捷区scrollWidth=clientWidth=347px；接送信息行322px位于卡片内。关注、展开明细和留言也通过边界断言，结构校验、成长和原交互脚本通过。证据 `.run/growth-layout-after.json`、`.run/growth-layout-after.jpg`。两份文件已保守同步至微信工具目录。

本轮尝试原生界面自动检查时，Computer Use 服务启动失败，因此实际微信重新编译画面仍待核对；上述结论是含已确认微信默认规则的浏览器复现与回归结果。
