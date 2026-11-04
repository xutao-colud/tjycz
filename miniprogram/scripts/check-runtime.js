const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const storage = new Map();
let app;
let page;
let component;
let response = { statusCode: 200, data: {} };
let networkFailure = false;
let lastRequest;
let lastUpload;
let reLaunchCount = 0;
let authorized = true;
let recordingOptions;
const listeners = {};
const recorder = {
  onStart(fn) { listeners.start = fn; }, offStart() {}, onStop(fn) { listeners.stop = fn; }, offStop() {}, onError(fn) { listeners.error = fn; }, offError() {},
  start(options) { recordingOptions = options; listeners.start(); },
  stop() { listeners.stop({ tempFilePath: '/tmp/oral.mp3', fileSize: 1024 }); }
};
global.getApp = () => app;
global.App = value => { app = value; };
global.Page = value => { page = value; };
global.Component = value => { component = value; };
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key),
  reLaunch(options) { reLaunchCount++; if (options.complete) options.complete(); }, showToast() {}, stopPullDownRefresh() {},
  request(options) { lastRequest = options; if (networkFailure) options.fail(typeof networkFailure === 'object' ? networkFailure : {}); else options.success(response); },
  uploadFile(options) { lastUpload = options; options.success({ statusCode: 200, data: JSON.stringify({ id: 17, name: '口头作业.mp3', contentType: 'audio/mpeg', url: '/api/files/17?ticket=fixture-ticket' }) }); },
  getRecorderManager() { return recorder; }, authorize(options) { if (authorized) options.success(); else options.fail(); },
  openSetting(options) { authorized = true; options.success({ authSetting: { 'scope.record': true } }); }
};
require(path.join(root, 'app.js'));
app.onLaunch();
const api = require(path.join(root, 'utils/api.js'));
const apiBase = require(path.join(root, 'utils/config.js')).apiBase.replace(/\/$/, '');
const session = require(path.join(root, 'utils/session.js'));
const { definePage } = require(path.join(root, 'utils/page.js'));
const format = require(path.join(root, 'utils/format.js'));
const growth = require(path.join(root, 'utils/growth.js'));
const identity = { token: 'fixture-token', user: { id: 1, role: 'TEACHER', name: '老师', campusIds: [1, 2], campuses: [{ id: 1, name: 'A校区' }, { id: 2, name: 'B校区' }] }, demo: true };
const flush = () => new Promise(resolve => setImmediate(resolve));
function instance(definition, props) {
  const value = Object.assign({}, definition.methods, { data: Object.assign({}, structuredClone(definition.data), props), events: [], setData(patch) { Object.assign(this.data, patch); }, triggerEvent(name, detail) { this.events.push({ name, detail }); } });
  definition.lifetimes.attached.call(value);
  return value;
}
async function sameCampusBindingChangesInvalidateLocalContext(screen) {
  const immediateRequest = wx.request, immediateUpload = wx.uploadFile, loadData = screen.loadData;
  try {
    for (const role of ['TEACHER', 'PARENT']) {
      const user = { id: role === 'TEACHER' ? 1001 : 1002, name: '授权测试账号', role, campusIds: [1], campuses: [{ id: 1, name: 'A校区' }], classIds: role === 'TEACHER' ? [10] : [], studentIds: role === 'PARENT' ? [101] : [] };
      app.setSession({ token: 'binding-fixture-' + role, user, demo: false });
      app.setCampus(1);
      screen.setData({ answer: 42, draftText: '旧学生的未提交内容', attachmentIds: [17], studentIds: [101], classIndex: 2 });
      app.globalData.selectedStudentIds = [101];
      const draftScope = ['homework', '2026-10-28', 101];
      growth.draft(draftScope, { submissionText: '旧学生的本机草稿', attachmentIds: [17] });
      assert.equal(growth.draft(draftScope).submissionText, '旧学生的本机草稿');
      const before = session.context();
      wx.request = options => { lastRequest = options; };
      wx.uploadFile = options => { lastUpload = options; };
      const staleRequest = api.students({}), pendingRequest = lastRequest;
      const staleUpload = api.upload('/tmp/oral.mp3'), pendingUpload = lastUpload;
      let releasePage;
      screen.loadData = () => new Promise(resolve => { releasePage = resolve; });
      const stalePage = screen.refresh();
      // These are the current grants returned by /me after changing a class/child in the same campus.
      app.updateUser({ ...user, classIds: role === 'TEACHER' ? [20] : [], studentIds: role === 'PARENT' ? [102] : [] }, false);
      assert.equal(app.globalData.selectedCampusId, 1, '同校区改授权保留有效校区筛选');
      assert.equal(session.isCurrentContext(before), false, role + ' 同校区改授权必须使旧上下文失效');
      assert.deepEqual(screen.data.studentIds, []);
      assert.deepEqual(app.globalData.selectedStudentIds, []);
      assert.deepEqual(screen.data.attachmentIds, []);
      assert.equal(screen.data.draftText, '');
      assert.equal(screen.data.classIndex, 0);
      assert.deepEqual(growth.draft(draftScope), {}, '新授权范围不能恢复旧范围的本机草稿');
      pendingRequest.success({ statusCode: 200, data: [{ id: 101 }] });
      pendingUpload.success({ statusCode: 200, data: JSON.stringify({ id: 17 }) });
      releasePage({ answer: 99, draftText: '旧请求内容' });
      await assert.rejects(staleRequest, error => error.code === 'CONTEXT_CHANGED');
      await assert.rejects(staleUpload, error => error.code === 'CONTEXT_CHANGED');
      await stalePage;
      assert.equal(screen.data.answer, 0, '旧页面加载不能回填新授权范围');
      assert.equal(screen.data.draftText, '');
    }
  } finally {
    wx.request = immediateRequest;
    wx.uploadFile = immediateUpload;
    screen.loadData = loadData;
  }
}
async function privacyAsyncResultsRespectContext() {
  const privacy = require(path.join(root, 'utils/privacy.js'));
  const originals = Object.assign({}, privacy), originalModal = wx.showModal;
  const originalPages = global.getCurrentPages;
  require(path.join(root, 'pages/privacy/index.js'));
  const screen = Object.assign({}, page, { data: structuredClone(page.data), setData(patch) { Object.assign(this.data, patch); } });
  let refreshes = 0;
  screen.refresh = async () => { refreshes++; };
  wx.showModal = options => options.success({ confirm: true });
  global.getCurrentPages = () => [screen];
  try {
    const base = { parent: true, manager: true, studentId: 101, reason: '旧申请说明', reviewId: 1, reviewNote: '旧核对说明', erasureId: 1, erasureBlocks: [], eraseConfirmation: '确认脱敏', requests: [{ id: 1, canExport: true, type: 'ERASURE', status: 'APPROVED', studentName: '测试孩子' }] };
    const event = { currentTarget: { dataset: { id: 1 } } };
    const cases = [
      { api: 'export', action: 'export', result: { student: { id: 101 } }, patch: { exportText: '新账号资料' }, field: 'exportText' },
      { api: 'checkErasure', action: 'checkErasure', result: { blocks: ['旧账号事项'] }, patch: { erasureId: 66, erasureBlocks: ['新账号事项'] }, field: 'erasureBlocks' },
      { api: 'createRequest', action: 'createRequest', result: {}, patch: { reason: '新账号申请草稿' }, field: 'reason' },
      { api: 'review', action: 'saveReview', result: {}, patch: { reviewId: 66 }, field: 'reviewId' },
      { api: 'erase', action: 'executeErasure', result: {}, patch: { erasureId: 66, exportText: '新账号资料' }, field: 'erasureId' }
    ];
    for (const test of cases) {
      app.setSession(Object.assign({}, identity, { token: 'privacy-old-' + test.api }));
      screen._unloaded = false; screen.setData(Object.assign({}, structuredClone(base), { busy: false }));
      let release;
      privacy[test.api] = () => new Promise(resolve => { release = resolve; });
      const pending = screen[test.action](event); await flush();
      assert.equal(typeof release, 'function', test.action + ' 必须已发出请求');
      const before = refreshes;
      app.setSession(Object.assign({}, identity, { token: 'privacy-new-' + test.api }));
      screen.setData(test.patch); release(test.result); await pending;
      assert.deepEqual(screen.data[test.field], test.patch[test.field], test.action + ' 旧身份结果不得回填或清空新身份数据');
      assert.equal(refreshes, before, test.action + ' 旧身份操作不得刷新新页面');
      privacy[test.api] = originals[test.api];
    }
    screen.setData(Object.assign({}, structuredClone(base), { busy: false, exportText: '' }));
    privacy.export = async () => ({ student: { id: 101 } });
    await screen.export(event);
    assert.equal(JSON.parse(screen.data.exportText).student.id, 101, '当前身份资料副本应正常显示');
    let release;
    privacy.export = () => new Promise(resolve => { release = resolve; });
    screen.setData({ exportText: '' });
    const pending = screen.export(event); screen.onUnload(); release({ student: { id: 101 } }); await pending;
    assert.equal(screen.data.exportText, '', '页面卸载后不得写入资料副本');
  } finally {
    Object.assign(privacy, originals); wx.showModal = originalModal; global.getCurrentPages = originalPages;
  }
}
async function main() {
  app.setSession(identity);
  await api.students({ keyword: '张 & 王', classId: '' });
  assert.ok(lastRequest.url.includes('keyword=' + encodeURIComponent('张 & 王')));
  assert.ok(!lastRequest.url.includes('classId'));
  assert.equal(lastRequest.header.Authorization, 'Bearer fixture-token');
  await api.demo('PARENT');
  assert.equal(lastRequest.header.Authorization, undefined);
  response = { statusCode: 403, data: { message: '未绑定孩子', code: 'FORBIDDEN' } };
  await assert.rejects(api.student(99), error => error.message === '未绑定孩子' && error.statusCode === 403);
  assert.ok(session.current());
  response = { statusCode: 401, data: { message: '会话过期' } };
  await assert.rejects(api.students({}), error => error.statusCode === 401);
  assert.equal(session.current(), null);
  assert.equal(reLaunchCount, 1);
  app.setSession(identity);
  networkFailure = true;
  await assert.rejects(api.me(), /网络连接失败/);
  for (const test of [
    { failure: { errMsg: 'request:fail url not in domain list', errno: 600002 }, hint: /合法域名/ },
    { failure: { errMsg: 'request:fail Local network permission denied', errno: 5 }, hint: /本地网络/ },
    { failure: { errMsg: 'request:fail Local network permission denied https://school.example/api', errno: 5 }, hint: /本地网络/ },
    { failure: { errMsg: 'request:fail timeout', errno: 7 }, hint: /请求超时/ },
    { failure: { errMsg: 'request:fail ERR_SSL_PROTOCOL_ERROR', errCode: -1200 }, hint: /HTTPS/ }
  ]) {
    networkFailure = test.failure;
    await assert.rejects(api.request('GET', '/auth/challenge', { purpose: 'LOGIN' }, true), error => {
      assert.equal(error.code, 'NETWORK_ERROR');
      assert.equal(error.errMsg, test.failure.errMsg);
      assert.equal(error.errno, test.failure.errno === undefined ? test.failure.errCode : test.failure.errno);
      assert.match(error.message, test.hint);
      assert.ok(error.message.includes('/auth/challenge'), '失败提示必须指明实际接口');
      assert.ok(error.message.includes(test.failure.errMsg), '真机错误原因不能被统一提示隐藏');
      assert.ok(error.message.includes(String(error.errno)), '真机错误码必须保留');
      return true;
    });
  }
  networkFailure = { errMsg: 'request:fail connect http://192.168.31.28:18080/api/files/17?ticket=private-fixture#fragment', errno: 0 };
  await assert.rejects(api.me(), error => {
    assert.ok(error.message.includes('request:fail connect'));
    assert.ok(!error.message.includes('private-fixture') && !error.errMsg.includes('private-fixture'), '网络诊断不得暴露链接票据');
    return true;
  });
  networkFailure = false;
  response = { statusCode: 200, data: {} };
  const fileURL = api.fileUrl({ url: '/api/files/17?ticket=fixture-ticket' });
  assert.equal(fileURL, apiBase + '/files/17?ticket=fixture-ticket');
  await api.upload('/tmp/oral.mp3');
  assert.equal(lastUpload.name, 'file');
  assert.equal(lastUpload.header.Authorization, 'Bearer fixture-token');
  assert.equal(lastUpload.url, apiBase + '/files');
  definePage({ data: { answer: 0, draftText: '', attachmentIds: [], classIndex: 0, mediaReset: 0 }, async loadData() { throw new Error('服务离线'); } });
  const screen = Object.assign({}, page, { data: structuredClone(page.data), setData(patch) { Object.assign(this.data, patch); } });
  await screen.refresh();
  assert.equal(screen.data.loading, false);
  assert.equal(screen.data.error, '服务离线');
  screen.loadData = async () => ({ answer: 42 });
  await screen.refresh();
  assert.equal(screen.data.error, '');
  assert.equal(screen.data.answer, 42);
  let release;
  let writes = 0;
  const pending = screen.act(async () => { writes++; await new Promise(resolve => { release = resolve; }); }, '', false);
  assert.equal(await screen.act(async () => { writes++; }), false);
  release();
  await pending;
  assert.equal(writes, 1);
  assert.equal(screen.data.busy, false);
  global.getCurrentPages = () => [screen];
  screen.setData({ attachmentIds: [17], classIndex: 2, studentIds: [99] });
  app.globalData.selectedStudentIds = [99];
  app.setCampus(1);
  assert.deepEqual(screen.data.attachmentIds, []);
  assert.deepEqual(screen.data.studentIds, []);
  assert.deepEqual(app.globalData.selectedStudentIds, []);
  assert.equal(screen.data.classIndex, 0);
  assert.equal(screen.data.mediaReset, 1);
  await api.students({});
  assert.equal(lastRequest.header['X-Campus-Id'], '1');
  await api.me();
  assert.equal(lastRequest.header['X-Campus-Id'], undefined, '刷新身份不能被已撤销的校区筛选阻断');
  await api.upload('/tmp/oral.mp3');
  assert.equal(lastUpload.header['X-Campus-Id'], '1');
  assert.throws(() => app.setCampus(99), /无权访问/);

  const immediateRequest = wx.request;
  wx.request = options => { lastRequest = options; };
  const staleRequest = api.students({});
  app.setCampus(2);
  lastRequest.success({ statusCode: 200, data: [{ id: 99 }] });
  await assert.rejects(staleRequest, error => error.code === 'CONTEXT_CHANGED');
  const stale401 = api.students({});
  app.setSession(Object.assign({}, identity, { token: 'new-account-token' }));
  lastRequest.success({ statusCode: 401, data: { message: '旧会话过期' } });
  await assert.rejects(stale401, error => error.code === 'CONTEXT_CHANGED');
  assert.equal(session.current().token, 'new-account-token', '旧请求401不能清除新账号会话');
  wx.request = immediateRequest;

  const immediateUpload = wx.uploadFile;
  wx.uploadFile = options => { lastUpload = options; };
  const staleUpload = api.upload('/tmp/oral.mp3');
  app.setCampus(1);
  lastUpload.success({ statusCode: 200, data: JSON.stringify({ id: 99 }) });
  await assert.rejects(staleUpload, error => error.code === 'CONTEXT_CHANGED');
  wx.uploadFile = immediateUpload;
  screen.loadData = () => new Promise(resolve => { release = resolve; });
  const stalePage = screen.refresh();
  app.setCampus(2);
  release({ answer: 99 });
  await stalePage;
  assert.equal(screen.data.answer, 0, '切换校区后旧页面加载不得回填');
  screen.setData({ attachmentIds: [17] });
  app.updateUser({ campusIds: [1], campuses: [{ id: 1, name: 'A校区' }] });
  assert.equal(app.globalData.selectedCampusId, null, '授权撤销时自动退出原校区');
  assert.deepEqual(screen.data.attachmentIds, []);
  app.setSession(identity);
  app.setCampus(2);
  screen.loadData = async () => ({ answer: 42 });
  response = { statusCode: 200, data: { id: 1, name: '老师', role: 'TEACHER', campusIds: [1], campuses: [{ id: 1, name: 'A校区' }] } };
  await screen.retry();
  assert.equal(app.globalData.selectedCampusId, null, '重试先刷新被撤销的校区权限');
  assert.deepEqual(screen.data.user.campusIds, [1]);
  assert.equal(screen.data.answer, 42);
  screen.setData({ attachmentIds: [17] });
  response = { statusCode: 503, data: { message: '临时离线' } };
  await screen.retry();
  assert.equal(screen.data.error, '临时离线');
  assert.deepEqual(screen.data.attachmentIds, [17], '网络错误保留同一权限范围内未提交的表单');
  await sameCampusBindingChangesInvalidateLocalContext(screen);
  await privacyAsyncResultsRespectContext();
  app.setSession(identity);
  response = { statusCode: 200, data: {} };
  global.getCurrentPages = () => [];
  require(path.join(root, 'components/media-field/index.js'));
  const media = instance(component, { disabled: false, audioEnabled: true, resetKey: 0 });
  authorized = false;
  media.record();
  assert.equal(media.data.recordDenied, true);
  assert.equal(media.data.busy, false);
  media.settings();
  assert.equal(media.data.recordDenied, false);
  media.record();
  assert.equal(recordingOptions.format, 'mp3');
  assert.equal(recordingOptions.duration, 60000);
  assert.equal(media.data.recording, true);
  assert.ok(media.events.some(event => event.name === 'uploading' && event.detail.busy));
  media.stopRecord();
  await flush();
  assert.equal(media.data.recording, false);
  assert.equal(media.data.busy, false);
  assert.equal(media.data.files.length, 1);
  assert.ok(media.events.some(event => event.name === 'change' && event.detail.ids[0] === 17));
  media.record();
  assert.equal(media.data.recording, true);
  component.pageLifetimes.hide.call(media);
  await flush();
  assert.equal(media.data.recording, false);
  assert.equal(media.data.files.length, 1, '离页取消录音，不新增文件');
  component.lifetimes.detached.call(media);
  require(path.join(root, 'components/student-picker/index.js'));
  let selectedPatch;
  component.observers['students, selected'].call({ setData(patch) { selectedPatch = patch; } }, [{ id: 1, name: '授权孩子' }], [1, 99]);
  assert.equal(selectedPatch.count, 1);
  assert.equal(selectedPatch.chosen, '授权孩子');
  assert.equal(format.businessText('PENDING_REVIEW CORRECTION PENDING_RECHECK'), '待批改 需订正 待复核');
  console.log('通过运行时校验：鉴权与校区请求头、同校区班级/孩子改授权清理选择/表单并隔离草稿/异步响应、权限撤销清理、校区切换清空表单/选择、旧请求/旧401/旧上传/旧页面隔离、隐私资料导出/审核/脱敏异步身份与卸载隔离、错误与重试、MP3录音/授权/上传/离页停止、学生选择授权过滤。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
