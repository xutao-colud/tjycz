# 晚辅智管 MVP 接口约定

基础路径 `/api`，JSON，camelCase。成功直接返回对象/数组；错误返回 `{message, code}` 和适当 HTTP 状态。所有业务请求带 `Authorization: Bearer <token>`。日期 `YYYY-MM-DD`，时间 ISO 本地时间，服务器业务时区 Asia/Shanghai。列表直接数组，MVP 不分页。单位：金额为分、课时 BigDecimal、积分整数。演示数据日期以业务当天为准。

## 会话
- `POST /auth/demo` `{role:"ADMIN"|"TEACHER"|"PARENT"}` → `{token,user:{id,name,role},demo:true}`。仅 local profile 开启，家长绑定 1、2 号学生；教师仅授权 1 号班级。
- `GET /auth/challenge?purpose=LOGIN|CREATE_USER`（默认 LOGIN）→ `{challengeId,publicKey,algorithm:"RSA-OAEP-256",expiresAt}`。无需会话，公钥为标准 Base64 SPKI DER RSA-2048；challenge 60 秒有效、一次消费。
- `POST /auth/login` `{username,challengeId,encryptedPassword,role?}` → 会话。拒绝明文 `password` 字段。密码使用 RSA-OAEP SHA-256、MGF1 SHA-256 加密，密文标准 Base64；OAEP label 为 UTF-8 编码的 `JSON.stringify(["wanfu-password-v1","LOGIN",challengeId,normalizedUsername])`；normalizedUsername 与 Java `String.trim()` 一致，只移除两端 U+0000 至 U+0020 字符。每次重试先获取新 challenge，过期/重放/篡改返回400，错误凭据401。解密密码保持原字符、不trim，最多72个UTF-8字节。
- local 环境凭据 `admin/admin` 按 `role:"ADMIN"|"TEACHER"|"PARENT"` 登录现有演示用户，返回 `demo:true`；role 省略或空白默认 ADMIN，非法角色400。正式凭据返回数据库所属角色及 `demo:false`，请求role不覆盖正式权限；非local不启用共享凭据。
- `GET /me` → `{id,name,role,demo}`。
- `GET /health` → `{status:"UP", demo:true}`，无需登录。

## 查询与工作台
- `GET /dashboard?date=` → `{date,studentCount,presentCount,homeworkCount,completedHomeworkCount,pendingCorrectionCount,pendingSummaryCount,lowBalanceCount,todayConsumed,students:[学生],activities:[{id,title,detail,time,type}],subjectProgress:[{subject,total,completed}],weeklyAttendance:[{date,label,present,total}]}`。
- `GET /classes` → `[{id,name,teacherName,studentCount}]`。
- `GET /students?classId=&keyword=` → `[{id,name,gender,grade,classId,className,parentName,parentPhone,attendanceStatus:"NOT_ARRIVED"|"PRESENT"|"LEFT",checkInTime,checkOutTime,homeworkTotal,homeworkCompleted,points,lessonBalance}]`。
- `POST /students` `{name,gender,grade,classId,parentName,parentPhone}` → 学生。仅 ADMIN，有启用的 EVENING 课程时建立首个晚辅账户，初始余额 0；尚未建课允许创建无账户学生，后续通过报名创建独立账户。
- `GET /students/{id}` → 学生。

## 考勤
- `GET /attendance?date=&classId=` → `[{id,studentId,studentName,className,date,status,checkInTime,checkOutTime,source}]`。
- `POST /attendance/check-in` `{studentIds:[1,2],date?}` → `{results:[{studentId,success,message}]}`。
- `POST /attendance/check-out` `{studentIds:[1,2],date?}` → 同上。按当天启用的排课及报名结算课时，重复请求无重复扣减；缺少签到应失败，余额不足则离校成功并保留待处理消课。

管理员的 `/dashboard` 另返回 `teacherCount`、`checkedInTeacherCount`：所选校区范围内应签到、已签到的老师人次。跨校区任教按校区分别统计。学生考勤记录另包含 `campusId`、`campusName`。

## 老师签到
- `GET /teacher-attendance?date=` → `[{teacherId,teacherName,campusId,campusName,date,status:"NOT_CHECKED_IN"|"CHECKED_IN",checkInTime}]`。老板可看全部校区，校长仅授权校区，老师仅本人任教校区，家长不可访问。名册随当前班级授权生成，同一校区任教多个班级只计一次。
- `POST /teacher-attendance/check-in` `{campusId}` → `{id,userId,campusId,date,checkInTime}`。仅教师可为本人签到；打卡日期与时间由服务端上海时区确定，不接受代打卡、补写日期或其他字段。同一老师、校区、日期重复或并发提交保留首次时间。`X-Campus-Id` 仍须与提交校区及当前授权一致。

## 作业
- `GET /homework?date=&studentId=&subject=&status=` → `[{id,taskId,studentId,studentName,className,date,subject:"语文"|"数学"|"英语",type:"WRITTEN"|"ORAL",title,description,status:"NOT_STARTED"|"IN_PROGRESS"|"PENDING_REVIEW"|"CORRECTION"|"PENDING_RECHECK"|"COMPLETED",accuracy,comment,attachments:[文件]}]`。
- `POST /homework/tasks` `{date,subject,type,title,description,studentIds:[1,2],attachmentIds?:[]}` → `{taskId,assignedCount}`。
- `POST /homework/{id}/status` `{status,submissionText?,attachmentIds?,expectedVersion?,requestId?}` → 作业。PARENT 可提交自己孩子作业为 PENDING_REVIEW 或 CORRECTION→PENDING_RECHECK；订正须有文字或未关联该作业的新附件，说明与附件同事务保存。教师可进入 IN_PROGRESS/PENDING_REVIEW，完成及需订正必须走 review。
- `POST /homework/{id}/review` `{status:"COMPLETED"|"CORRECTION",accuracy:0..100整数,comment,expectedVersion?,requestId?}` → 作业。退回订正必须有说明；完成奖励按机构规则每份只入账一次。
- `POST /homework/{id}/attachments` `{fileIds:[]}` → 作业。

## 错题
- `GET /mistakes?studentId=&subject=&status=` → `[{id,studentId,studentName,subject,title,knowledgePoint,errorReason,status:"UNCORRECTED"|"PENDING_REVIEW"|"MASTERED",homeworkId,createdAt,correctionText,teacherComment,attachments:[],corrections:[{id,text,createdAt,attachments:[]}],reviews:[{id,dueDate,status:"PENDING"|"DONE",completedAt}]}]`。
- `POST /mistakes` `{studentId,subject,title,knowledgePoint,errorReason,homeworkId?,attachmentIds?:[]}` → 错题。
- `POST /mistakes/{id}/correct` `{text,attachmentIds?:[]}` → 错题，进入 PENDING_REVIEW。
- `POST /mistakes/{id}/review` `{approved:boolean,comment}` → 错题。通过则 MASTERED、+2 积分，生成建立后 1、3、7 天复习任务，不重复。
- `POST /mistakes/reviews/{reviewId}/complete` → 错题或 `{success:true}`。

## 体能与积分
- `GET /fitness?date=&studentId=` → `[{id,studentId,studentName,date,project,count,duration,confirmed}]`，duration 秒。
- `POST /fitness/batch` `{date,studentIds,project,count,duration}` → `{recordedCount}`，同一学生同一天同项目只记一次并 +2 积分。
- `GET /points?studentId=` → `[{id,studentId,studentName,amount,balance,type,reason,createdAt}]`。
- `POST /points` `{studentId,amount,reason,requestId}` → 流水，TEACHER/ADMIN，单次范围 -100..100、不可透支、请求幂等。

## 课程、账户、收费
- `GET /courses` → `[{id,name,type,unit,price,warningThreshold,enabled}]`。
- `GET /accounts?studentId=` → `[{id,studentId,studentName,courseId,courseName,unit,balance,purchased,gifted,consumed,warningThreshold}]`。
- `GET /lesson-ledger?studentId=&accountId=` → `[{id,accountId,studentId,studentName,courseName,type,amount,balanceBefore,balanceAfter,reason,createdAt,reversed,originalId}]`。
- `GET /renewals?studentId=` → `[{id,studentId,studentName,accountId,courseName,amount,lessons,giftLessons,paymentMethod,status:"PENDING"|"CONFIRMED",note,createdAt}]`。
- `POST /renewals` `{accountId,amount,lessons,giftLessons,paymentMethod:"WECHAT_OFFLINE"|"ALIPAY"|"CASH"|"BANK",note,requestId}` → 续费单，仅 ADMIN。
- `POST /renewals/{id}/confirm` → 续费单，数据库事务入账，重复确认幂等。
- `POST /lesson-ledger/{id}/reverse` `{reason}` → 冲正流水，仅 ADMIN，同一原流水只可冲正一次。
- `GET /settlements` → `[{id,studentId,studentName,courseName,date,status:"PENDING"|"SETTLED",reason}]`，教师/管理员。
- `POST /settlements/{id}/retry` → `{success:true}`。

## 排课与报名
- `GET /schedules?date=` → `[{id,courseId,courseName,classId,className,date,startTime,endTime,units,enabled}]`。
- `POST /schedules` `{courseId,classId,date,startTime,endTime,units}` → 排课，仅 ADMIN，校验时间和课时。
- `GET /enrollments?studentId=` → `[{id,studentId,studentName,courseId,courseName,accountId}]`。
- `POST /enrollments` `{studentId,courseId}` → 报名，创建独立账户，重复幂等，仅 ADMIN。

## 日小结
- `GET /summaries?date=&studentId=` → `[{id,studentId,studentName,date,status,content,teacherComment,version,publishedAt,feedbackSatisfaction,feedbackText,notificationStatus}]`。`feedbackSatisfaction` 为 `SATISFIED` / `DISSATISFIED` / null。
- `POST /summaries/generate` `{date,studentIds}` → `{generatedCount}`，同日同学生唯一，已发布的不覆盖。
- `PATCH /summaries/{id}` `{teacherComment}` → 小结，仅草稿可修改。
- `POST /summaries/{id}/publish` `{confirmedConcerns?:boolean}` → 小结，存在未结束关注事项时必须在核对后显式确认。保存发布快照，版本 1；消息未配置时 notificationStatus=NOT_CONFIGURED，不声称已推送。
- `POST /summaries/{id}/feedback` `{satisfaction:"SATISFIED"|"DISSATISFIED",text?}` → 小结，仅所属家长、仅已发布。不满意时原因去空白后必填，拒绝数字评分字段。

## 家长课程、订餐与头像（V16）

- `GET /family-services?studentId=` → `{student,date,accounts,settings,meal}`。`accounts` 包含各课程可用余额 `availableBalance`、`warningThreshold`、`lowBalance`、当前家长的 `intention` 与 `intentVersion`；`settings` 包含 `campusId,campusName,mealPrice,paymentQr,version`。`date` 为服务器当天。
- `GET /renewal-intents?studentId=` → 意向列表，包含孩子、课程、家长、校区、`intention`、`version` 和更新时间；家长只看到自己仍绑定孩子的意向，老师限任教班级，校长限授权校区。
- `PUT /accounts/{id}/renewal-intent` `{intention:"INTERESTED"|"NOT_NOW",expectedVersion}` → 意向，仅绑定家长可写；每个课程账户、家长唯一，相同意向重试幂等。不会创建收费单或增加课时。
- `GET /meal-orders?date=&studentId=` → 指定日期的订餐选择，含孩子、家长、班级、校区、`ordered`、金额快照 `amount`、`version`；沿用学生范围授权。
- `PUT /students/{id}/meal-order` `{ordered:boolean,expectedVersion}` → 仅绑定家长为服务器当天选择订餐。每个孩子每天唯一，相同选择幂等；拒绝传入日期或金额。校区未配置餐费时不能订餐，订单金额按选择时价格记录，不修改课时账本。
- `GET /campuses/{id}/family-services` → 校区餐费及收款二维码，限有权访问该校区的账号。
- `PUT /campuses/{id}/family-services` `{mealPrice:整数分|null,paymentQrFileId:文件ID|null,expectedVersion}` → 老板或该校区校长维护。餐费非负、最大100000000分，null暂停订餐。收款码必须是本校区单独上传的图片，禁止复用学生附件。二维码不提供支付回调或自动到账。
- `PUT /students/{id}/avatar` `{avatarFileId:文件ID|null}` → 头像，限有学生访问权的账号，复用文件上传、图片类型、校区和媒体同意校验。学生响应附带 `avatar` 文件对象或null。

版本未创建时传0；有记录时传最新version，不同更新冲突返回409。`X-Campus-Id`进一步缩小已有授权范围。收款码、头像通过短效文件访问票据读取，预览前可用 `GET /files/{id}/access` 刷新票据。

学生到校由老师人工确认，无需地理位置；接送授权不要求人脸，仍核验授权有效期、接送人和接送码。旧 `GET /pickup-face/status` 返回停用状态，旧人脸核验提交返回410；老师本人定位签到规则继续保留。

## 文件与设置
- `POST /files` multipart field `file` → `{id,name,url,contentType,size}`，图片/音频/视频，大小 ≤20MB。返回的 URL 带短期文件访问票据；读取时验证会话、上传人或关联学生的权限及媒体授权。
- `GET /files/{id}?ticket=` → 文件流；`GET /files/{id}/access`由有效会话刷新票据，路径不可由用户指定。不支持URL携带长期会话令牌。
- `GET /settings` → `{institutionName,campusName,teacherName,faceRecognitionEnabled:false,wechatConfigured:false,pointsRules:[{event,points}],demo}`。
- `GET /audit-logs` → `[{id,actorName,action,detail,createdAt}]`，仅 ADMIN。

## 基础管理（保障空库能初始化）
- `POST /classes` `{name,teacherName}` → 班级，仅 ADMIN。
- `POST /courses` `{name,type:"EVENING"|"HOLIDAY"|"WEEKEND",unit:"次"|"节"|"小时",price,warningThreshold}` → 课程，仅 ADMIN。
- `GET /users` → `[{id,name,username,role,classIds:[],studentIds:[]}]`，仅 ADMIN，无密码/凭证。
- `POST /users` `{name,username,challengeId,encryptedPassword,role:"TEACHER"|"PARENT",classIds?:[],studentIds?:[]}` → 用户，仅 ADMIN。密码使用 CREATE_USER challenge 和相同 RSA-OAEP 算法，label 的 purpose 改为 `CREATE_USER`；拒绝明文 `password`。密码强度校验，username唯一；授权关系存数据库。
- `PATCH /settings` `{institutionName,campusName,teacherName,pointsRules:[{event,points}]}` → 设置，仅 ADMIN。积分规则由后端实际使用。稳定事件键：`HOMEWORK_COMPLETED`（默认5）、`MISTAKE_MASTERED`（默认2）、`FITNESS`（默认2）。

## 本地演示与生产边界
local：H2 文件持久化、8 名虚构学生、两个班级、教师仅班级 1、家长仅学生 1/2、每天仅初始化一次当日演示排课和学习数据。生产：关闭 demo 登录，配置管理员密码、MySQL、私有文件目录；微信真实登录、订阅消息、在线支付尚需外部资质和凭据。学生接送不使用人脸服务。不得静默模拟成功。Web 使用 `/api` Vite 代理到 18080，开发端口 15173。小程序 apiBase `http://localhost:18080/api` 可修改。生产后端容器端口仍为 8080；本地端口避开用户已有其他项目。

## 一期成长与跟进

所有新增接口沿用已有学生、班级和校区服务端授权。`date` 为上海业务日期；父母只能查看绑定孩子，教师只能访问授权班级，校长仅授权校区，老板可汇总全部校区。新操作的 `requestId` 在同用户、同操作、同记录范围内防重；同请求号换内容返回 409。`expectedVersion` 冲突返回 409。

- `GET /growth?date=&studentId=` → `{date,students,student,teachers,homework,timeline,concerns,openConcernCount,messages,fitness,fitnessPause,pointsDelta,points,summary,readOnly}`。无可访问学生时 `student=null`。仅 OPEN/IN_PROGRESS 的 ISSUE 计为关注数量。积分按 ledger 实际入账日净额计算；运动时长单位秒，客户端只合计 confirmed 记录。
- `GET /workbench?date=&classId=` → `{date,classes,classId,scheduled,readOnly,students,homework,concerns,messages,summaries}`。有效排课加报名学生去重；无有效排课返回在册名单，`scheduled=false`。
- `GET /homework/{id}` → 原作业字段加 `{version,submittedAt,reviewedAt,completedAt,updatedAt,actorName,reviewerName,history,mistakes,attachments}`。history 追加保留每次提交与批改；老记录未知时间仍为 null。
- `POST /growth/notes` `{date,studentIds,type:PREPARATION|BEHAVIOR|COMMENT,text,attachmentIds?,occurredAt?,requestId}` → `{results:[{studentId,id,success}]}`。每次最多 6 个附件；历史日期未提供发生时间时不伪造时刻。
- `POST /growth/fitness-pause` `{date,studentIds,reason,measures,occurredAt?,requestId}` → `{results}`。不扣积分，不自动建立关注事项，保留修改历史。
- `POST /homework/batch-review` `{taskId,status,accuracy?,comment?,items:[{id,expectedVersion,accuracy?,comment?}],requestId}` → `{results:[{id,success,homework?,message?,status?}]}`。同任务、显式作业列表，1–200 份；所有条目权限和任务归属先校验，任何越权整批拒绝。版本冲突、业务条件和数字格式错误逐份失败，其他合法项可成功。正确率不默认满分。
- `POST /summaries/batch-publish` `{summaryIds,confirmedConcernStudentIds?:[]}` → `{results:[{id,success,summary?,message?}]}`。发布前按学生显式确认未结束关注；草稿读取时汇总当前数据，已发布快照不变。
- `GET /follow-ups?date=&studentId=&kind=ISSUE|MESSAGE` → 事项列表，含 student/class/campus、负责人、版本及状态。MESSAGE 不计入成长关注数。
- `POST /follow-ups` `{date,studentId,kind,type?,title?,reason,measures?,needsParent?,parentAction?,ownerId?,nextFeedbackAt?,sourceType?,sourceId?,attachmentIds?,requestId}` → 详情。ISSUE 须老师登记、提供原因/措施/是否需家长配合；需配合时必须说明。MESSAGE 允许家长提交，自动交给班级老师。可关联本学生 HOMEWORK/ATTENDANCE/FITNESS/NOTE；反馈时间使用 `YYYY-MM-DDTHH:mm`。
- `GET /follow-ups/{id}` → 原字段加 `{logs,canManage,lastLogId}`。日志含操作人快照、角色、动作、时间、文字、附件、readByStaff/readByParent。
- `POST /follow-ups/{id}/read` `{lastLogId}` → `{success}`。显示详情后提交已读位置，单用户只前进。
- `POST /follow-ups/{id}/reply` `{action:ACK|REPLY|PARENT_DONE,text?,attachmentIds?,requestId}` → 详情。家长可使用三项动作，老师补充回复使用 REPLY；不自动解决事项，撤销后不允许回复。
- `PUT /follow-ups/{id}/status` `{status,text,expectedVersion,requestId}` → 详情。OPEN→IN_PROGRESS/CANCELLED；IN_PROGRESS→RESOLVED/CANCELLED；RESOLVED→IN_PROGRESS。仅负责人或授权管理员处理，取消另限制为创建人或授权管理员，必须有处理说明/原因。
- `GET /growth/owners?studentId=` → 该学生班级老师列表。
- `PUT /follow-ups/{id}/owner` `{ownerId,text,expectedVersion,requestId}` → 详情。授权管理员可改派到有该学生权限的负责人，历史追加。

所有已发生事实（考勤、作业状态/批改、运动、成长点评、事项登记）拒绝未来日期。安排未来作业仍可使用原 task 接口。无效发生时间/反馈时间返回 400。跨校区上传时，客户端为所选学生提供 `X-Campus-Id`，服务端仍验证上传人与关联学生权限。详细验收见 `growth-phase1-acceptance.md`。


## 教师拍照、课时与奖励（V17）

沿用老师班级、校长校区、家长孩子权限。写入由服务端校验，`requestId`防重；修改已有配置/兑换时使用当前`expectedVersion`。

- `POST /attendance/photo` `{studentId,photoFileId,action:"CHECK_IN"|"CHECK_OUT"}`：当天照片考勤，返回`notificationCount,wechatStatus,photos`；签退执行接送核验，照片不是人脸识别。
- `GET /student-notifications?studentId=`：站内到离校消息，包含`event,title,message,createdAt,readAt,deliveryStatus,attachments`；`POST /student-notifications/{id}/read`由对应家长标记已读。
- `POST /lesson-grants` `{studentId,courseId,units,reason,requestId}`：增加同校区已有课程课时，自动报名、独立记账，正数且不超过1000；不是收费确认。
- `GET /teaching-capture/status`、`POST /teaching-capture/recognize` `{studentId,photoFileId}`：供应商未接入，返回`NOT_CONFIGURED`与手工确认提示；识别text为空。
- `POST /teaching-capture/save` `{studentId,photoFileId,kind:"MISTAKE"|"HOMEWORK",subject,questionText,requestId,homeworkId?}`：题目手工确认后保存，科目语/数/英。错题正文最多10000字，作业2000字。作业转待批改。
- `POST /mistake-books` `{studentId,subject?,mistakeIds?}`：生成1–100道可访问错题的私有PDF文件对象，走既有短票据文件读取。
- `GET /point-presets`、`POST /point-presets` `{campusId,name,points,enabled?}`、`PUT /point-presets/{id}`同字段加`expectedVersion`：管理员维护固定奖励，1–100整数分。
- `POST /point-awards` `{studentId,presetId,requestId}`：老师按校区启用规则加分，服务端采用规则分值。
- `GET /reward-prizes`、`POST /reward-prizes` `{campusId,name,pointsCost,stock,imageFileId?,enabled?}`、`PUT /reward-prizes/{id}`加`expectedVersion`：管理员维护奖品，封面必须单独上传且不可复用学生附件。
- `GET /prize-redemptions?studentId=`；`POST /prize-redemptions` `{studentId,prizeId,expectedVersion,requestId,attachmentIds?}`：老师现场兑换，原子扣积分、库存并保存快照。
- `POST /prize-redemptions/{id}/photos` `{attachmentIds}`：补充图片凭证，总数最多6张。

新增自动积分事件`SUMMARY_PUBLISHED`，默认2分，在小结审核发布成功时发放；重复发布不重复奖励。配置占位为`OCR_PROVIDER`、`WECHAT_ARRIVAL_TEMPLATE_ID`、`WECHAT_DEPARTURE_TEMPLATE_ID`，尚无供应商请求适配；配置值本身不会启用外部识别或微信推送。

## 整页拍照识题（V18）

新入口独立使用以下任务接口，旧recognize/save仍供手工录题，旧占位的OCR_PROVIDER不会启用新服务。新服务由后端配置`OCR_URL`、`OCR_KEY_FILE`；小程序不持有内部服务凭据。

- `GET /teaching-capture/scan-status` → `{available,message,engine?}`，仅老师/管理员，检查实际OCR就绪状态。
- `POST /teaching-capture/jobs` `{studentId,photoFileId,subject,requestId}` → HTTP202与任务。每操作人最多3个待处理任务；同请求号同参数返回同任务，不同参数409。学生、图片须同校区且具有有效服务/媒体授权。
- `GET /teaching-capture/jobs?studentId=` → 当前范围内最近40个扫描任务。任务包括`id,studentId,studentName,campusId,subject,status,errorMessage,engine,pageWidth,pageHeight,version,createdAt,updatedAt,sourcePhoto,questionCount,importedCount`。
- `GET /teaching-capture/jobs/{id}` → 上述字段加`pagePhoto,questions`。候选含`id,displayNumber,groupName,questionText,regions,warnings,importedMistakeId`。`questionText`自动填入提取的印刷题干、选项及表格文字，`groupName`优先使用章节标题或去题号的题干首行（最多160字）；二者均可校对，导入时将有效`groupName`用作错题标题（通用占位标题回退为题号）。未读到清晰文字时可为空，原题与附图同时保留裁图。`regions`为校正页的归一化`{x,y,width,height}`，每题1–8个范围，不返回后台会话哈希。
- `PATCH /teaching-capture/jobs/{id}/questions` `{expectedVersion,questions:[{id?,displayNumber,groupName,questionText,regions}]}` → 更新后任务。全量替换活动候选，保留1–40题；省略id表示补框；未传旧题标记移除；服务器保留原识别文字。已入库题目不得改写或移除，版本冲突409。
- `POST /teaching-capture/jobs/{id}/import` `{expectedVersion,questionIds:[候选ID]}` → `{results:[{id,success,mistakeId?,message}]}`。仅明确选题入库，逐题事务；已成功候选重试返回同错题ID，失败项可单独重试。裁图由服务端按标准页生成并继承学生权限。
- `POST /teaching-capture/jobs/{id}/retry` `{}` → 仅FAILED任务重新排队，换为本次有效操作会话并增加版本，原图保留。

任务状态为`QUEUED → RECOGNIZING → READY/FAILED`；后台串行处理，超时或遗留执行标为失败，明确重试。识别完成与入库独立；默认不选题，不判对错。读取、修改、重试和入库均为老师/管理员范围，家长只经既有错题接口查看已入库内容。

### 家长授权开关（2026-10-31）

`app.privacy.consent-required`由`GUARDIAN_CONSENT_REQUIRED`配置，默认`false`。`GET /privacy/policy`和`GET /privacy/consents?studentId=`增加`consentRequired`。关闭时`effectiveService/effectiveMedia`表示业务可用，均为`true`，`canConsent=false`；原始SERVICE/MEDIA状态和历史仍如实保留，不等同于监护人曾经同意。`POST /privacy/consents`对绑定家长返回410，无需提交；跨孩子或角色访问仍403。此开关不影响角色、班级、校区、附件阻断、脱敏及资料申请权限。

## 每日家长评价与作业曲线（V19，2026-11-05）

- `GET /daily-feedback?date=&studentId=`：家长仅返回本人的绑定孩子评价；管理者按校区查看；教师403。记录含`studentId,parentId,date,feedbackSatisfaction,feedbackText,feedbackSubmitted,feedbackSubmittedAt,canFeedback`。没有提交时返回默认满意，`feedbackSubmitted=false`，不伪造提交时间。
- `PUT /students/{id}/daily-feedback`：家长提交`{date?,satisfaction:SATISFIED|DISSATISFIED,text?}`，仅上海业务当天；不满意必须填写原因，最多2000字。按孩子、家长、日期唯一，重复保存不重复建记录。父母各自记录；旧`POST /summaries/{id}/feedback`继续要求已发布小结，并转入同一独立评价逻辑。
- `GET /class-satisfaction?date=&classId=`：仅校长／总管理员，校区与班级由后端校验。返回`parentCount,submittedCount,defaultCount,satisfiedCount,dissatisfiedCount,satisfactionPercent`；分母为查询时当前班级有效孩子与家长绑定的组合数，默认满意参与分子，百分比保留1位小数。分母0返回null，未来日期拒绝。
- 教师的小结列表、单份详情及工作台均删除反馈字段；家长小结只带自己的评价，管理员增加`parentFeedback`逐家长记录。旧共用反馈保留作历史，不推测作者、不合并为新评价。
- `GET /homework-trend?studentId=&days=7|30&date=`：仅绑定家长，date默认当天且不能为未来。返回`startDate,endDate,points:[{date,gradedCount,accuracy,errorRate}]`，包含结束日期，按天升序并补足7／30天。正确率为已录入正确率作业的平均值，保留1位小数；错题率为100减正确率。没有批改数据时两项均null，不能当作0%。
- `POST /fitness/batch`继续接受自由填写的`project`（1–80字），`count`和`duration`省略时为0，均须非负整数且至少一项为正；继续使用已有授权、当天／历史时间校验及业务防重。
