/* Actual API integration using the mini program's adapter and page loaders.
 * Run only with the local demo server: node scripts/check-api.js
 * Creates explicitly named demo records on the current business date. No production use.
 */
const assert = require('node:assert/strict');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const storage = new Map();
const calls = [];
let app;
let page;
global.getApp = () => app;
global.App = definition => { app = definition; };
global.Page = definition => { page = definition; };
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key),
  stopPullDownRefresh() {}, showToast() {}, reLaunch(options) { if (options.complete) options.complete(); },
  getRandomValues(options) { options.success({ randomValues: Uint8Array.from(crypto.randomBytes(options.length)).buffer }); },
  async request(options) {
    try {
      if (new URL(options.url).pathname === '/api/auth/login') assert.ok(!Object.hasOwn(options.data, 'password'), '登录网络请求不得包含明文密码');
      const response = await fetch(options.url, { method: options.method, headers: options.header, body: options.method === 'GET' || options.data === undefined ? undefined : JSON.stringify(options.data), signal: AbortSignal.timeout(options.timeout) });
      const data = await response.json();
      calls.push({ method: options.method, path: new URL(options.url).pathname, status: response.status });
      if (response.status >= 400) console.log('HTTP ' + response.status + ' ' + options.method + ' ' + new URL(options.url).pathname + ': ' + (data.message || '服务端错误'));
      options.success({ statusCode: response.status, data });
    } catch (error) { options.fail(error); }
  },
  async uploadFile(options) {
    try {
      const form = new FormData();
      const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJ1kAAAAASUVORK5CYII=', 'base64');
      form.append(options.name, new Blob([image], { type: 'image/png' }), '小程序联调.png');
      const response = await fetch(options.url, { method: 'POST', headers: options.header, body: form });
      options.success({ statusCode: response.status, data: await response.text() });
    } catch (error) { options.fail(error); }
  }
};
require(path.join(root, 'app.js'));
app.onLaunch();
if (process.env.WANFU_TEST_API_BASE) require(path.join(root, 'utils/config.js')).apiBase = process.env.WANFU_TEST_API_BASE;
const api = require(path.join(root, 'utils/api.js'));
const format = require(path.join(root, 'utils/format.js'));
function loadPage(name, query) {
  const file = path.join(root, 'pages', name, 'index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  const value = Object.assign({}, page, { data: structuredClone(page.data), setData(patch) { Object.assign(this.data, patch); } });
  if (value.onLoad) value.onLoad(query || {});
  value.data.teacher = ['SUPER_ADMIN', 'ADMIN', 'TEACHER'].includes(app.globalData.session.user.role);
  value.data.admin = ['SUPER_ADMIN', 'ADMIN'].includes(app.globalData.session.user.role);
  return value;
}
async function main() {
  assert.ok(process.env.WANFU_TEST_API_BASE, '请通过 WANFU_TEST_API_BASE 显式指定隔离验收服务，勿写入日常业务库');
  const health = await api.health();
  assert.equal(health.demo, true, '仅允许 local 演示服务执行');
  const teacher = await api.login({ username: 'admin', password: 'admin', role: 'TEACHER' });
  app.setSession(teacher);
  const teacherStudents = await api.students({});
  const parent = await api.login({ username: 'admin', password: 'admin', role: 'PARENT' });
  app.setSession(parent);
  const parentStudents = await api.students({});
  const student = parentStudents.find(row => teacherStudents.some(other => other.id === row.id));
  assert.ok(student, '教师与家长应有共同授权学生');
  const forbidden = teacherStudents.find(row => !parentStudents.some(other => other.id === row.id));
  if (forbidden) await assert.rejects(api.student(forbidden.id), error => error.statusCode === 403);
  const face = await api.request('GET', '/pickup-face/status');
  assert.equal(face.providerConfigured, false); assert.equal(face.captureEnabled, false);
  await assert.rejects(api.request('POST', '/pickup-face/verify', { studentId: student.id }), error => error.statusCode === 503);
  const common = await api.request('POST', '/pickup-contacts', { studentId: student.id, name: '虚构接口接送人', relationship: '亲属', phone: '13800001234', isDefault: true, requestId: crypto.randomUUID() });
  assert.equal(common.faceStatus, 'NOT_CONFIGURED'); assert.equal(common.isDefault, true);
  const date = format.today();
  const title = '小程序接口联调 ' + Date.now();
  app.setSession(teacher);
  const created = await api.createHomework({ date, subject: '数学', type: 'WRITTEN', title, description: '原生前端接口验证', studentIds: [student.id], attachmentIds: [] });
  assert.equal(created.assignedCount, 1);
  let homework = (await api.homework({ date, studentId: student.id })).find(row => row.taskId === created.taskId);
  assert.ok(homework);
  app.setSession(parent);
  const policy = await api.request('GET', '/privacy/policy');
  if (policy.consentRequired !== false) for (const scope of ['SERVICE', 'MEDIA']) await api.request('POST', '/privacy/consents', { studentId: student.id, scope, policyVersion: policy.version, granted: true });
  const file = await api.upload('fixture.png', student.campusId);
  assert.ok(file.id);
  const fileURL = api.fileUrl(file);
  assert.ok(!new URL(fileURL).pathname.includes('/api/api/'));
  assert.equal((await fetch(fileURL)).status, 200);
  await api.homeworkAttachments(homework.id, [file.id]);
  await api.homeworkStatus(homework.id, 'PENDING_REVIEW');
  app.setSession(teacher);
  await api.reviewHomework(homework.id, { status: 'CORRECTION', accuracy: 80, comment: '请补充步骤' });
  app.setSession(parent);
  await api.homeworkStatus(homework.id, 'PENDING_RECHECK', { submissionText: '验收：已按老师要求完成订正' });
  app.setSession(teacher);
  await api.reviewHomework(homework.id, { status: 'COMPLETED', accuracy: 100, comment: '订正完成' });
  await api.reviewHomework(homework.id, { status: 'COMPLETED', accuracy: 100, comment: '重复验证' });
  app.setSession(parent);
  const mistake = await api.createMistake({ studentId: student.id, subject: '数学', title, knowledgePoint: '运算顺序', errorReason: '遗漏步骤', homeworkId: homework.id, attachmentIds: [] });
  await api.correctMistake(mistake.id, { text: '已补充完整步骤', attachmentIds: [] });
  app.setSession(teacher);
  await api.reviewMistake(mistake.id, { approved: true, comment: '已掌握' });
  const mastered = (await api.mistakes({ studentId: student.id })).find(row => row.id === mistake.id);
  assert.equal(mastered.status, 'MASTERED');
  assert.equal(mastered.reviews.length, 3);
  app.setSession(parent);
  await assert.rejects(api.completeReview(mastered.reviews[0].id), error => error.statusCode === 403);
  app.setSession(teacher);
  const project = title;
  assert.equal((await api.fitnessBatch({ date, studentIds: [student.id], project, count: 10, duration: 60 })).recordedCount, 1);
  assert.equal((await api.fitnessBatch({ date, studentIds: [student.id], project, count: 10, duration: 60 })).recordedCount, 0);
  const pointPayload = { studentId: student.id, amount: 1, reason: title, requestId: 'mp-check-' + Date.now() };
  const firstPoint = await api.adjustPoints(pointPayload);
  assert.equal((await api.adjustPoints(pointPayload)).id, firstPoint.id);
  const checkInPlace = (await api.campuses()).find(c => c.id === student.campusId);
  assert.ok(checkInPlace && checkInPlace.checkInRadius, '请先在隔离测试校区配置签到地点');
  const position = () => ({ latitude: checkInPlace.checkInLatitude, longitude: checkInPlace.checkInLongitude, accuracy: 5, capturedAt: Date.now() });
  const checkin = await api.checkIn({ date, studentIds: [student.id], campusId: student.campusId, location: position() });
  assert.equal(checkin.results[0].success, true);
  app.setSession(parent);
  const pickup = await api.request('POST', '/pickups', { studentId: student.id, date, pickupName: '联调监护人', relationship: '监护人', phone: '13800000000', requestId: crypto.randomUUID() });
  app.setSession(teacher);
  await api.request('POST', '/pickups/' + pickup.id + '/verify', { pickupName: '联调监护人', phoneLast4: '0000', code: pickup.pickupCode, confirmedIdentity: true, expectedVersion: pickup.version, requestId: crypto.randomUUID() });
  assert.equal((await api.checkOut({ date, studentIds: [student.id] })).results[0].success, true);
  await api.generateSummaries({ date, studentIds: [student.id] });
  let summary = (await api.summaries({ date, studentId: student.id }))[0];
  if (summary.status === 'DRAFT') {
    summary = await api.editSummary(summary.id, '小程序真实接口联调评语');
    summary = await api.request('POST', '/summaries/' + summary.id + '/submit-review', { expectedVersion: summary.version });
    const reviewer = await api.demo('ADMIN'); app.setSession(reviewer);
    await api.request('POST', '/summaries/' + summary.id + '/review', { expectedVersion: summary.version, approved: true, comment: '接口联调独立审核' });
    app.setSession(teacher);
    summary = await api.publishSummary(summary.id);
  }
  assert.equal(summary.status, 'PUBLISHED');
  assert.equal(summary.notificationStatus, 'NOT_CONFIGURED');
  app.setSession(parent);
  await api.feedbackSummary(summary.id, { satisfaction: 'SATISFIED', text: '小程序联调反馈' });
  assert.equal((await api.summaries({ date, studentId: student.id }))[0].feedbackSatisfaction, 'SATISFIED');
  app.setSession(teacher);
  const ownAttendance = await api.teacherAttendance(date);
  assert.ok(ownAttendance.length);
  const punched = await api.teacherCheckIn(ownAttendance[0].campusId, position());
  assert.equal((await api.teacherCheckIn(ownAttendance[0].campusId, position())).id, punched.id);
  app.setSession(parent);
  await assert.rejects(api.teacherAttendance(date), error => error.statusCode === 403);
  const boss = await api.demo('ADMIN');
  const principal = await api.demo('CAMPUS_ADMIN');
  for (const identity of [teacher, parent, boss, principal]) {
    app.setSession(identity);
    const staff = ['SUPER_ADMIN', 'ADMIN', 'TEACHER'].includes(identity.user.role);
    const manager = ['SUPER_ADMIN', 'ADMIN'].includes(identity.user.role);
    for (const name of ['dashboard', 'growth', 'follow-ups', 'students', 'homework', 'mistakes', 'fitness', 'points', 'lessons', 'summaries', 'safety'].concat(staff ? ['attendance', 'teacher-attendance', 'workbench'] : [], manager ? ['campuses', 'refunds', 'privacy'] : !staff ? ['refunds', 'privacy'] : [])) {
      const instance = loadPage(name);
      instance.data.date = date;
      await instance.refresh();
      assert.equal(instance.data.error, '', identity.user.role + ' ' + name + ': ' + instance.data.error);
      assert.equal(instance.data.loading, false);
    }
    if (!staff) { const authorizationPage = loadPage('pickup-authorization', { studentId: student.id }); await authorizationPage.refresh(); assert.equal(authorizationPage.data.error, ''); assert.equal(authorizationPage.data.faceStatus, 'NOT_CONFIGURED'); }
    for (const [name, query] of [['homework-detail', { id: homework.id, date }], ['mistake-detail', { id: mistake.id, studentId: student.id }]]) {
      const instance = loadPage(name, query);
      await instance.refresh();
      assert.equal(instance.data.error, '', name + ': ' + instance.data.error);
      assert.ok(instance.data.item);
    }
  }
  app.setSession(boss);
  const campuses = await api.campuses();
  assert.ok(campuses.length >= 2);
  for (const campus of campuses.slice(0, 2)) {
    app.setCampus(campus.id);
    const [scopedStudents, scopedDashboard, scopedTeachers] = await Promise.all([api.students({}), api.dashboard(date), api.teacherAttendance(date)]);
    assert.ok(scopedStudents.every(row => Number(row.campusId) === Number(campus.id)));
    assert.equal(scopedDashboard.studentCount, scopedStudents.length);
    assert.ok(scopedTeachers.every(row => Number(row.campusId) === Number(campus.id)));
    assert.equal((await api.me()).campusIds.length, boss.user.campusIds.length);
    assert.ok((await api.users()).some(row => row.role === 'ADMIN' && row.campusIds.some(id => Number(id) !== Number(campus.id))), '授权页须保留当前筛选之外的校长');
  }
  app.setSession(principal);
  const forbiddenCampus = campuses.find(row => !principal.user.campusIds.includes(row.id));
  assert.ok(forbiddenCampus);
  assert.throws(() => app.setCampus(forbiddenCampus.id), /无权访问/);
  await assert.rejects(api.updateAccess(principal.user.id, campuses.map(row => row.id)), error => error.statusCode === 403);
  assert.equal(format.businessText('NOT_STARTED PENDING_RECHECK MASTERED'), '未开始 待复核 已掌握');
  console.log('通过真实API：教师/家长会话与授权、图片上传和下载、作业分配/提交/退回/复核、家长错题录入/订正/教师复核、3次复习计划、体能幂等、积分幂等、批量签到签退、日报生成/编辑/发布/满意度反馈。');
  console.log('通过真实API与页面加载：四角色、两校区筛选、完整授权名单、禁止校长自行扩权、老师本人签到与重复提交、家长禁止读取老师考勤；HTTP请求 ' + calls.length + ' 次；联调日期 ' + date + '。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
