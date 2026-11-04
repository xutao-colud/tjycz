/* Actual API integration using the mini program's adapter and page loaders.
 * Run only with the local demo server: node scripts/check-api.js
 * Creates explicitly named demo records on the current business date. No production use.
 */
const assert = require('node:assert/strict');
const path = require('node:path');
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
  async request(options) {
    try {
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
const api = require(path.join(root, 'utils/api.js'));
const format = require(path.join(root, 'utils/format.js'));
function loadPage(name, query) {
  const file = path.join(root, 'pages', name, 'index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  const value = Object.assign({}, page, { data: structuredClone(page.data), setData(patch) { Object.assign(this.data, patch); } });
  if (value.onLoad) value.onLoad(query || {});
  value.data.teacher = ['ADMIN', 'TEACHER'].includes(app.globalData.session.user.role);
  return value;
}
async function main() {
  const health = await api.health();
  assert.equal(health.demo, true, '仅允许 local 演示服务执行');
  const teacher = await api.demo('TEACHER');
  app.setSession(teacher);
  const teacherStudents = await api.students({});
  const parent = await api.demo('PARENT');
  app.setSession(parent);
  const parentStudents = await api.students({});
  const student = parentStudents.find(row => teacherStudents.some(other => other.id === row.id));
  assert.ok(student, '教师与家长应有共同授权学生');
  const forbidden = teacherStudents.find(row => !parentStudents.some(other => other.id === row.id));
  if (forbidden) await assert.rejects(api.student(forbidden.id), error => error.statusCode === 403);
  const date = format.today();
  const title = '小程序接口联调 ' + Date.now();
  app.setSession(teacher);
  const created = await api.createHomework({ date, subject: '数学', type: 'WRITTEN', title, description: '原生前端接口验证', studentIds: [student.id], attachmentIds: [] });
  assert.equal(created.assignedCount, 1);
  let homework = (await api.homework({ date, studentId: student.id })).find(row => row.taskId === created.taskId);
  assert.ok(homework);
  app.setSession(parent);
  const file = await api.upload('fixture.png');
  assert.ok(file.id);
  const fileURL = api.fileUrl(file);
  assert.ok(!new URL(fileURL).pathname.includes('/api/api/'));
  assert.equal((await fetch(fileURL)).status, 200);
  await api.homeworkAttachments(homework.id, [file.id]);
  await api.homeworkStatus(homework.id, 'PENDING_REVIEW');
  app.setSession(teacher);
  await api.reviewHomework(homework.id, { status: 'CORRECTION', accuracy: 80, comment: '请补充步骤' });
  app.setSession(parent);
  await api.homeworkStatus(homework.id, 'PENDING_RECHECK');
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
  const checkin = await api.checkIn({ date, studentIds: [student.id] });
  assert.equal(checkin.results[0].success, true);
  assert.equal((await api.checkOut({ date, studentIds: [student.id] })).results[0].success, true);
  await api.generateSummaries({ date, studentIds: [student.id] });
  let summary = (await api.summaries({ date, studentId: student.id }))[0];
  if (summary.status === 'DRAFT') {
    await api.editSummary(summary.id, '小程序真实接口联调评语');
    summary = await api.publishSummary(summary.id);
  }
  assert.equal(summary.status, 'PUBLISHED');
  assert.equal(summary.notificationStatus, 'NOT_CONFIGURED');
  app.setSession(parent);
  await api.feedbackSummary(summary.id, { rating: 5, text: '小程序联调反馈' });
  assert.equal((await api.summaries({ date, studentId: student.id }))[0].feedbackRating, 5);
  for (const identity of [teacher, parent]) {
    app.setSession(identity);
    for (const name of ['dashboard', 'students', 'homework', 'mistakes', 'fitness', 'points', 'lessons', 'summaries'].concat(identity.user.role === 'TEACHER' ? ['attendance'] : [])) {
      const instance = loadPage(name);
      instance.data.date = date;
      await instance.refresh();
      assert.equal(instance.data.error, '', identity.user.role + ' ' + name + ': ' + instance.data.error);
      assert.equal(instance.data.loading, false);
    }
    for (const [name, query] of [['homework-detail', { id: homework.id, date }], ['mistake-detail', { id: mistake.id, studentId: student.id }]]) {
      const instance = loadPage(name, query);
      await instance.refresh();
      assert.equal(instance.data.error, '', name + ': ' + instance.data.error);
      assert.ok(instance.data.item);
    }
  }
  assert.equal(format.businessText('NOT_STARTED PENDING_RECHECK MASTERED'), '未开始 待复核 已掌握');
  console.log('通过真实API：教师/家长会话与授权、图片上传和下载、作业分配/提交/退回/复核、家长错题录入/订正/教师复核、3次复习计划、体能幂等、积分幂等、批量签到签退、日报生成/编辑/发布/评分。');
  console.log('通过页面数据加载：教师11个业务页、家长10个业务页；HTTP请求 ' + calls.length + ' 次；联调日期 ' + date + '。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
