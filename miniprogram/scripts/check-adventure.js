const assert = require('node:assert/strict');
let definition, calls = [], deferred;
const app = { globalData: { session: { token: 'test', user: { id: 1, role: 'PARENT' } }, contextVersion: 0, selectedCampusId: null } };
global.getApp = () => app;
global.Page = value => { definition = value; };
global.wx = { showToast() {}, stopPullDownRefresh() {}, navigateTo(value) { calls.push(value); } };
const api = require('../utils/api');
const profile = { studentId: 1, studentName: '虚构孩子', pet: 'CAT', petName: '小蓝', mode: 'ADVENTURE', stage: 1, xp: 22, level: 2, levelXp: 2, nextLevelXp: 40, chapter: 1, seals: 1, badges: 0, tasks: [{ key: 'LEARNING', done: true }], recent: [], date: '2026-11-07' };
api.students = async () => [{ id: 1, name: '虚构孩子' }, { id: 2, name: '虚构孩子二' }];
api.request = async (method, path, body) => { calls.push({ method, path, body }); if (deferred) return new Promise(resolve => { deferred.resolve = resolve; }); if (method === 'PATCH') Object.assign(profile, body); return structuredClone(profile); };
require('../pages/adventure/index');
function mount() { return Object.assign({}, definition, { data: structuredClone(definition.data), query: {}, setData(patch, callback) { Object.assign(this.data, patch); callback?.(); } }); }
async function main() {
  const page = mount(); await page.refresh();
  assert.equal(page.data.adventure.percent, 5);
  await page.mode({ currentTarget: { dataset: { mode: 'PET' } } });
  assert.equal(page.data.adventure.mode, 'PET'); assert.equal(page.data.adventure.xp, 22);
  await page.pet({ currentTarget: { dataset: { pet: 'DINO' } } });
  assert.equal(page.data.adventure.petImage, '/assets/adventure/dino-1.svg');
  calls = []; page.data.teacher = true; await page.savePreference({ mode: 'ADVENTURE' }); assert.equal(calls.length, 0);
  page.data.teacher = false; page.data.nameDraft = ' '; await page.rename(); assert.equal(calls.length, 0);
  page.mode({ currentTarget: { dataset: { mode: 'INVALID' } } }); assert.equal(calls.length, 0);
  page.data.studentId = 999; await page.refresh(); assert.equal(page.data.adventure, null); assert.equal(calls.length, 0, 'unknown children never trigger a detail request');
  page.data.studentId = 1; deferred = {}; const pending = page.refresh(); await new Promise(resolve => setImmediate(resolve));
  app.globalData.contextVersion++; page.resetScopeData(); deferred.resolve(structuredClone(profile)); await pending;
  assert.equal(page.data.adventure, null, 'stale account response cannot refill the page');
  deferred = null; await page.refresh(); calls = []; page.task({ currentTarget: { dataset: { key: 'INVALID' } } }); assert.equal(calls.length, 0);
  page.data.adventure = null; page.onUnload(); await page.refresh(); assert.equal(calls.length, 0);
  console.log('PASS: shared progress, preference validation, staff restrictions, child scope, stale response and unload isolation');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
