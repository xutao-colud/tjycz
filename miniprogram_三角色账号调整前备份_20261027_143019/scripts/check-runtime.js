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
  request(options) { lastRequest = options; if (networkFailure) options.fail({}); else options.success(response); },
  uploadFile(options) { lastUpload = options; options.success({ statusCode: 200, data: JSON.stringify({ id: 17, name: '口头作业.mp3', contentType: 'audio/mpeg', url: '/api/files/17?token=fixture' }) }); },
  getRecorderManager() { return recorder; }, authorize(options) { if (authorized) options.success(); else options.fail(); },
  openSetting(options) { authorized = true; options.success({ authSetting: { 'scope.record': true } }); }
};
require(path.join(root, 'app.js'));
app.onLaunch();
const api = require(path.join(root, 'utils/api.js'));
const session = require(path.join(root, 'utils/session.js'));
const { definePage } = require(path.join(root, 'utils/page.js'));
const format = require(path.join(root, 'utils/format.js'));
const identity = { token: 'fixture-token', user: { id: 1, role: 'TEACHER', name: '老师' }, demo: true };
const flush = () => new Promise(resolve => setImmediate(resolve));
function instance(definition, props) {
  const value = Object.assign({}, definition.methods, { data: Object.assign({}, structuredClone(definition.data), props), events: [], setData(patch) { Object.assign(this.data, patch); }, triggerEvent(name, detail) { this.events.push({ name, detail }); } });
  definition.lifetimes.attached.call(value);
  return value;
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
  networkFailure = false;
  response = { statusCode: 200, data: {} };
  const fileURL = api.fileUrl({ url: '/api/files/17?token=fixture' });
  assert.equal(fileURL, 'http://localhost:18080/api/files/17?token=fixture');
  await api.upload('/tmp/oral.mp3');
  assert.equal(lastUpload.name, 'file');
  assert.equal(lastUpload.header.Authorization, 'Bearer fixture-token');
  assert.equal(lastUpload.url, 'http://localhost:18080/api/files');
  definePage({ async loadData() { throw new Error('服务离线'); } });
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
  console.log('通过运行时校验：鉴权头、编码参数、401清会话、403保留错误、网络失败、文件地址/上传字段、页面重试/互斥、MP3录音60秒/授权拒绝恢复/上传/离页停止、学生选择授权过滤、中文状态。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
