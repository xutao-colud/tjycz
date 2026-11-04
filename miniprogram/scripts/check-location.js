const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
let current = 1, getLocation;
const sandbox = { module: { exports: {} }, require: () => ({ context: () => current, isCurrentContext: value => value === current }), wx: { getLocation: options => getLocation(options) } };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../utils/location.js'), 'utf8'), sandbox);
(async () => {
  const mini = sandbox.module.exports.currentLocation;
  getLocation = options => { assert.equal(options.type, 'wgs84'); assert.equal(options.isHighAccuracy, true); options.success({ latitude: 31.23, longitude: 121.47, accuracy: 5 }); };
  assert.equal((await mini()).accuracy, 5);
  getLocation = options => options.fail({});
  await assert.rejects(mini(), /无法获取/);
  getLocation = options => { current++; options.success({ latitude: 31.23, longitude: 121.47, accuracy: 5 }); };
  await assert.rejects(mini(), /已切换/);
  console.log('PASS: mini-program WGS84 capture, denial and context cancellation');
})().catch(error => { console.error(error); process.exitCode = 1; });
