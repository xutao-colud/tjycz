const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const pair = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicKey = pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const challenges = new Map();
const wire = [];
let counter = 0;
let failRandom = false;
let failNetwork = false;
const storage = new Map();
let app;
global.App = value => { app = value; };
global.getApp = () => app;
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key),
  getRandomValues(options) {
    if (failRandom) { options.fail({}); return; }
    const bytes = crypto.randomBytes(options.length);
    options.success({ randomValues: Uint8Array.from(bytes).buffer });
  },
  request(options) {
    if (failNetwork) { options.fail({ errMsg: 'request:fail url not in domain list' }); return; }
    const url = new URL(options.url);
    wire.push({ path: url.pathname, method: options.method, data: options.data });
    if (url.pathname === '/api/auth/challenge') {
      const value = { challengeId: 'test-challenge-' + (++counter), publicKey, algorithm: 'RSA-OAEP-256', expiresAt: new Date(Date.now() + 60000).toISOString() };
      challenges.set(value.challengeId, value);
      options.success({ statusCode: 200, data: value });
    } else if (url.pathname === '/api/auth/login') {
      assert.ok(!Object.hasOwn(options.data, 'password'), '网络请求不能包含 password 明文字段');
      const { username, role, challengeId, encryptedPassword } = options.data;
      assert.ok(challenges.delete(challengeId));
      const label = Buffer.from(JSON.stringify(['wanfu-password-v1', 'LOGIN', challengeId, username]));
      const plain = crypto.privateDecrypt({ key: pair.privateKey, oaepHash: 'sha256', oaepLabel: label, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING }, Buffer.from(encryptedPassword, 'base64')).toString('utf8');
      assert.equal(plain, 'admin');
      options.success({ statusCode: 200, data: { token: 'fixture-token', user: { id: 1, role: { PARENT: 'PARENT', TEACHER: 'TEACHER', CAMPUS_ADMIN: 'ADMIN', ADMIN: 'SUPER_ADMIN' }[role] }, demo: true } });
    } else throw new Error('Unexpected request');
  }
};
require(path.join(root, 'app.js'));
app.onLaunch();
const api = require(path.join(root, 'utils/api'));
const { encryptPassword } = require(path.join(root, 'utils/password'));
async function main() {
  const sandbox = { module: { exports: {} }, setTimeout, clearTimeout, Uint8Array, ArrayBuffer, TextEncoder };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/vendor/forge.js'), 'utf8'), sandbox);
  assert.ok(sandbox.module.exports.pki.publicKeyFromAsn1, '加密库无需 Node/window 浏览器全局');
  for (const role of ['PARENT', 'TEACHER', 'CAMPUS_ADMIN', 'ADMIN']) {
    const result = await api.login({ username: ' admin ', password: 'admin', role });
    assert.equal(result.user.role, { PARENT: 'PARENT', TEACHER: 'TEACHER', CAMPUS_ADMIN: 'ADMIN', ADMIN: 'SUPER_ADMIN' }[role]);
    assert.ok(!JSON.stringify(wire).includes('"password"'));
  }
  const posts = wire.filter(item => item.method === 'POST');
  assert.equal(new Set(posts.map(item => item.data.encryptedPassword)).size, 4, '同密码每次密文不同');
  const challenge = { challengeId: 'unicode-case', publicKey, algorithm: 'RSA-OAEP-256', expiresAt: new Date(Date.now() + 60000).toISOString() };
  const normalized = await encryptPassword(challenge, '\u0000admin\u001f', 'admin', 'LOGIN');
  assert.equal(normalized.username, 'admin', '与后端Java trim一致');
  const encrypted = await encryptPassword(challenge, '账号', '中文密码 Test123!', 'LOGIN');
  const plaintext = crypto.privateDecrypt({ key: pair.privateKey, oaepHash: 'sha256', oaepLabel: Buffer.from(JSON.stringify(['wanfu-password-v1', 'LOGIN', challenge.challengeId, '账号'])) }, Buffer.from(encrypted.encryptedPassword, 'base64')).toString('utf8');
  assert.equal(plaintext, '中文密码 Test123!');
  await assert.rejects(encryptPassword({ ...challenge, expiresAt: 'invalid' }, 'admin', 'admin', 'LOGIN'), /配置无效/);
  await assert.rejects(encryptPassword({ ...challenge, expiresAt: new Date(Date.now() - 1).toISOString() }, 'admin', 'admin', 'LOGIN'), /过期/);
  await assert.rejects(encryptPassword(challenge, 'admin', '密'.repeat(25), 'LOGIN'), /72/);
  const before = wire.filter(item => item.method === 'POST').length;
  failRandom = true;
  await assert.rejects(api.login({ username: 'admin', password: 'admin', role: 'PARENT' }), /安全随机数/);
  assert.equal(wire.filter(item => item.method === 'POST').length, before, '随机源失败不能明文降级');
  failRandom = false;
  const oldRandom = wx.getRandomValues;
  delete wx.getRandomValues;
  await assert.rejects(api.login({ username: 'admin', password: 'admin' }), /更新微信/);
  wx.getRandomValues = oldRandom;
  failNetwork = true;
  await assert.rejects(api.health(), /合法域名/);
  console.log('通过密码传输检查：RSA-OAEP SHA256/MGF1 SHA256互通、中文UTF8、四种身份密文请求无password、密文随机化、challenge过期与72字节限制、安全随机源失败不降级、无Node/window依赖和网络错误提示。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
