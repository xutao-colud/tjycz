const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
let now = 100000;
let nextId = 0;
const timers = new Map();
let app;
let definition;
let failNavigation = false;
const navigations = [];
const storage = new Map();
const realNow = Date.now;
Date.now = () => now;
global.setTimeout = (callback, delay) => { const id = ++nextId; timers.set(id, { callback, at: now + delay }); return id; };
global.clearTimeout = id => timers.delete(id);
global.getApp = () => app;
global.App = value => { app = value; };
global.Page = value => { definition = value; };
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key),
  stopPullDownRefresh() {},
  reLaunch(options) { navigations.push(options.url); if (failNavigation && options.fail) options.fail(); }
};
require(path.join(root, 'app.js'));
app.onLaunch();
const api = require(path.join(root, 'utils/api'));
api.health = async () => ({ status: 'UP', demo: true });
api.login = async () => ({ token: 'fixture-session', user: { id: 1, name: '测试老师', role: 'TEACHER' }, demo: true });
require(path.join(root, 'pages/login/index.js'));
function screen() {
  const value = Object.assign({}, definition, { data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch); } });
  value.onLoad({ stage: 'login', identity: 'TEACHER' });
  value.onShow();
  value.setData({ username: 'admin', password: 'admin', agreed: true });
  return value;
}
function advance(milliseconds) {
  const end = now + milliseconds;
  while (true) {
    const due = [...timers].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
    if (!due) break;
    now = due[1].at;
    timers.delete(due[0]);
    due[1].callback();
  }
  now = end;
}
async function main() {
  const page = screen();
  await page.submit();
  assert.equal(page.data.stage, 'success');
  assert.equal(page.data.successLeaving, false);
  advance(1499);
  assert.equal(page.data.successLeaving, false, '成功提示需完整展示1.5秒');
  assert.equal(navigations.length, 0);
  advance(1);
  assert.equal(page.data.successLeaving, true, '1.5秒后开始渐隐');
  advance(1199);
  assert.equal(navigations.length, 0, '整页淡出未结束不提前切页');
  advance(1);
  assert.deepEqual(navigations, ['/pages/workbench/index?fromLogin=1']);
  advance(10000);
  assert.equal(navigations.length, 1, '不会重复导航');
  page.onUnload();

  const hidden = screen();
  await hidden.submit();
  advance(1000);
  hidden.onHide();
  assert.equal(timers.size, 0, '隐藏时清理计时器');
  advance(4000);
  assert.equal(navigations.length, 1);
  hidden.onShow();
  advance(0);
  assert.equal(hidden.data.successLeaving, true, '返回页面按成功时间计算，不重新等待1.5秒');
  advance(1200);
  assert.equal(navigations.length, 2);
  hidden.onUnload();

  const disposed = screen();
  await disposed.submit();
  disposed.onUnload();
  advance(5000);
  assert.equal(navigations.length, 2, '已卸载页面不跳转');
  assert.equal(timers.size, 0);

  const revoked = screen();
  await revoked.submit();
  app.clearSession();
  advance(2700);
  assert.equal(navigations.length, 2);
  assert.equal(revoked.data.stage, 'login', '会话清除后不进入首页');
  revoked.onUnload();

  const failed = screen();
  await failed.submit();
  failNavigation = true;
  advance(2700);
  assert.equal(failed.data.stage, 'login');
  assert.equal(failed.data.successLeaving, false);
  assert.match(failed.data.error, /首页/);
  failed.onUnload();
  failNavigation = false;

  const logout = screen();
  await logout.submit();
  logout.logout();
  assert.equal(timers.size, 0, '退出账号时取消自动跳转');
  logout.onUnload();
  const template = require('node:fs').readFileSync(path.join(root, 'pages/login/index.wxml'), 'utf8');
  const success = template.match(/<block wx:elif="{{stage === 'success'}}">([\s\S]*?)<\/block>/)[1];
  assert.ok(!success.includes('<button'), '成功页不显示进入首页按钮');
  const styles = require('node:fs').readFileSync(path.join(root, 'pages/login/index.wxss'), 'utf8');
  assert.match(styles, /\.login-shell\.success-leaving\s*\{[^}]*animation:\s*success-fade-out\s+1\.2s\s+ease-in-out\s+forwards/, '整页使用连续的淡出动画');
  assert.match(styles, /@keyframes\s+success-fade-out\s*\{\s*from\s*\{\s*opacity:\s*1;?\s*\}\s*to\s*\{\s*opacity:\s*0;?\s*\}/, '淡出动画从完全可见过渡到透明');
  assert.ok(success.includes('success-progress'), '成功页保留进度条');
  assert.match(styles, /animation:\s*success-progress-fill\s+1\.5s\s+linear\s+both/, '进度条在欢迎提示期间填充');
  console.log('通过登录自动过渡：欢迎提示与进度条展示1.5秒，整页持续淡出1.2秒后仅跳转一次，并检查生命周期与异常恢复。');
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => { Date.now = realNow; });
