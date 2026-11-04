#!/usr/bin/env node
'use strict';
// Local visual preview only. No packages, backend access, persistent storage or business writes.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const ROOT = path.resolve(__dirname, '..');
const HOST = '127.0.0.1', PORT = 18945;
// Ephemeral keys and challenges belong only to this local preview server.
const previewKeys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048, publicExponent: 65537 });
const previewPublicKey = previewKeys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const challenges = new Map();
const read = name => fs.readFileSync(path.join(ROOT, name), 'utf8');
const appConfig = JSON.parse(read('app.json'));
const pages = appConfig.pages.map(route => route.split('/')[1]);
const labels = { login: '登录', dashboard: '首页', principal: '校长工作台', growth: '今日成长', workbench: '教师工作台', 'follow-ups': '关注与沟通', students: '学生', attendance: '出勤', homework: '作业', 'homework-detail': '作业详情', mistakes: '错题', 'mistake-detail': '错题详情', fitness: '体能', points: '积分', lessons: '课时', summaries: '小结', 'teacher-attendance': '老师签到', campuses: '校区权限', safety: '接送管理', 'pickup-authorization': '临时接送授权', refunds: '退款与记录', privacy: '隐私与授权' };
labels['student-detail'] = '学生详情';
const names = pages.map(page => labels[page] || page);
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function sources() {
  const files = ['app.js', 'app.json', 'app.wxss'];
  for (const route of appConfig.pages.concat(Object.values(appConfig.usingComponents)))
    for (const ext of ['js', 'json', 'wxml', 'wxss']) files.push(route.replace(/^\//, '') + '.' + ext);
  for (const directory of ['utils', 'utils/vendor'])
    for (const file of fs.readdirSync(path.join(ROOT, directory))) if (file.endsWith('.js')) files.push(directory + '/' + file);
  const source = file => file.endsWith('.wxss') ? read(file).replace(/@import\s+["']([^"']+)["'];/g, (_, ref) => source(path.posix.join(path.posix.dirname(file), ref))) : read(file);
  const result = Object.fromEntries(files.map(file => [file, source(file)]));
  const scanResult = path.resolve(ROOT, '../.run/scan-service-result.json');
  if (fs.existsSync(scanResult)) { const value = JSON.parse(fs.readFileSync(scanResult, 'utf8')); delete value.pageImage; result['__scanPreview.json'] = JSON.stringify(value); }
  return result;
}
function browserPreview(files) {
  const query = new URLSearchParams(location.search), config = JSON.parse(files['app.json']);
  const pageNames = config.pages.map(p => p.split('/')[1]);
  const pageName = pageNames.includes(query.get('page')) ? query.get('page') : 'dashboard';
  const roles = ['PARENT', 'TEACHER', 'ADMIN', 'SUPER_ADMIN'];
  const role = roles.includes(query.get('role')) ? query.get('role') : 'TEACHER', teacher = role !== 'PARENT', admin = ['ADMIN', 'SUPER_ADMIN'].includes(role);
  const state = query.get('state'), date = query.get('date') || new Date(Date.now() + 28800000).toISOString().slice(0, 10), time = date + 'T16:30:00';
  const showPickupAlert = state === 'alert' || query.get('alert') === '1';
  const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  const campuses = [{ id: 1, name: '虚构主校区', studentCount: 2, teacherCount: 1, classCount: 1 }, { id: 2, name: '虚构东校区', studentCount: 1, teacherCount: 1, classCount: 1 }];
  const makeSession = role => ({ token: 'visual-preview-fiction-only', user: { id: 9001, name: { PARENT: '虚构陈家长', TEACHER: '虚构林老师', ADMIN: '虚构校区校长', SUPER_ADMIN: '虚构老板' }[role], role, campusIds: [1, 2], campuses, demo: true }, demo: true });
  const session = makeSession(role), user = session.user, storage = new Map([['wanfu.session.v1', session]]);
  const students = [
    { id: 1, name: '样例小禾', grade: '三年级', classId: 1, className: '虚构向日葵班', parentName: '虚构陈家长', attendanceStatus: 'PRESENT', points: 128, lessonBalance: 18, homeworkCompleted: 2, homeworkTotal: 3, checkInTime: time, checkOutTime: null },
    { id: 2, name: '样例星星', grade: '四年级', classId: 1, className: '虚构向日葵班', parentName: '虚构林家长', attendanceStatus: 'LEFT', points: 86, lessonBalance: 3, homeworkCompleted: 3, homeworkTotal: 3, checkInTime: time, checkOutTime: date + 'T18:10:00' },
    { id: 3, name: '样例小舟', grade: '三年级', classId: 2, className: '虚构小树班', parentName: '虚构周家长', attendanceStatus: 'NOT_ARRIVED', points: 42, lessonBalance: 12, homeworkCompleted: 1, homeworkTotal: 3 }
  ];
  students.forEach(s => Object.assign(s, { campusId: s.classId === 1 ? 1 : 2, campusName: s.classId === 1 ? campuses[0].name : campuses[1].name }));
  const children = teacher ? students : students.slice(0, 2);
  const photo = { id: 8001, name: '虚构练习示意.svg', contentType: 'image/svg+xml', size: 400, url: '/files/preview.svg?ticket=fiction-only', expiresAt: '2099-01-01T00:00:00' };
  const audio = { id: 8002, name: '虚构朗读示音.wav', contentType: 'audio/wav', size: 8044, url: '/files/preview.wav?ticket=fiction-only', expiresAt: '2099-01-01T00:00:00' };
  const identity = { studentId: 1, studentName: students[0].name, className: students[0].className, date, createdAt: time };
  const tasks = ['朗读与课后练习', '两位数乘法练习', '英语单词书写'].map((title, i) => Object.assign({}, identity, {
    id: i + 1, taskId: i + 1, classId: 1, campusId: 1, version: 1, title, subject: ['语文', '数学', '英语'][i], type: i === 0 ? 'ORAL' : 'WRITTEN',
    status: i === 0 ? (teacher ? 'PENDING_REVIEW' : 'COMPLETED') : i === 1 ? 'CORRECTION' : 'COMPLETED',
    submittedAt: date + 'T17:00:00', reviewedAt: date + 'T17:45:00', completedAt: i === 1 ? null : date + 'T18:20:00', updatedAt: date + 'T18:20:00', reviewerName: '虚构林老师',
    description: '虚构样例：认真完成课后练习，并说一说你的解题思路。', accuracy: i === 2 ? 100 : 90,
    comment: '虚构评语：书写认真，注意检查计算过程。', attachments: i === 0 ? [photo, audio] : [photo]
  }));
  const mistakes = ['乘法计算中进位遗漏', '阅读理解的关键词', '英语单词拼写'].map((title, i) => Object.assign({}, identity, {
    id: i + 1, title, subject: ['数学', '语文', '英语'][i], status: i === 0 ? (teacher ? 'PENDING_REVIEW' : 'UNCORRECTED') : i === 1 ? 'UNCORRECTED' : 'MASTERED',
    knowledgePoint: '虚构知识点：两位数乘法与进位', errorReason: '虚构错因：计算时漏写进位。', teacherComment: '虚构评语：请把每一步写清楚。', attachments: [photo],
    corrections: [{ id: 11, text: '虚构订正：重新列竖式，标出进位后完成验算。', createdAt: time, attachments: [photo] }],
    reviews: [{ id: 12, dueDate: date, status: 'PENDING' }, { id: 13, dueDate: date, status: 'DONE', completedAt: time }]
  }));
  const settings = { institutionName: '虚构成长学堂', campusName: '样例校区', pointsRules: [{ event: 'HOMEWORK_COMPLETED', points: 5 }, { event: 'MISTAKE_MASTERED', points: 3 }, { event: 'FITNESS_COMPLETED', points: 2 }] };
  const account = { id: 1, studentId: 1, studentName: students[0].name, courseId: 1, courseName: '虚构晚辅课程', unit: '课时', balance: 18, refundReserved: 1, availableBalance: 17, purchased: 29, gifted: 2, consumed: 13, warningThreshold: 5 };
  const fixtures = {
    '/health': { status: 'UP', demo: true }, '/me': user, '/settings': settings, '/campuses': campuses,
    '/users': [{ id: 9010, name: '虚构主校区校长', username: 'fictional-principal', role: 'ADMIN', campusIds: [1], campusNames: ['虚构主校区'] }],
    '/teacher-attendance': campuses.map((campus, i) => ({ teacherId: role === 'TEACHER' ? user.id : 9020 + i, teacherName: role === 'TEACHER' ? user.name : ['虚构林老师', '虚构周老师'][i], campusId: campus.id, campusName: campus.name, date, checkInTime: i ? null : time, status: i ? 'NOT_CHECKED_IN' : 'CHECKED_IN' })),
    '/classes': [{ id: 1, name: '虚构向日葵班', campusId: 1, campusName: campuses[0].name }, { id: 2, name: '虚构小树班', campusId: 2, campusName: campuses[1].name }], '/students': children,
    '/dashboard': { studentCount: children.length, presentCount: teacher ? 2 : 1, teacherCount: 2, checkedInTeacherCount: 1, todayConsumed: 3, homeworkCount: teacher ? 9 : 3, completedHomeworkCount: teacher ? 6 : 2, pendingCorrectionCount: 2, pendingSummaryCount: 1, students: children,
      activities: [{ id: 1, title: '虚构样例：小禾完成数学作业', detail: '认真检查，收获新的进步。', time }, { id: 2, title: '虚构样例：星星完成体能打卡', detail: '坚持运动，元气满满。', time }],
      subjectProgress: [{ subject: '语文', total: 3, completed: 2 }, { subject: '数学', total: 3, completed: 3 }, { subject: '英语', total: 3, completed: 1 }],
      weeklyAttendance: Array.from({ length: 7 }, (_, i) => ({ date: new Date(Date.parse(date) - (6 - i) * 86400000).toISOString().slice(0, 10), total: 3, present: [2, 3, 2, 3, 3, 2, 2][i] })) },
    '/attendance': children.map(s => ({ id: s.id, studentId: s.id, studentName: s.name, classId: s.classId, className: s.className, date, status: s.attendanceStatus, checkInTime: s.checkInTime, checkOutTime: s.checkOutTime, source: 'MANUAL', notificationStatus: 'NOT_CONFIGURED' })),
    '/homework': tasks, '/mistakes': mistakes,
    '/fitness': ['跳绳', '慢跑', '拉伸'].map((project, i) => Object.assign({}, identity, { id: i + 1, project, count: [120, 0, 20][i], duration: [60, 300, 90][i], confirmed: i !== 1 })),
    '/points': ['HOMEWORK_COMPLETED', 'MISTAKE_MASTERED', 'ADJUSTMENT'].map((type, i) => Object.assign({}, identity, { id: i + 1, type, amount: [5, 3, -2][i], balance: [128, 123, 120][i], reason: ['虚构作业完成奖励', '虚构错题掌握奖励', '虚构积分调整说明'][i] })),
    '/courses': [{ id: 1, name: account.courseName }, { id: 2, name: '虚构周末课程' }], '/accounts': [account, Object.assign({}, account, { id: 2, courseId: 2, courseName: '虚构周末课程', balance: 3, refundReserved: 0, availableBalance: 3, purchased: 10, gifted: 0, consumed: 7 })],
    '/lesson-ledger': [{ id: 1, studentId: 1, accountId: 1, courseName: account.courseName, type: 'CONSUME', amount: -1, balanceBefore: 19, balanceAfter: 18, createdAt: time, reason: '虚构晚辅出勤消课' }, { id: 2, studentId: 1, accountId: 1, courseName: account.courseName, type: 'PURCHASE', amount: 30, balanceBefore: 0, balanceAfter: 30, createdAt: time, reason: '虚构课程购买' }],
    '/renewals': [{ id: 1, studentId: 1, accountId: 1, courseName: account.courseName, amount: 180000, lessons: 30, giftLessons: 2, status: 'CONFIRMED', createdAt: time, note: '虚构续费样例，无真实交易' }],
    '/settlements': [{ id: 1, studentId: 1, courseName: '虚构周末课程', date, status: 'PENDING', reason: '虚构待结算样例，请核对课程余额' }],
    '/summaries': ['PUBLISHED', 'DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED'].map((status, i) => Object.assign({}, identity, {
      id: i + 1, studentId: i % 2 + 1, studentName: students[i % 2].name, status, version: i + 1, publishedAt: status === 'PUBLISHED' ? time : null, notificationStatus: 'NOT_CONFIGURED',
      teacherComment: '虚构评语：今天专注认真，明天继续加油。', feedbackRating: null, feedbackSatisfaction: status === 'PUBLISHED' ? 'SATISFIED' : null, feedbackText: status === 'PUBLISHED' ? '虚构家长反馈：谢谢老师的细心陪伴。' : '',
      submittedBy: 9012, submittedName: '虚构授课老师', submittedAt: time, reviewedBy: 9013, reviewedName: '虚构另一位校长', reviewedAt: time, reviewComment: '虚构审核：已核对学习与关注记录。', reviewVersion: i + 1,
      canEdit: teacher && ['DRAFT', 'REJECTED'].includes(status), canSubmit: teacher && ['DRAFT', 'REJECTED'].includes(status), canWithdraw: false, canReview: admin && status === 'IN_REVIEW', canPublish: teacher && status === 'APPROVED',
      reviewHistory: teacher ? [{ id: 100 + i, action: status === 'DRAFT' ? 'GENERATE' : 'SUBMIT', fromStatus: 'DRAFT', toStatus: status === 'DRAFT' ? 'DRAFT' : 'IN_REVIEW', version: 1, actorName: '虚构授课老师', actorRole: 'TEACHER', comment: '虚构流程记录：仅用于预览', content: '虚构历史快照：作业与运动记录', teacherComment: '虚构历史点评', createdAt: time }] : [],
      content: { attendance: '虚构出勤：16:30 准时到校。', homework: '虚构作业：完成 2 / 3 份，书写认真。', mistakes: '虚构错题：正在巩固乘法进位。', fitness: '虚构运动：跳绳 120 次。', points: '虚构积分：今日获得 8 分。', lessons: '虚构课时：晚辅课程剩余 18 课时。' }
    })),
    '/leaves': [{ id: 1, studentId: 2, studentName: students[1].name, leaveDate: date, reason: '虚构请假：家中已有安排，申请整日请假。', status: 'REQUESTED', version: 1, createdByName: '虚构林家长', canReview: admin, canCancel: true, logs: [{ id: 1, actorName: '虚构林家长', action: 'REQUESTED', detail: '虚构记录：监护人提交整日请假', createdAt: time }] }],
    '/pickup-contacts': ['妈妈', '爸爸', '奶奶'].map((relationship, i) => ({ id: i + 1, studentId: 1, name: '虚构' + relationship, relationship, phone: teacher ? undefined : '1380000' + ['8888', '6666', '1234'][i], phoneMasked: '•••• ' + ['8888', '6666', '1234'][i], phoneLast4: ['8888', '6666', '1234'][i], avatar: null, avatarFileId: null, isDefault: i === 0, version: 0, canManage: !teacher, faceStatus: 'NOT_CONFIGURED' })),
    '/pickup-face/status': { providerConfigured: false, status: 'NOT_CONFIGURED', message: '人脸核验服务待接入；未采集照片' },
    '/pickup-alerts': showPickupAlert ? [{ id: 1, studentId: 1, studentName: students[0].name, classId: 1, businessDate: date, pickupName: '虚构来接人', phoneLast4: '9999', reason: '虚构提醒：来接人不在当前授权名单，请联系监护人核对。', parentPhone: teacher ? '13800008888' : undefined, status: 'OPEN', version: 0, canHandle: teacher, createdAt: time, logs: [{ id: 1, action: 'OPEN', actorName: '虚构林老师', note: '虚构记录：孩子继续留在校区，等待监护人确认。', createdAt: time }] }] : [],
    '/pickups': [{ id: 1, studentId: 1, studentName: students[0].name, pickupName: '虚构亲属', relationship: '亲属', phoneMasked: '•••• 5566', guardianName: '虚构陈家长', status: 'ACTIVE', effectiveStatus: state === 'expired' ? 'EXPIRED' : 'ACTIVE', version: 1, mode: 'SINGLE', startDate: date, endDate: date, startTime: '18:30', endTime: '20:00', contactId: null, note: '虚构安排：临时由亲属来接，请老师核对。', faceStatus: 'NOT_CONFIGURED', usedToday: false, usageDates: [], validFrom: date + 'T18:30:00', expiresAt: date + 'T20:00:00', canVerify: teacher && state !== 'expired' && !showPickupAlert, canCheckout: teacher && state === 'verified', canRevoke: !teacher && state !== 'expired', logs: [{ id: 1, actorName: '虚构陈家长', action: 'ACTIVE', detail: '虚构记录：监护人已登记临时接送授权，仍需现场核验。', createdAt: time }] }, { id: 2, studentId: 1, studentName: students[0].name, pickupName: '虚构历史接送人', relationship: '叔叔', phoneMasked: '•••• 1234', guardianName: '虚构陈家长', status: 'REVOKED', effectiveStatus: 'REVOKED', version: 2, mode: 'SINGLE', startDate: date, endDate: date, startTime: '17:30', endTime: '19:00', validFrom: date + 'T17:30:00', expiresAt: date + 'T19:00:00', faceStatus: 'NOT_CONFIGURED', canVerify: false, canCheckout: false, canRevoke: false, logs: [{ id: 2, actorName: '虚构陈家长', action: 'REVOKED', detail: '虚构记录：接送安排已变更，原授权撤销。', createdAt: time }] }],
    '/refunds': ['REQUESTED', 'APPROVED', 'CONFIRMED'].map((status, i) => ({ id: i + 1, renewalId: 1, accountId: 1, studentId: 1, studentName: students[0].name, courseName: account.courseName, unit: '课时', amount: [12000, 8000, 6000][i], lessons: [2, 1, 1][i], giftLessons: 0, reason: '虚构退款样例：退回未使用课程；没有真实交易。', status, version: i + 1, createdAt: time, applicantName: '虚构陈家长', canReview: admin && status === 'REQUESTED', canConfirm: admin && status === 'APPROVED', canCancel: status !== 'CONFIRMED', reviewNote: status === 'REQUESTED' ? '' : '虚构审核意见：核对原单与剩余课时。', confirmedAt: status === 'CONFIRMED' ? time : null, paymentReference: status === 'CONFIRMED' ? 'FICTION-OFFLINE-001' : '', confirmationNote: status === 'CONFIRMED' ? '虚构线下退款确认，仅供页面预览。' : '', ledgerId: status === 'CONFIRMED' ? 43 : null, logs: [{ id: 10 + i, actorName: '虚构陈家长', toStatus: 'REQUESTED', text: '虚构记录：提交退款申请，等待独立审核。', createdAt: time }] })),
    '/refunds/sources': [{ id: 1, renewalId: 1, accountId: 1, studentId: 1, studentName: students[0].name, courseName: account.courseName, amount: 180000, lessons: 30, giftLessons: 2, refundableAmount: 154000, refundableLessons: 26, refundableGiftLessons: 2, availableBalance: 17, status: 'CONFIRMED' }],
    '/privacy/policy': { version: 'FICTION-PREVIEW-v1', serviceText: '虚构说明：仅为展示托管服务授权页面，不收集或处理真实孩子资料。', mediaText: '虚构说明：图片、录音和视频仅使用本地生成示意资料，可单独选择或撤回授权。', retentionText: '虚构说明：本预览仅在内存展示，刷新即重置，没有真实数据保存或脱敏。', externalConfiguration: ['虚构配置提示：正式告知文案与机构联系人须由机构核定。'] },
    '/privacy/consents': { studentId: 1, canConsent: !teacher, service: { status: 'GRANTED' }, media: { status: state === 'withdrawn' ? 'WITHDRAWN' : 'GRANTED' }, effectiveService: true, effectiveMedia: state !== 'withdrawn', history: [{ id: 1, scope: 'SERVICE', granted: true, policyVersion: 'FICTION-PREVIEW-v1', createdAt: time }, { id: 2, scope: 'MEDIA', granted: state !== 'withdrawn', policyVersion: 'FICTION-PREVIEW-v1', createdAt: time }] },
    '/privacy/requests': [{ id: 1, studentId: 1, studentName: students[0].name, type: 'EXPORT', status: 'OPEN', reason: '虚构申请：希望查看孩子的托管记录副本。', createdAt: time }, { id: 2, studentId: 2, studentName: students[1].name, type: 'ERASURE', status: 'APPROVED', reason: '虚构申请：离校后请求资料脱敏。', reviewNote: '虚构审核意见：已完成身份与范围核对。', createdAt: time }, { id: 3, studentId: 1, studentName: students[0].name, type: 'EXPORT', status: 'COMPLETED', reason: '虚构申请：一份已完成的资料副本。', createdAt: time }]

  };
  fixtures['/family-services'] = { student: children[0], date, accounts: fixtures['/accounts'].map(a => ({ ...a, lowBalance: a.availableBalance <= a.warningThreshold, intention: null, intentVersion: 0 })), settings: { campusId: 1, campusName: campuses[0].name, mealPrice: 1800, paymentQr: null, version: 0 }, meal: null };
  if (files['__scanPreview.json']) {
    const sample = JSON.parse(files['__scanPreview.json']);
    const scanPhoto = { id: 891, url: '/files/scan.png', contentType: 'image/png' };
    const sampleJob = { id: 1, studentId: 1, studentName: children[0].name, subject: '数学', status: 'READY', version: 1, createdAt: time, sourcePhoto: scanPhoto, pagePhoto: scanPhoto, engine: sample.engine, questionCount: sample.questions.length, importedCount: 0, questions: sample.questions.map((q, index) => ({ id: index + 1, displayNumber: q.number, groupName: q.group, questionText: q.text, regions: q.regions, warnings: q.warnings, importedMistakeId: null })) };
    fixtures['/teaching-capture/scan-status'] = { available: true, message: '虚构样本的真实OCR结果，仅用于源码预览' };
    fixtures['/teaching-capture/jobs'] = state === 'withdrawn' ? [] : [sampleJob];
    fixtures['/teaching-capture/jobs/1'] = sampleJob;
  }
  const issueRows = state === 'concern' || pageName === 'follow-ups' ? [1, 2].map((id, i) => ({ id, studentId: 1, studentName: students[0].name, classId: 1, className: students[0].className, campusId: 1, campusName: campuses[0].name, businessDate: date, kind: 'ISSUE', type: i ? 'HEALTH' : 'LEARNING', title: i ? '身体不适，运动暂停' : '数学作业需要继续辅导', reason: i ? '虚构情况：孩子有些疲倦' : '虚构情况：计算用时较长', measures: i ? '已安排休息，减少作业量' : '老师正在分步指导', needsParent: true, parentAction: '回家后留意休息，有变化及时沟通', nextFeedbackAt: date + 'T19:00:00', status: i ? 'IN_PROGRESS' : 'OPEN', ownerId: 9001, ownerName: '虚构林老师', createdBy: 9001, createdAt: date + 'T17:40:00', version: 0 })) : [];
  const messageRows = state === 'reminder' ? [{ id: 31, studentId: 1, kind: 'MESSAGE', type: 'REMINDER', title: '明日提醒', reason: '虚构提醒：明天带英语练习册', status: 'OPEN', lastActorRole: 'TEACHER', businessDate: date, version: 0 }] : [];
  const logs = new Map(issueRows.map(i => [i.id, [{ id: i.id * 100, actorName: '虚构林老师', actorRole: 'TEACHER', action: 'CREATE', text: i.reason + '\n已采取措施：' + i.measures, createdAt: i.createdAt, attachments: [], readByStaff: false, readByParent: false }]]));
  const feed = [
    { eventType: 'ATTENDANCE', sourceType: 'ATTENDANCE', sourceId: 1, title: '到校签到', detail: '老师接送拍照签到', action: 'CHECK_IN', status: 'PRESENT', occurredAt: date + 'T16:30:00', attachments: [] },
    { eventType: 'COMMENT', sourceType: 'NOTE', sourceId: 1, title: '课前准备', detail: '整理书包，开始晚辅', status: 'RECORDED', occurredAt: date + 'T16:40:00', attachments: [] },
    ...tasks.map((h, i) => ({ eventType: 'HOMEWORK', sourceType: 'HOMEWORK', sourceId: h.id, title: h.subject + '作业', detail: h.title, status: h.status, occurredAt: date + ['T17:05:00', 'T17:45:00', 'T18:20:00'][i], attachments: h.attachments })),
    { eventType: 'FITNESS', sourceType: 'FITNESS', sourceId: 1, title: '体能活动', detail: state === 'concern' ? '身体不适，已安排休息' : '跳绳与拉伸，适度活动', status: state === 'concern' ? 'PAUSED' : 'RECORDED', occurredAt: date + 'T18:40:00', attachments: [] }
  ].map((r, i) => Object.assign({ id: i + 1, key: 'event-' + i, actorName: '虚构林老师', businessDate: date }, r));
  function growthValue(sid) {
    const student = children.find(s => s.id === sid) || children[0];
    return { date, students: state === 'empty' ? [] : children, student: state === 'empty' ? null : student, readOnly: date > new Date(Date.now() + 28800000).toISOString().slice(0, 10), homework: tasks.filter(h => h.studentId === student?.id), teachers: [{ id: 9001, name: '虚构林老师' }], timeline: feed, points: fixtures['/points'], pointsDelta: 6, fitness: state === 'concern' ? [] : fixtures['/fitness'], fitnessPause: state === 'concern' ? { reason: '虚构情况：身体不适', measures: '安排休息，减少作业量' } : null, concerns: issueRows.filter(i => i.studentId === student?.id), messages: messageRows, openConcernCount: issueRows.filter(i => i.studentId === student?.id && ['OPEN', 'IN_PROGRESS'].includes(i.status)).length, pickup: { defaultContact: { id: 1, name: '虚构妈妈', relationship: '妈妈' }, planned: state === 'planned' ? { pickupName: '虚构亲属', relationship: '亲属', endTime: '20:00', mode: 'SINGLE' } : null, openAlertCount: fixtures['/pickup-alerts'].filter(row => row.studentId === student?.id && row.status !== 'RESOLVED').length, expectedCheckout: '19:00' }, summary: null };
  }
  function followDetail(id) { const item = issueRows.concat(messageRows).find(i => i.id === id); if (!item) return undefined; const history = logs.get(id) || []; return Object.assign({}, item, { logs: history, lastLogId: history.at(-1)?.id || 0, canManage: teacher }); }
  function response(options) {
    const url = new URL(options.url, location.origin), route = url.pathname.replace(/^\/__fixtures/, ''), method = options.method || 'GET';
    if (state === 'error') return { statusCode: 503, data: { message: '虚构错误样例：服务暂不可用，请重试。' } };
    let value = fixtures[route];
    const sid = Number(url.searchParams.get('studentId') || 1), body = options.data || {};
    if (route === '/teaching-capture/jobs/1/questions' && method === 'PATCH') { const saved = fixtures['/teaching-capture/jobs/1']; saved.questions = body.questions.map((q, i) => ({ ...q, id: q.id || 100+i, warnings: [], importedMistakeId: null })); saved.version++; return { statusCode: 200, data: copy(saved) }; }
    if (route === '/pickup-face/verify') return { statusCode: 503, data: { code: 'FACE_NOT_CONFIGURED', message: '人脸核验服务待接入；请先登记并在接送时现场核验。' } };
    if (route === '/growth') value = growthValue(sid);
    if (route === '/growth/comparison-works') value = [{ id: 8001, title: '虚构早期练习', workDate: date }, { id: 8003, title: '虚构近期练习', workDate: date }];
    if (route === '/growth/comparisons' && method === 'POST') {
      value = { id: 99 };
      feed.push({ id: 99, key: 'event-99', eventType: 'COMMENT', sourceType: 'NOTE', sourceId: 99, action: 'COMPARISON', title: '成长对比', detail: body.text, status: 'RECORDED', occurredAt: date + 'T19:00:00', actorName: '虚构林老师', businessDate: date, before: [photo], after: [Object.assign({}, photo, {id:8003})] });
    }
    if (route === '/principal-overview') {
      if (!admin) return { statusCode: 403, data: { message: '暂无校区管理权限' } };
      const roster = state === 'empty' ? [] : children.filter(row => !options.header?.['X-Campus-Id'] || String(row.campusId) === options.header['X-Campus-Id']);
      const arrived = roster.filter(row => ['PRESENT', 'LEFT'].includes(row.attendanceStatus));
      const present = roster.filter(row => row.attendanceStatus === 'PRESENT');
      const alerts = showPickupAlert ? fixtures['/pickup-alerts'].filter(row => row.status !== 'RESOLVED' && roster.some(s => s.id === row.studentId)) : [];
      value = { date, expectedCount: roster.length, arrived, present, warnings: roster.filter(row => row.lessonBalance <= 5),
        todos: alerts.map(row => ({ kind: 'SAFETY', id: row.id, studentId: row.studentId, studentName: row.studentName, date, title: '接送异常', reason: row.reason, owner: '校区值班人员' })),
        classes: fixtures['/classes'].filter(c => roster.some(row => row.classId === c.id)).map(c => ({
          ...c, expected: roster.filter(row => row.classId === c.id).length, arrived: arrived.filter(row => row.classId === c.id).length,
          present: present.filter(row => row.classId === c.id).length, alerts: alerts.filter(a => roster.some(s => s.id === a.studentId && s.classId === c.id)).length
        })) };
    }
    if (route === '/workbench') { const cid = Number(url.searchParams.get('classId') || 1); value = { date, classes: fixtures['/classes'], classId: cid, students: state === 'empty' ? [] : children.filter(s => s.classId === cid), homework: state === 'empty' ? [] : tasks, concerns: issueRows, messages: messageRows, pickupAlerts: showPickupAlert ? fixtures['/pickup-alerts'].filter(row => row.status !== 'RESOLVED') : [], summaries: state === 'empty' ? [] : fixtures['/summaries'].map(s => Object.assign({}, s, { content: '虚构预览：作业完成2/3，待订正1份。\n体能：按实际情况记录。' })), scheduled: state !== 'noschedule', readOnly: false }; }
    if (route === '/growth/owners') value = [{ id: 9001, name: '虚构林老师' }];
    if (route === '/follow-ups') value = issueRows.concat(messageRows).filter(i => (!url.searchParams.has('studentId') || i.studentId === sid) && i.kind === (url.searchParams.get('kind') || 'ISSUE'));
    if (/^\/follow-ups\/\d+$/.test(route)) value = followDetail(Number(route.split('/').pop()));
    if (/^\/homework\/\d+$/.test(route)) { const h = tasks.find(h => h.id === Number(route.split('/').pop())); value = h ? Object.assign({}, h, { history: [{ id: 1, action: h.status, detail: h.comment, status: h.status, actorName: '虚构林老师', occurredAt: h.reviewedAt, attachments: [] }], mistakes: h.status === 'CORRECTION' ? mistakes.slice(0, 1) : [] }) : undefined; }
    if (/^\/mistakes\/\d+$/.test(route)) { const item = mistakes.find(row => row.id === Number(route.split('/').pop())); value = item ? Object.assign({}, item, { history: [{ id: 1, fromStatus: 'UNCORRECTED', toStatus: item.status, actorName: '虚构林老师', createdAt: time, detail: '虚构记录：录入与订正过程可追溯' }] }) : undefined; }
    if (/^\/refunds\/\d+$/.test(route)) value = fixtures['/refunds'].find(row => row.id === Number(route.split('/').pop()));
    if (/^\/files\/\d+\/access$/.test(route)) value = [photo, audio, Object.assign({}, photo, { id: 8003 })].find(row => row.id === Number(route.split('/')[2]));
    if (/^\/privacy\/requests\/\d+\/erasure-check$/.test(route)) value = { blocks: state === 'blocked' ? ['虚构阻断：还有未完结退款，请处理后再核对。'] : [] };
    if (route === '/privacy/consents') value = Object.assign({}, value, { studentId: sid });
    if (/^\/students\/\d+$/.test(route)) value = children.find(s => s.id === Number(route.split('/').pop())) || null;
    if (method !== 'GET') {
      value = route === '/auth/demo' ? session : route.startsWith('/auth/') ? undefined : { assignedCount: 2, recordedCount: 1, generatedCount: 2, results: children.map(s => ({ studentId: s.id, success: true })) };
      if (route === '/follow-ups') { const item = Object.assign({ id: 100 + issueRows.length + messageRows.length, studentName: children.find(s => s.id === body.studentId)?.name, classId: 1, campusId: 1, campusName: campuses[0].name, ownerName: '虚构林老师', ownerId: 9001, status: 'OPEN', businessDate: body.date, createdAt: time, version: 0, createdBy: user.id }, body); if (item.kind === 'MESSAGE') { item.title = '家校留言'; messageRows.push(item); } else issueRows.push(item); logs.set(item.id, [{ id: item.id * 100, actorName: user.name, actorRole: role, action: 'CREATE', text: body.reason, createdAt: time, attachments: [], readByStaff: false, readByParent: false }]); value = followDetail(item.id); }
      const followAction = route.match(/^\/follow-ups\/(\d+)\/(reply|status|read|owner)$/);
      if (followAction) { const id = Number(followAction[1]), item = issueRows.concat(messageRows).find(i => i.id === id), history = logs.get(id); if (item && history) { if (followAction[2] === 'read') history.forEach(l => { l[teacher ? 'readByStaff' : 'readByParent'] = true; }); else { if (body.status) item.status = body.status; item.version++; history.push({ id: history.at(-1).id + 1, actorName: user.name, actorRole: role, action: body.action || 'STATUS', text: body.text || '家长已了解', createdAt: time, attachments: [], readByStaff: false, readByParent: false }); } value = followDetail(id); } }
      if (route === '/homework/batch-review') { value = { results: body.items.map((i, index) => ({ id: i.id, success: !(state === 'partial' && index === 0), message: state === 'partial' && index === 0 ? '虚构冲突：记录已更新，请核对' : '已完成' })) }; }
      if (route === '/summaries/batch-publish') value = { results: body.summaryIds.map((id, i) => ({ id, success: !(state === 'partial' && i === 0), message: state === 'partial' && i === 0 ? '虚构冲突：审核版本已变化' : '虚构发布成功' })) };
      if (route === '/leaves') { value = Object.assign({}, fixtures['/leaves'][0], body, { id: fixtures['/leaves'].length + 1, status: 'REQUESTED', canReview: false }); fixtures['/leaves'].push(value); }
      if (route === '/pickup-contacts' || /^\/pickup-contacts\/\d+(?:\/default)?$/.test(route)) {
        if (teacher) return { statusCode: 403, data: { message: '虚构校验：仅绑定监护人可维护接送人。' } };
        if (route === '/pickup-contacts') { value = Object.assign({}, body, { id: fixtures['/pickup-contacts'].length + 10, version: 0, canManage: true, phoneMasked: '•••• ' + String(body.phone).slice(-4), faceStatus: 'NOT_CONFIGURED' }); if (body.isDefault) fixtures['/pickup-contacts'].forEach(row => { row.isDefault = false; }); fixtures['/pickup-contacts'].push(value); }
        else { const id = Number(route.split('/')[2]), row = fixtures['/pickup-contacts'].find(item => item.id === id); if (!row) return { statusCode: 404, data: { message: '虚构接送人已删除' } }; if (row.version !== body.expectedVersion) return { statusCode: 409, data: { message: '虚构接送人已更新，请刷新' } }; if (route.endsWith('/default')) { fixtures['/pickup-contacts'].forEach(item => { item.isDefault = item.id === id; }); row.version++; value = row; } else { for (const item of fixtures['/pickups']) if (item.contactId === id && item.status === 'ACTIVE') Object.assign(item, { status: 'REVOKED', effectiveStatus: 'REVOKED', canRevoke: false, canVerify: false, canCheckout: false }); if (method === 'DELETE') { fixtures['/pickup-contacts'] = fixtures['/pickup-contacts'].filter(item => item.id !== id); value = { success: true }; } else { Object.assign(row, body, { version: row.version + 1, phoneMasked: '•••• ' + String(body.phone).slice(-4) }); if (body.isDefault) fixtures['/pickup-contacts'].forEach(item => { item.isDefault = item.id === id; }); value = row; } } }
      }
      if (route === '/pickups') { const contact = body.contactId ? fixtures['/pickup-contacts'].find(row => row.id === Number(body.contactId)) : null; if (teacher) return { statusCode: 403, data: { message: '请由监护人登记授权' } }; if (body.contactId && (!contact || contact.version !== body.expectedContactVersion)) return { statusCode: 409, data: { message: '常用接送人已变更，请重新确认' } }; const row = Object.assign({}, fixtures['/pickups'][0], body, contact ? { pickupName: contact.name, relationship: contact.relationship, phoneMasked: contact.phoneMasked } : { phoneMasked: '•••• ' + String(body.phone || '').slice(-4) }, { id: fixtures['/pickups'].length + 1, status: 'ACTIVE', effectiveStatus: 'ACTIVE', version: 0, mode: body.mode || 'SINGLE', canRevoke: true, canVerify: false, canCheckout: false, validFrom: (body.startDate || date) + 'T' + (body.startTime || '18:30') + ':00', expiresAt: (body.endDate || body.startDate || date) + 'T' + (body.endTime || '20:00') + ':00' }); fixtures['/pickups'].push(row); value = Object.assign({}, row, { pickupCode: '638294' }); }
      if (route === '/pickup-alerts') { value = Object.assign({}, body, { id: fixtures['/pickup-alerts'].length + 1, studentName: children.find(row => row.id === body.studentId)?.name, businessDate: date, createdAt: time, status: 'OPEN', version: 0, canHandle: teacher, parentPhone: '13800008888', logs: [] }); fixtures['/pickup-alerts'].push(value); fixtures['/pickups'].forEach(row => { if (row.studentId === body.studentId) { row.canVerify = false; row.canCheckout = false; } }); }
      const alertAction = route.match(/^\/pickup-alerts\/(\d+)\/handle$/);
      if (alertAction) { const row = fixtures['/pickup-alerts'].find(item => item.id === Number(alertAction[1])); if (!teacher) return { statusCode: 403, data: { message: '仅负责老师可处理异常' } }; if (row && row.version !== body.expectedVersion) return { statusCode: 409, data: { message: '异常记录已更新，请重新核对' } }; if (row) { row.status = body.action; row.version++; row.canHandle = body.action !== 'RESOLVED'; row.logs.push({ id: row.logs.length + 1, actorName: user.name, note: body.note, createdAt: time }); value = row; if (body.action === 'RESOLVED') fixtures['/pickups'].forEach(item => { if (item.studentId === row.studentId && item.status === 'ACTIVE') item.canVerify = true; }); } }
      const safetyAction = route.match(/^\/(leaves|pickups)\/(\d+)\/(review|cancel|revoke|rotate-code|verify)$/);
      if (safetyAction) { const row = fixtures['/' + safetyAction[1]].find(item => item.id === Number(safetyAction[2])); if (row) { const action = safetyAction[3]; if (action === 'verify' && (!body.confirmedIdentity || !/^\d{6}$/.test(body.code) || !/^\d{4}$/.test(body.phoneLast4) || !body.pickupName || fixtures['/pickup-alerts'].some(alert => alert.studentId === row.studentId && alert.status !== 'RESOLVED'))) return { statusCode: 400, data: { message: '虚构拦截：请完整核验并先处理异常提醒' } }; row.version++; if (action === 'rotate-code') row.canCheckout = false; if (action === 'verify') row.canCheckout = true; if (action === 'review') row.status = body.approved ? 'APPROVED' : 'REJECTED'; if (action === 'cancel' || action === 'revoke') { row.status = action === 'cancel' ? 'CANCELLED' : 'REVOKED'; row.effectiveStatus = row.status; row.canRevoke = false; row.canVerify = false; row.canCheckout = false; } if (safetyAction[1] === 'leaves') { row.canReview = admin && row.status === 'REQUESTED'; row.canCancel = ['REQUESTED', 'APPROVED'].includes(row.status); } value = Object.assign({}, row, action === 'rotate-code' ? { pickupCode: '638295' } : {}); } }
      if (route === '/refunds') { value = Object.assign({}, fixtures['/refunds'][0], body, { id: fixtures['/refunds'].length + 1, status: 'REQUESTED', version: 1 }); fixtures['/refunds'].push(value); }
      const refundAction = route.match(/^\/refunds\/(\d+)\/(review|cancel|confirm)$/);
      if (refundAction) { const row = fixtures['/refunds'].find(item => item.id === Number(refundAction[1])); if (row) { row.status = refundAction[2] === 'review' ? body.approved ? 'APPROVED' : 'REJECTED' : refundAction[2] === 'cancel' ? 'CANCELLED' : 'CONFIRMED'; row.version++; row.canReview = false; row.canConfirm = admin && row.status === 'APPROVED'; row.canCancel = row.status === 'APPROVED'; value = row; } }
      if (route === '/privacy/consents') { const scope = body.scope === 'MEDIA' ? 'media' : 'service'; fixtures[route][scope].status = body.granted ? 'GRANTED' : 'WITHDRAWN'; fixtures[route][scope === 'media' ? 'effectiveMedia' : 'effectiveService'] = body.granted; value = fixtures[route]; }
      if (route === '/privacy/requests') { value = Object.assign({}, body, { id: fixtures['/privacy/requests'].length + 1, studentName: children.find(row => row.id === body.studentId)?.name, status: 'OPEN', createdAt: time }); fixtures[route].push(value); }
      const privacyAction = route.match(/^\/privacy\/requests\/(\d+)\/(review|export|erase)$/);
      if (privacyAction) { const row = fixtures['/privacy/requests'].find(item => item.id === Number(privacyAction[1])); if (row) { if (privacyAction[2] === 'export') value = { notice: '全部为虚构资料，无真实孩子信息。', student: children.find(item => item.id === row.studentId), records: [] }; else { row.status = privacyAction[2] === 'review' ? body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED' : 'COMPLETED'; row.reviewNote = body.note || '虚构处理记录'; value = row; } } }

    } else if (state === 'empty') {
      if (Array.isArray(value)) value = [];
      if (route === '/dashboard') value = { studentCount: 0, presentCount: 0, homeworkCount: 0, completedHomeworkCount: 0, students: [], activities: [], subjectProgress: [], weeklyAttendance: [] };
    } else if (Array.isArray(value)) {
      if (route === '/summaries' && !teacher) value = value.filter(row => row.status === 'PUBLISHED');
      if (route === '/teacher-attendance' && options.header?.['X-Campus-Id']) value = value.filter(row => String(row.campusId) === options.header['X-Campus-Id']);
      value = value.filter(row => ['studentId', 'classId', 'accountId', 'subject', 'status'].every(key => !url.searchParams.get(key) || row[key] === undefined || String(row[key]) === url.searchParams.get(key)));
      if (route === '/students' && url.searchParams.get('keyword')) value = value.filter(s => s.name.includes(url.searchParams.get('keyword')));
    }
    return value === undefined ? { statusCode: 404, data: { message: '预览缺少虚构接口：' + route } } : { statusCode: 200, data: copy(value) };
  }
  const root = document.getElementById('__preview-app'), cache = new Map(), components = new Map(), definitions = new Map(), tasksPending = new Set(), expressionCache = new Map(), trees = new Map();
  let app, pageDefinition, componentDefinition, page, booting = true, drawing = false, scheduled = false;
  const failures = window.__previewErrors = [], requests = window.__previewRequests = [];
  function failure(error) { failures.push(String(error.stack || error)); console.error(error); document.documentElement.dataset.previewError = 'true'; }
  function track(value) { if (value && typeof value.then === 'function') { tasksPending.add(value); value.then(() => tasksPending.delete(value), error => { tasksPending.delete(value); failure(error); }); } return value; }
  const notice = text => { const el = document.getElementById('__preview-note'); el.textContent = '虚构样例 · ' + text; };
  function navigate(options) {
    if (booting) return;
    const target = new URL(options.url, location.origin), parts = target.pathname.split('/'), next = new URLSearchParams(query);
    next.set('page', parts[2] || 'dashboard');
    for (const key of ['sheet', 'form', 'state', 'id', 'studentId', 'stage', 'mode', 'identity', 'fromLogin', 'contactId', 'pickupId', 'view', 'tab', 'alert']) next.delete(key);
    for (const [key, value] of target.searchParams) next.set(key, value);
    const sessionRole = app.globalData.session?.user?.role;
    if (roles.includes(sessionRole)) next.set('role', sessionRole);
    location.href = '/?' + next;
  }
  const wx = {
    request(options) {
      const method = (options.method || 'GET').toUpperCase(), controller = new AbortController();
      requests.push({ url: options.url, method });
      queueMicrotask(async () => {
        let result;
        try {
          const url = new URL(options.url, location.origin), route = url.pathname.replace(/^\/__fixtures/, '');
          if (state !== 'error' && ['/auth/challenge', '/auth/login'].includes(route)) {
            const target = route === '/auth/challenge' ? '/__preview/challenge' + url.search : '/__preview/login';
            const reply = await fetch(target, { method, headers: { 'Content-Type': 'application/json' }, body: ['GET', 'HEAD'].includes(method) ? undefined : JSON.stringify(options.data || {}), signal: controller.signal });
            result = { statusCode: reply.status, data: await reply.json() };
          } else result = response(options);
        } catch {
          const error = { errMsg: controller.signal.aborted ? 'request:fail abort' : 'request:fail preview connection' };
          options.fail?.(error); options.complete?.(error); return;
        }
        options.success?.(result); options.complete?.(result);
      });
      return { abort: () => controller.abort() };
    },
    getRandomValues(options) {
      queueMicrotask(() => {
        let result;
        try { result = { randomValues: window.crypto.getRandomValues(new Uint8Array(options.length)).buffer }; }
        catch { const error = { errMsg: 'getRandomValues:fail' }; options.fail?.(error); options.complete?.(error); return; }
        options.success?.(result); options.complete?.(result);
      });
    },
    getStorageSync: key => copy(storage.get(key)), setStorageSync: (key, value) => storage.set(key, copy(value)), removeStorageSync: key => storage.delete(key),
    navigateTo: navigate, reLaunch: navigate, redirectTo: navigate, switchTab: navigate, navigateBack: () => history.back(),
    showToast: options => notice(options.title), showModal: options => {
      const dialog = document.createElement('dialog'), title = document.createElement('h3'), content = document.createElement('p');
      title.textContent = options.title; content.textContent = options.content; content.style.whiteSpace = 'pre-wrap';
      dialog.style.cssText = 'width:320px;border:1px solid #dfe8f7;border-radius:16px;padding:22px;color:#253246;background:white;font:14px/1.7 sans-serif';
      dialog.append(title, content);
      for (const [label, yes] of [['取消', false], ['确认', true]]) { const button = document.createElement('button'); button.textContent = label; button.style.cssText = 'margin-top:12px;padding:10px;background:#edf5ff;color:#1677ff;border-radius:10px;width:100%'; button.onclick = () => { dialog.close(); dialog.remove(); options.success?.({ confirm: yes, cancel: !yes }); }; dialog.append(button); }
      document.body.append(dialog); dialog.showModal();
    },
    setClipboardData: options => { window.__previewClipboard = options.data; notice('虚构接送码已复制到预览内存，未写入系统剪贴板'); options.success?.({}); },
    makePhoneCall: options => { window.__previewPhone = options.phoneNumber; notice('虚构拨号预览，不发起真实通话'); },
    stopPullDownRefresh() {}, setNavigationBarTitle() {}, showLoading() {}, hideLoading() {},
    chooseMedia: options => options.success?.({ tempFiles: [{ tempFilePath: '/__fixtures/files/preview.svg', size: 400 }] }),
    uploadFile: options => queueMicrotask(() => options.success?.({ statusCode: 200, data: JSON.stringify(photo) })),
    authorize: options => options.fail?.({ errMsg: '虚构预览不启用麦克风' }), openSetting: options => options.success?.({ authSetting: { 'scope.record': false } }),
    previewImage: options => window.open(options.current, '_blank', 'noopener'), previewMedia: () => notice('虚构媒体预览'),
    setInnerAudioOption: options => options.success?.({}),
    createInnerAudioContext() {
      const audioElement = new Audio();
      let onStop;
      return {
        set src(value) { audioElement.src = value; },
        play: () => audioElement.play().catch(failure),
        pause: () => audioElement.pause(),
        stop() { audioElement.pause(); audioElement.currentTime = 0; onStop?.(); },
        destroy() { audioElement.pause(); audioElement.removeAttribute('src'); audioElement.load(); },
        onPlay: fn => audioElement.addEventListener('play', fn),
        onPause: fn => audioElement.addEventListener('pause', fn),
        onStop: fn => { onStop = fn; },
        onEnded: fn => audioElement.addEventListener('ended', fn),
        onError: fn => audioElement.addEventListener('error', fn),
        onCanplay: fn => audioElement.addEventListener('canplay', fn),
        seek: value => { audioElement.currentTime = value; },
        get currentTime() { return audioElement.currentTime; }
      };
    }
  };
  function load(id, from = '') {
    const parts = (id.startsWith('.') ? from.split('/').slice(0, -1).join('/') + '/' + id : id).split('/'), normalized = [];
    for (const part of parts) if (part === '..') normalized.pop(); else if (part && part !== '.') normalized.push(part);
    let name = normalized.join('/'); if (!/\.(js|json)$/.test(name)) name += '.js';
    if (cache.has(name)) return cache.get(name).exports;
    if (!(name in files)) throw new Error('预览模块不存在：' + name);
    const module = { exports: {} }; cache.set(name, module);
    if (name.endsWith('.json')) module.exports = JSON.parse(files[name]);
    else new Function('require', 'module', 'exports', 'wx', 'getApp', 'getCurrentPages', 'Page', 'Component', 'App', files[name] + '\n//# sourceURL=/' + name)(
      request => load(request, name), module, module.exports, wx, () => app, () => page ? [page] : [], value => { pageDefinition = value; }, value => { componentDefinition = value; }, value => { app = value; });
    if (name === 'utils/config.js') module.exports.apiBase = location.origin + '/__fixtures';
    return module.exports;
  }
  const decode = text => text.replace(/&(?:amp|lt|gt|quot|apos|#\d+|#x[\da-f]+);/gi, entity => ({ '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'" }[entity] || String.fromCodePoint(entity[2].toLowerCase() === 'x' ? parseInt(entity.slice(3), 16) : parseInt(entity.slice(2), 10))));
  function evaluate(code, scope) { try { if (!expressionCache.has(code)) expressionCache.set(code, new Function('scope', 'with(scope){ return (' + code + '); }')); return expressionCache.get(code)(scope); } catch (error) { failure(new Error('WXML 表达式 ' + code + ': ' + error.message)); return undefined; } }
  function interpolate(value, scope) { if (typeof value !== 'string') return value; const exact = value.match(/^{{((?:(?!{{|}})[\s\S])*?)}}$/); return exact ? evaluate(exact[1], scope) : value.replace(/{{([\s\S]*?)}}/g, (_, code) => String(evaluate(code, scope) ?? '')); }
  const truth = value => value !== false && value !== undefined && value !== null && value !== '' && value !== 'false' && value !== 0;
  const rpx = css => css.replace(/(-?(?:\d*\.)?\d+)rpx\b/g, (_, n) => Number(n) / 2 + 'px');
  const css = value => rpx(value).replace(/(^|[\s,>+~])image(?=[\s,.#:[>+~{]|$)/g, '$1img').replace(/(^|[\s,])page(?=\s*\{)/g, '$1body');
  // Keep WeChat's higher-specificity default button sizing visible during layout checks.
  const reset = 'view,scroll-view,picker{display:block}text{display:inline}view,text,picker,scroll-view,input,textarea,button,img{box-sizing:border-box}img{display:inline-block;width:150px;height:112.5px;vertical-align:middle}button{display:block;border:0;margin:0;padding:0;background:#f8f8f8;color:inherit;font:inherit;text-align:center;cursor:pointer}' + (config.style === 'v2' ? 'button:not([size=mini]){width:184px;margin-left:auto;margin-right:auto}' : '') + 'input,textarea,select{font:inherit;color:inherit}textarea{resize:none}input{min-width:0}input::placeholder,textarea::placeholder{color:#a5adba}picker{position:relative}a{color:inherit;text-decoration:none}[hidden]{display:none!important}';
  function parse(source) {
    if (trees.has(source)) return trees.get(source);
    const nodes = [], stack = [{ children: nodes }], tokens = source.match(/<!--[\s\S]*?-->|<\/?[\w-]+(?:[^"'<>]|"[^"]*"|'[^']*')*\/?>|[^<]+|</g) || [];
    for (const token of tokens) {
      if (token.startsWith('<!--')) continue;
      if (token.startsWith('</')) { const tag = token.match(/^<\/([\w-]+)/)[1]; if (stack.length < 2 || stack.pop().tag !== tag) throw new Error('WXML 标签不匹配：' + tag); continue; }
      const match = token.match(/^<([\w-]+)([\s\S]*?)\/?>$/);
      if (!match) { stack[stack.length - 1].children.push({ text: decode(token.replace(/\s*\n\s*/g, '')) }); continue; }
      const node = { tag: match[1], attrs: {}, children: [] }, attr = /([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
      let found; while ((found = attr.exec(match[2]))) node.attrs[found[1]] = found[2] !== undefined ? decode(found[2]) : found[3] !== undefined ? decode(found[3]) : found[4] !== undefined ? decode(found[4]) : true;
      stack[stack.length - 1].children.push(node); if (!token.endsWith('/>')) stack.push(node);
    }
    if (stack.length !== 1) throw new Error('WXML 标签未闭合'); trees.set(source, nodes); return nodes;
  }
  function instance(definition, props = {}) {
    const object = { data: Object.assign(copy(definition.data || {}), props), properties: props };
    object.setData = (patch, callback) => { for (const [key, value] of Object.entries(patch)) { const steps = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = object.data; steps.slice(0, -1).forEach((step, i) => { target = target[step] ||= /^\d+$/.test(steps[i + 1]) ? [] : {}; }); target[steps[steps.length - 1]] = value; } schedule(); if (callback) queueMicrotask(callback); };
    for (const [key, value] of Object.entries(Object.assign({}, definition, definition.methods))) if (typeof value === 'function') object[key] = (...args) => track(value.apply(object, args));
    return object;
  }
  function invoke(owner, name, element, detail = {}, original) {
    if (!name || typeof owner[name] !== 'function') return;
    try { return owner[name]({ currentTarget: { dataset: Object.assign({}, element.dataset) }, target: { dataset: Object.assign({}, element.dataset) }, detail, preventDefault: () => original?.preventDefault(), stopPropagation: () => original?.stopPropagation() }); } catch (error) { failure(error); }
  }
  function schedule() { if (booting || drawing || scheduled) return; scheduled = true; queueMicrotask(() => { scheduled = false; try { draw(); } catch (error) { failure(error); } }); }
  function component(node, values, scope, owner, key) {
    let cached = components.get(key), route = Object.assign({}, config.usingComponents, JSON.parse(files['pages/' + pageName + '/index.json'] || '{}').usingComponents)[node.tag].replace(/^\//, '');
    if (!cached) { if (!definitions.has(route)) { componentDefinition = null; load(route + '.js'); definitions.set(route, componentDefinition); } const definition = definitions.get(route); if (!definition) throw new Error('组件未注册：' + node.tag); const defaults = {}; for (const [name, schema] of Object.entries(definition.properties || {})) defaults[name] = schema.value !== undefined ? copy(schema.value) : (schema.type || schema) === Boolean ? false : (schema.type || schema) === String ? '' : (schema.type || schema) === Number ? 0 : (schema.type || schema) === Array ? [] : null; cached = { definition, object: instance(definition, defaults), previous: {}, mounted: false }; components.set(key, cached); }
    const { definition, object } = cached, changed = new Set();
    for (const [name, value] of Object.entries(values)) { const prop = name.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()); if (!(prop in (definition.properties || {}))) continue; if (JSON.stringify(cached.previous[prop]) !== JSON.stringify(value)) changed.add(prop); object.data[prop] = value; object.properties[prop] = value; cached.previous[prop] = copy(value); }
    for (const [keys, observer] of Object.entries(definition.observers || {})) { const list = keys.split(',').map(s => s.trim()); if (!cached.mounted || list.some(name => changed.has(name))) observer.apply(object, list.map(name => object.data[name])); }
    const host = document.createElement(node.tag); for (const [name, value] of Object.entries(values)) if (name === 'class' || name.startsWith('data-')) host.setAttribute(name, String(value));
    // Project children in their parent's scope; keep their cache keys separate from the shadow tree.
    host.append(render(node.children, scope, owner, key + '/slot'));
    object.triggerEvent = (name, detail) => invoke(owner, values['bind' + name] || values['bind:' + name], host, detail);
    if (!cached.mounted) { definition.lifetimes?.attached?.call(object); definition.pageLifetimes?.show?.call(object); if (node.tag === 'app-nav' && query.get('sheet') === '1') object.data.open = true; cached.mounted = true; }
    const shadow = host.attachShadow({ mode: 'open' }), style = document.createElement('style');
    style.textContent = ':host{display:block;font:inherit;color:inherit}' + reset + css(files[route + '.wxss'] || '') + (node.tag === 'app-nav' ? '.bottom-nav,.nav-overlay{left:max(0px,calc((100vw - 375px)/2));right:auto;width:375px}' : '');
    shadow.append(style, render(parse(files[route + '.wxml']), object.data, object, key)); return host;
  }
  function render(nodes, scope, owner, prefix) {
    const result = document.createDocumentFragment(); let chain = false, matched = false;
    nodes.forEach((node, index) => {
      const key = prefix + '/' + index;
      if (node.text !== undefined) { if (node.text.trim()) result.append(document.createTextNode(String(interpolate(node.text, scope) ?? ''))); return; }
      const attrs = node.attrs;
      if ('wx:for' in attrs) { const list = interpolate(attrs['wx:for'], scope) || []; Object.entries(list).forEach(([i, item]) => { const local = Object.assign({}, scope, { [attrs['wx:for-item'] || 'item']: item, [attrs['wx:for-index'] || 'index']: Array.isArray(list) ? Number(i) : i }); const clean = Object.assign({}, attrs); delete clean['wx:for']; result.append(render([Object.assign({}, node, { attrs: clean })], local, owner, key + ':' + (attrs['wx:key'] === '*this' ? item : item?.[attrs['wx:key']] ?? i))); }); chain = false; return; }
      if ('wx:if' in attrs) { chain = true; matched = truth(interpolate(attrs['wx:if'], scope)); if (!matched) return; }
      else if ('wx:elif' in attrs || 'wx:else' in attrs) { if (!chain || matched) return; matched = 'wx:else' in attrs || truth(interpolate(attrs['wx:elif'], scope)); if (!matched) return; }
      else { chain = false; matched = false; }
      if (node.tag === 'block') { result.append(render(node.children, scope, owner, key)); return; }
      const values = Object.fromEntries(Object.entries(attrs).filter(([name]) => !name.startsWith('wx:')).map(([name, value]) => [name, interpolate(value, scope)]));
      if (node.tag.includes('-') && node.tag !== 'scroll-view') { const element = component(node, values, scope, owner, key); if (truth(values.hidden)) element.hidden = true; result.append(element); return; }
      const tag = node.tag === 'image' ? 'img' : ['switch', 'checkbox'].includes(node.tag) ? 'input' : node.tag, element = document.createElement(tag); element.dataset.previewKey = key;
      for (const [name, value] of Object.entries(values)) {
        if (/^(bind|catch)/.test(name) || ['range', 'range-key', 'mode', 'value', 'password', 'type', 'scroll-y', 'scroll-x'].includes(name)) continue;
        if (['hidden', 'disabled', 'loading'].includes(name)) { if (truth(value)) element.setAttribute(name, ''); continue; }
        if (name === 'style') element.setAttribute(name, rpx(String(value ?? ''))); else if (value !== undefined && value !== null) element.setAttribute(name, String(value));
      }
      if (tag === 'img') { element.alt = ''; element.style.objectFit = values.mode === 'aspectFill' ? 'cover' : values.mode === 'scaleToFill' ? 'fill' : 'contain'; if (values.mode === 'widthFix') element.style.height = 'auto'; if (values.mode === 'heightFix') element.style.width = 'auto'; }
      if (tag === 'input' || tag === 'textarea') { element.value = values.value ?? ''; if (tag === 'input') { element.type = truth(values.password) ? 'password' : ['number', 'digit'].includes(values.type) ? 'text' : values.type || 'text'; if (['number', 'digit'].includes(values.type)) element.inputMode = 'decimal'; } }
      else if (tag !== 'img') element.append(render(node.children, scope, owner, key));
      if (tag === 'button') element.type = 'button';
      if (node.tag === 'switch' || node.tag === 'checkbox') { element.type = 'checkbox'; element.checked = truth(values.checked); element.style.cssText = 'width:20px;height:20px;min-height:0;margin:0;accent-color:#1677ff'; }
      if (tag === 'scroll-view') { if (truth(values['scroll-y'])) element.style.overflowY = 'auto'; if (truth(values['scroll-x'])) element.style.overflowX = 'auto'; }
      if (tag === 'picker') {
        const input = document.createElement(values.mode === 'date' || values.mode === 'time' ? 'input' : 'select');
        if (input.tagName === 'INPUT') { input.type = values.mode; input.value = values.value || date; } else { (values.range || []).forEach((entry, i) => { const option = document.createElement('option'); option.value = i; option.textContent = values['range-key'] ? entry[values['range-key']] : entry; input.append(option); }); input.value = values.value ?? 0; }
        input.setAttribute('aria-label', element.textContent.trim()); input.disabled = truth(values.disabled); input.style.cssText = 'position:absolute;inset:0;opacity:0;width:100%;height:100%;min-height:0;padding:0;cursor:pointer';
        input.addEventListener('change', event => { event.stopPropagation(); invoke(owner, values.bindchange || values['bind:change'], element, { value: input.value }, event); }); element.append(input);
      }
      for (const [name, handler] of Object.entries(values)) { const binding = name.match(/^(bind|catch):?(tap|input|change|confirm|touchmove)$/); if (!binding || (tag === 'picker' && binding[2] === 'change')) continue; const type = { tap: 'click', confirm: 'keydown' }[binding[2]] || binding[2]; element.addEventListener(type, event => { if (binding[2] === 'confirm' && event.key !== 'Enter') return; if (binding[1] === 'catch') event.stopPropagation(); if (truth(values.disabled)) return; invoke(owner, handler, element, { value: node.tag === 'switch' ? event.target.checked : event.target.value }, event); }); }
      result.append(element);
    }); return result;
  }
  function draw() {
    drawing = true;
    try { let active = document.activeElement; while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement; const focusKey = active?.dataset?.previewKey, selection = [active?.selectionStart, active?.selectionEnd]; root.replaceChildren(render(parse(files['pages/' + pageName + '/index.wxml']), page.data, page, 'page')); if (focusKey) { const find = container => { for (const element of container.querySelectorAll('*')) { if (element.dataset.previewKey === focusKey) return element; if (element.shadowRoot) { const nested = find(element.shadowRoot); if (nested) return nested; } } }; const next = find(root); if (next) { next.focus({ preventScroll: true }); try { next.setSelectionRange(...selection); } catch {} } } }
    finally { drawing = false; }
  }
  async function start() {
    const style = document.createElement('style'); style.textContent = reset + css(files['app.wxss']) + css(files['pages/' + pageName + '/index.wxss']) + 'html{width:375px;margin:0 auto}body{width:375px;margin:0;min-height:100vh}'; document.head.append(style);
    if (pageName === 'login' && query.get('session') !== '1') storage.delete('wanfu.session.v1');
    load('app.js'); app.onLaunch?.(); app.globalData.selectedStudentIds = state === 'empty' ? [] : teacher ? [1, 2] : [1];
    const route = 'pages/' + pageName + '/index';
    load(route + '.js'); page = instance(pageDefinition); page.route = route; window.__previewPage = page;
    window.addEventListener('pagehide', () => { for (const hook of ['onHide', 'onUnload']) { try { page[hook]?.(); } catch (error) { failure(error); } } }, { once: true });
    page.setData({ teacher, admin, user, demo: true, date, roleIndex: teacher ? 0 : 1 });
    page.onLoad?.(Object.assign({ studentId: '1', date }, ['homework-detail', 'mistake-detail'].includes(pageName) ? { id: '1' } : {}, pageName === 'login' ? { stage: 'roles' } : {}, Object.fromEntries(query))); page.onShow?.();
    // A teacher-only route remains inspectable as a parent without following the authorization redirect.
    if (page.loadData && !tasksPending.size) page.refresh();
    while (tasksPending.size) await Promise.allSettled([...tasksPending]);
    if (query.get('form') === '1') { const patch = {}; for (const field of ['showCreate', 'formOpen', 'generateOpen']) if (field in page.data) patch[field] = true; if (pageName === 'summaries' && page.data.items.length) { if (teacher) { const draft = page.data.items.find(item => item.canEdit); patch.editingId = draft?.id || ''; patch.editingVersion = draft?.version || 0; patch.editContent = '虚构小结正文：作业认真完成，运动按实际情况记录。'; patch.teacherComment = '虚构评语：专注认真，继续保持。'; } else patch.feedbackId = page.data.items[0].id; } if (pageName === 'safety') { if (teacher) { patch.verifyId = page.data.pickups[0]?.id || ''; patch.viewTab = 'pickups'; } else { patch.contactForm = true; patch.contactName = '虚构新接送人'; patch.contactRelationship = '亲属'; patch.contactPhone = '13800005566'; } } if (pageName === 'refunds') patch.compose = true; if (pageName === 'privacy' && admin) { patch.reviewId = page.data.requests.find(row => row.status === 'OPEN')?.id || 0; patch.reviewName = '虚构学生 · 资料副本'; } page.setData(patch); }
    if (pageName === 'workbench' && ['record', 'review', 'summary'].includes(query.get('panel'))) { page.setData({ panel: query.get('panel'), studentIds: [1] }); page.derive(); }
    booting = false; draw(); document.documentElement.dataset.previewReady = 'true'; window.__previewReady = true;
  }
  start().catch(error => { failure(error); root.textContent = '预览加载失败：' + error.message; });
}
function html(url) {
  const page = pages.includes(url.searchParams.get('page')) ? url.searchParams.get('page') : 'dashboard';
  const role = ['PARENT', 'TEACHER', 'ADMIN', 'SUPER_ADMIN'].includes(url.searchParams.get('role')) ? url.searchParams.get('role') : 'TEACHER';
  const payload = JSON.stringify(sources()).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=375,initial-scale=1"><title>UI 样式预览 · 虚构样例 · ${escape(page)} · ${role}</title><style>#\_\_preview-toolbar{position:relative;z-index:150;width:375px;box-sizing:border-box;padding:5px 9px;background:#fff;border-bottom:1px solid #e8edf3;font:10px/1.5 -apple-system,sans-serif;color:#7a8797}#\_\_preview-toolbar form{display:flex;gap:6px;align-items:center;margin:0}#\_\_preview-toolbar select{font:11px sans-serif;max-width:110px;border:1px solid #dde5ee;border-radius:4px;background:white;padding:2px}#\_\_preview-toolbar a{font:10px sans-serif;color:#728395}#\_\_preview-note{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}</style></head><body><aside id="__preview-toolbar"><form method="get"><label>角色 <select name="role" onchange="this.form.submit()"><option${role === 'TEACHER' ? ' selected' : ''}>TEACHER</option><option${role === 'PARENT' ? ' selected' : ''}>PARENT</option><option${role === 'ADMIN' ? ' selected' : ''}>ADMIN</option><option${role === 'SUPER_ADMIN' ? ' selected' : ''}>SUPER_ADMIN</option></select></label><label>页面 <select name="page" onchange="this.form.submit()">${pages.map((name, i) => `<option value="${name}"${name === page ? ' selected' : ''}>${names[i]} · ${name}</option>`).join('')}</select></label><a href="/gallery?role=${role}">全部</a>${['sheet', 'form', 'state'].filter(key => url.searchParams.has(key)).map(key => `<input type="hidden" name="${key}" value="${escape(url.searchParams.get(key))}">`).join('')}</form><small id="__preview-note">UI 样式预览 · 全部为虚构样例</small></aside><main id="__preview-app"></main><script src="/__preview.js"></script><script>(${browserPreview.name})(${payload});</script></body></html>`;
}
function serve(request, response) {
  try {
    const url = new URL(request.url, `http://${HOST}:${PORT}`);
    response.setHeader('Cache-Control', 'no-store'); response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'");
    const send = (type, body, status = 200) => { response.writeHead(status, { 'Content-Type': type }); response.end(body); };
    const json = (data, status = 200) => send('application/json; charset=utf-8', JSON.stringify(data), status);
    if (url.pathname === '/__preview/challenge') {
      if (request.method !== 'GET') return json({ message: 'Method not allowed' }, 405);
      if (url.searchParams.get('purpose') !== 'LOGIN') return json({ message: '预览仅支持 LOGIN challenge。' }, 400);
      const now = Date.now(), expiresAt = now + 60000;
      for (const [id, expiry] of challenges) if (expiry <= now) challenges.delete(id);
      const challengeId = crypto.randomBytes(32).toString('base64url');
      challenges.set(challengeId, expiresAt);
      return json({ challengeId, publicKey: previewPublicKey, algorithm: 'RSA-OAEP-256', expiresAt: new Date(expiresAt).toISOString() });
    }
    if (url.pathname === '/__preview/login') {
      if (request.method !== 'POST') return json({ message: 'Method not allowed' }, 405);
      let body = '', size = 0;
      request.setEncoding('utf8');
      request.on('data', chunk => { size += Buffer.byteLength(chunk); if (size <= 4096) body += chunk; });
      request.on('error', () => { if (!response.destroyed && !response.writableEnded) json({ message: '预览登录请求无效。' }, 400); });
      request.on('end', () => {
        if (response.destroyed || response.writableEnded) return;
        if (size > 4096) return json({ message: '预览登录请求过大。' }, 400);
        let data;
        try { data = JSON.parse(body); } catch { return json({ message: '预览登录请求必须为 JSON。' }, 400); }
        if (!data || typeof data !== 'object' || Array.isArray(data)) return json({ message: '预览登录请求无效。' }, 400);
        // Every attempt consumes its challenge, including failed authentication.
        const expiresAt = challenges.get(data.challengeId);
        challenges.delete(data.challengeId);
        if (Object.prototype.hasOwnProperty.call(data, 'password')) return json({ message: '预览登录必须使用加密密码。' }, 400);
        const loginRole = data.role === undefined ? 'ADMIN' : data.role;
        if (!['PARENT', 'TEACHER', 'ADMIN'].includes(loginRole)) return json({ message: '预览身份必须为 PARENT、TEACHER 或 ADMIN。' }, 400);
        const denied = () => json({ message: '预览认证失败或请求已过期；仅接受虚构凭据 admin/admin，请勿输入真实密码。' }, 401);
        if (!expiresAt || expiresAt <= Date.now() || typeof data.username !== 'string' || typeof data.encryptedPassword !== 'string' || !/^[A-Za-z0-9+/]{342}==$/.test(data.encryptedPassword)) return denied();
        const username = data.username.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
        let valid;
        try {
          const label = Buffer.from(JSON.stringify(['wanfu-password-v1', 'LOGIN', data.challengeId, username]), 'utf8');
          const decrypted = crypto.privateDecrypt({ key: previewKeys.privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256', oaepLabel: label }, Buffer.from(data.encryptedPassword, 'base64'));
          valid = username === 'admin' && decrypted.equals(Buffer.from('admin', 'utf8'));
        } catch { return denied(); }
        if (!valid) return denied();
        return json({ token: 'visual-preview-fiction-only', user: { id: 9001, name: { PARENT: '虚构陈家长', TEACHER: '虚构林老师', ADMIN: '虚构管理员' }[loginRole], role: loginRole, demo: true }, demo: true });
      });
      return;
    }
    if (!['GET', 'HEAD'].includes(request.method)) return send('text/plain; charset=utf-8', 'Preview is read only', 405);
    if (url.pathname === '/') return send('text/html; charset=utf-8', html(url));
    if (url.pathname === '/__preview.js') return send('text/javascript; charset=utf-8', browserPreview.toString());
    if (url.pathname === '/gallery') return send('text/html; charset=utf-8', `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>UI 样式预览 · 虚构样例</title><style>body{font:14px/1.8 sans-serif;color:#253246;margin:24px}a{color:#149b78;margin-right:14px}li{margin:10px 0}</style><h2>UI 样式预览 · 全部为虚构样例</h2><p>真实 WXML / WXSS / 页面 JS · 375px · 无后端请求</p><ul>${pages.map((page, i) => `<li>${names[i]} · ${page}<br>${['TEACHER', 'PARENT', 'ADMIN', 'SUPER_ADMIN'].map(role => ['', '&form=1', '&sheet=1', '&state=empty', '&state=error'].map((flags, j) => `<a href="/?page=${page}&role=${role}${flags}">${role} ${['默认', '表单', '导航弹层', '空态', '错误态'][j]}</a>`).join('')).join('<br>')}</li>`).join('')}</ul></html>`);
    if (url.pathname === '/__fixtures/files/preview.svg') return send('image/svg+xml', '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="320" viewBox="0 0 480 320"><rect width="480" height="320" fill="#edf8f3"/><rect x="55" y="30" width="370" height="260" rx="12" fill="white"/><g font-family="sans-serif" fill="#456657"><text x="85" y="80" font-size="22">FICTIONAL PRACTICE SAMPLE</text><text x="85" y="135" font-size="26">24 × 12 = 288</text><text x="85" y="190" font-size="20">12 × 24 = 288</text><text x="85" y="245" font-size="16">UI preview only · No student data</text></g></svg>');
    if (url.pathname === '/__fixtures/files/scan.png') return send('image/png', fs.readFileSync(path.resolve(ROOT, '../.run/scan-normalized.png')));
    if (url.pathname === '/__fixtures/files/preview.wav') { const count = 4000, buffer = Buffer.alloc(44 + count * 2); buffer.write('RIFF'); buffer.writeUInt32LE(36 + count * 2, 4); buffer.write('WAVEfmt ', 8); buffer.writeUInt32LE(16, 16); buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(1, 22); buffer.writeUInt32LE(8000, 24); buffer.writeUInt32LE(16000, 28); buffer.writeUInt16LE(2, 32); buffer.writeUInt16LE(16, 34); buffer.write('data', 36); buffer.writeUInt32LE(count * 2, 40); for (let i = 0; i < count; i++) buffer.writeInt16LE(Math.round(Math.sin(i * Math.PI * 440 / 4000) * 1200), 44 + i * 2); return send('audio/wav', buffer); }
    if (url.pathname.startsWith('/assets/')) {
      const asset = path.resolve(ROOT, '.' + decodeURIComponent(url.pathname)), base = fs.realpathSync(path.join(ROOT, 'assets'));
      if (!asset.startsWith(path.join(ROOT, 'assets') + path.sep) || !fs.realpathSync(asset).startsWith(base + path.sep) || !fs.statSync(asset).isFile()) return send('text/plain', 'Not found', 404);
      return send(({ '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.gif': 'image/gif', '.woff2': 'font/woff2' })[path.extname(asset).toLowerCase()] || 'application/octet-stream', fs.readFileSync(asset));
    }
    return send('text/plain; charset=utf-8', 'Not found', 404);
  } catch (error) { response.writeHead(error.code === 'ENOENT' ? 404 : 500, { 'Content-Type': 'text/plain; charset=utf-8' }); response.end(error.code === 'ENOENT' ? 'Not found' : error.message); }
}
if (require.main === module) http.createServer(serve).listen(PORT, HOST, () => console.log(`UI 样式预览（虚构数据）：http://${HOST}:${PORT}/?page=dashboard&role=TEACHER`));
module.exports = { serve, browserPreview, sources, pages };
