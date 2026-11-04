/* Real page handlers with local wx fixtures. No server or real student data. */
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const geometry = require('../utils/scan');
const app = { globalData: { session: { token: 'scan-fixture', user: { id: 7, role: 'TEACHER' } }, contextVersion: 1, selectedCampusId: 1, selectedStudentIds: [101] } };
let definition, requests = [], toasts = [], navigation = [], handler, timer;
global.getApp = () => app;
global.Page = value => { definition = value; };
global.wx = {
  showToast: value => toasts.push(value.title), stopPullDownRefresh() {},
  navigateTo: value => navigation.push(value.url),
  showModal: value => value.success({ confirm: true }),
  request(options) { const url = new URL(options.url), route = url.pathname.replace(/^\/api/, ''); requests.push({ route, method: options.method, body: options.data }); handler(route, options); },
  createSelectorQuery() { const q = { in() { return q; }, select() { return q; }, boundingClientRect(callback) { callback({ width: 300, height: 400 }); return q; }, exec() {} }; return q; }
};
require('../pages/scan/index');
function job(patch = {}) { return Object.assign({ id: 11, studentId: 101, version: 1, subject: '数学', status: 'READY', pagePhoto: { id: 21, url: '/api/files/21?ticket=fixture' }, questions: [1, 2].map(id => ({ id, displayNumber: String(id), groupName: '虚构计算', questionText: '虚构题干' + id, regions: [{ x: .1, y: id === 1 ? .1 : .5, width: .8, height: .3 }], warnings: [], importedMistakeId: null })) }, patch); }
function mount(value = job()) { const p = Object.assign({}, definition, { data: structuredClone(definition.data), setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } }); p.onLoad({ studentId: '101' }); p.setData(Object.assign({ loading: false, students: [{ id: 101, campusId: 1 }], studentIds: [101] }, p.jobPatch(value, []))); return p; }
function ok(options, data) { options.success({ statusCode: 200, data }); }
const event = (index, extra = {}) => ({ currentTarget: { dataset: Object.assign({ index }, extra) } });
const cases = [
  ['initial scan results refresh quickly then back off while queued', async () => {
    const realSet = global.setTimeout, realClear = global.clearTimeout; let next; const delays = [];
    try {
      global.setTimeout = (callback, delay) => { next = callback; delays.push(delay); return 1; }; global.clearTimeout = () => {};
      const p = mount(job({ status: 'QUEUED', questions: [] })); p._visible = true;
      handler = (route, options) => ok(options, job({ status: 'QUEUED', questions: [] }));
      p.afterLoad(); for (let i = 0; i < 12; i++) await next();
      assert.deepEqual(delays.slice(0, 12), Array(12).fill(250)); assert.equal(delays[12], 1000);
      p.setData(p.jobPatch(job({ id: 12, status: 'QUEUED', questions: [] }), [])); p.schedulePoll(); assert.equal(delays.at(-1), 250); p.onHide();
    } finally { global.setTimeout = realSet; global.clearTimeout = realClear; }
  }],
  ['recognized printed title and body fill the editor and remain editable', () => {
    const value = job(); value.questions[0].displayNumber = '4'; value.questions[0].groupName = '虚构印刷标题'; value.questions[0].questionText = '4. 虚构印刷题干\n虚构价目表';
    const p = mount(value); assert.equal(p.data.activeQuestion.groupName, '虚构印刷标题'); assert.equal(p.data.activeQuestion.questionText, value.questions[0].questionText);
    p.editText({ currentTarget: { dataset: { field: 'groupName' } }, detail: { value: '老师校对标题' } });
    p.editText({ currentTarget: { dataset: { field: 'questionText' } }, detail: { value: '老师校对印刷题干' } });
    const row = geometry.payload(p.data.questions)[0]; assert.equal(row.groupName, '老师校对标题'); assert.equal(row.questionText, '老师校对印刷题干'); assert.equal(p.data.dirty, true);
  }],
  ['disabled guardian consent permits camera scanning without a grant request', async () => {
    const choose = wx.chooseMedia, upload = wx.uploadFile; let picked = false;
    try {
      const p = mount();
      handler = (route, options) => ok(options, route === '/privacy/consents' ? { consentRequired: false, effectiveMedia: true, effectiveService: true, canConsent: false } : job({ id: 12, status: 'QUEUED', questions: [] }));
      wx.chooseMedia = options => { picked = true; options.success({ tempFiles: [{ tempFilePath: '/tmp/fixture.png' }] }); };
      wx.uploadFile = options => options.success({ statusCode: 200, data: JSON.stringify({ id: 98 }) });
      await p.start(); assert.equal(picked, true); assert.equal(p.data.jobId, 12); assert.equal(navigation.length, 0);
      assert.ok(!requests.some(r => r.route === '/privacy/consents' && r.method === 'POST'));
    } finally { wx.chooseMedia = choose; wx.uploadFile = upload; }
  }],
  ['bonus headings display as titles without a numeric question prefix', () => {
    const value = job(); value.questions[0].displayNumber = '3'; value.questions[1].displayNumber = '附加题'; const p = mount(value);
    assert.deepEqual(p.data.questions.map(q => q.caption), ['第3题', '附加题']); assert.ok(!('caption' in geometry.payload(p.data.questions)[1]));
  }],
  ['rescanning uses original file in a new task and keeps the previous confirmed record intact', async () => {
    const value = job({ sourcePhoto: { id: 31, url: '/api/files/31?ticket=fixture' } }); const p = mount(value), original = structuredClone(p.data.job);
    handler = (route, options) => ok(options, route === '/privacy/consents' ? { studentId: 101, effectiveMedia: true } : job({ id: 12, status: 'QUEUED', questions: [] }));
    await p.rescan(); const post = requests.find(r => r.method === 'POST'); assert.equal(post.route, '/teaching-capture/jobs'); assert.equal(post.body.photoFileId, 31); assert.equal(p.data.jobId, 12); assert.deepEqual(value.questions, original.questions); assert.ok(!requests.some(r => r.method === 'PATCH' || /\/import$|\/retry$/.test(r.route)));
  }],
  ['cancelled rescan does not submit or replace the active task', async () => {
    const show = wx.showModal;
    try { wx.showModal = options => options.success({ confirm: false }); const p = mount(job({ sourcePhoto: { id: 31 } })); await p.rescan(); assert.equal(p.data.jobId, 11); assert.equal(p.data.pendingPhoto, null); assert.equal(requests.length, 0); } finally { wx.showModal = show; }
  }],
  ['missing consent is shown before opening camera and only the selected child status is accessible', async () => {
    let picked = false, uploaded = false;
    wx.chooseMedia = options => { picked = true; options.fail({ errMsg: 'fixture camera must not open before consent' }); }; wx.uploadFile = options => { uploaded = true; options.fail({}); };
    handler = (route, options) => ok(options, { studentId: 101, canConsent: false, effectiveService: false, effectiveMedia: false });
    const p = mount(); await p.start();
    assert.equal(picked, false); assert.equal(uploaded, false); assert.equal(p.data.mediaConsent.effectiveMedia, false);
    assert.deepEqual(requests.map(r => [r.method, r.route]), [['GET', '/privacy/consents']]);
    p.openMediaConsent(); assert.deepEqual(navigation, ['/pages/privacy/index?studentId=101']);
  }],
  ['retained uploaded photo can be submitted after consent without uploading again or granting on behalf of parent', async () => {
    const p = mount(); p.setData({ pendingPhoto: { id: 99 }, createRequestId: 'retained-scan-fixture' });
    let allowed = false; handler = (route, options) => ok(options, route === '/privacy/consents' ? { studentId: 101, effectiveMedia: allowed, effectiveService: allowed, canConsent: false } : job());
    await p.createScan(); assert.equal(requests.length, 1); assert.equal(p.data.pendingPhoto.id, 99); assert.equal(p.data.createRequestId, 'retained-scan-fixture');
    allowed = true; await p.createScan(); const post = requests.find(r => r.method === 'POST');
    assert.equal(post.route, '/teaching-capture/jobs'); assert.equal(post.body.photoFileId, 99); assert.equal(post.body.requestId, 'retained-scan-fixture');
    assert.ok(!requests.some(r => r.route === '/privacy/consents' && r.method !== 'GET')); assert.equal(p.data.pendingPhoto, null);
  }],
  ['withdrawal after taking photo stops scan creation and keeps the existing photo for retry', async () => {
    const p = mount(); let reads = 0;
    handler = (route, options) => { assert.equal(route, '/privacy/consents'); reads++; ok(options, { studentId: 101, effectiveService: true, effectiveMedia: reads === 1, canConsent: false }); };
    wx.chooseMedia = options => options.success({ tempFiles: [{ tempFilePath: '/tmp/fictional.png' }] });
    wx.uploadFile = options => options.success({ statusCode: 200, data: JSON.stringify({ id: 98 }) });
    await p.start(); assert.equal(reads, 2); assert.equal(p.data.pendingPhoto.id, 98); assert.equal(p.data.mediaConsent.effectiveMedia, false); assert.ok(!requests.some(r => r.method === 'POST'));
  }],
  ['checking again clears the block only after guardian consent becomes effective', async () => {
    const p = mount(); p.setData({ mediaConsent: { effectiveMedia: false } }); let allowed = false;
    handler = (route, options) => ok(options, { studentId: 101, effectiveService: true, effectiveMedia: allowed });
    await p.recheckConsent(); assert.equal(p.data.mediaConsent.effectiveMedia, false); assert.equal(toasts.length, 0);
    allowed = true; await p.recheckConsent(); assert.equal(p.data.mediaConsent.effectiveMedia, true); assert.ok(toasts.some(t => /授权已生效/.test(t))); assert.ok(requests.every(r => r.method === 'GET'));
  }],
  ['dragging and resizing stay inside the page', () => {
    const r = { x: .1, y: .2, width: .5, height: .3 };
    assert.deepEqual(geometry.moveRegion(r, -3, 5, false), { x: 0, y: .7, width: .5, height: .3 });
    const resized = geometry.moveRegion(r, 5, -4, true); assert.equal(resized.width, .9); assert.equal(resized.height, .025);
  }],
  ['recognized candidates start unselected and imported questions cannot be selected', () => {
    const p = mount(); assert.equal(p.data.selectedCount, 0); p.toggleSelected(); assert.equal(p.data.selectedCount, 1);
    const done = job(); done.questions[0].importedMistakeId = 91; p.setData(p.jobPatch(done, [0])); p.toggleSelected(); assert.equal(p.data.selectedCount, 0);
  }],
  ['split merge remove and manual frame keep stable editable regions', async () => {
    const p = mount(); p.split(); assert.equal(p.data.questions.length, 3); assert.equal(p.data.questions[1].id, null); assert.equal(p.data.questions[0].regions[0].height, .15);
    await p.mergeNext(); assert.equal(p.data.questions.length, 2); assert.equal(p.data.questions[0].regions.length, 2); await p.removeQuestion(); assert.equal(p.data.questions.length, 1);
    p.addQuestion(); assert.equal(p.data.questions.length, 2); assert.equal(p.data.adjusting, true); assert.equal(p.data.activeIndex, 1);
    const payload = geometry.payload(p.data.questions); assert.ok(!('selected' in payload[0])); assert.ok(!('style' in payload[0].regions[0]));
  }],
  ['unsaved changes survive returning from image preview', async () => {
    const p = mount(); p.editText({ currentTarget: { dataset: { field: 'questionText' } }, detail: { value: '老师未保存校对' } });
    handler = (route, options) => ok(options, route === '/students' ? [{ id: 101, campusId: 1 }] : route.endsWith('scan-status') ? { available: true } : route.endsWith('/11') ? job() : []);
    p.setData(await p.loadData()); assert.equal(p.data.questions[0].questionText, '老师未保存校对'); assert.equal(p.data.dirty, true);
  }],
  ['save happens before import and only selected persisted IDs are sent', async () => {
    const p = mount(); p.focus(event(1)); p.toggleSelected(); p.editText({ currentTarget: { dataset: { field: 'questionText' } }, detail: { value: '老师校对乙' } });
    const saved = job({ version: 2 }); saved.questions[1].questionText = '老师校对乙';
    handler = (route, options) => {
      if (options.method === 'PATCH') return ok(options, saved);
      if (route.endsWith('/import')) { saved.questions[1].importedMistakeId = 92; return ok(options, { results: [{ id: 2, success: true, mistakeId: 92, message: '已加入错题本' }] }); }
      ok(options, saved);
    };
    await p.importSelected(); assert.deepEqual(requests.map(r => r.method), ['PATCH', 'POST', 'GET']); assert.deepEqual(requests[1].body, { expectedVersion: 2, questionIds: [2] }); assert.equal(p.data.selectedCount, 0);
  }],
  ['partial import keeps only failed questions selected for retry', async () => {
    const p = mount(); p.toggleSelected(); p.focus(event(1)); p.toggleSelected(); const saved = job(); saved.questions[0].importedMistakeId = 91;
    handler = (route, options) => ok(options, route.endsWith('/import') ? { results: [{ id: 1, success: true }, { id: 2, success: false, message: '虚构失败' }] } : saved);
    await p.importSelected(); assert.equal(p.data.selectedCount, 1); assert.deepEqual(p.data.selectedIndices, [1]);
  }],
  ['hidden page and changed campus cannot receive a late polling result', async () => {
    const realSet = global.setTimeout, realClear = global.clearTimeout;
    try {
      global.setTimeout = callback => { timer = callback; return 1; }; global.clearTimeout = () => {};
      const p = mount(job({ status: 'RECOGNIZING', questions: [] })); p._visible = true; p.afterLoad(); let pending; handler = (route, options) => { pending = options; };
      const poll = timer(); p.onHide(); ok(pending, job()); await poll; assert.equal(p.data.job.status, 'RECOGNIZING');
      p._visible = true; p.afterLoad(); const poll2 = timer(); app.globalData.contextVersion++; ok(pending, job()); await poll2; assert.equal(p.data.job.status, 'RECOGNIZING'); p.onUnload();
    } finally { global.setTimeout = realSet; global.clearTimeout = realClear; }
  }],
  ['upload preserves retry identity and does not create a scan after context changes', async () => {
    let finish; const picking = new Promise(resolve => { wx.chooseMedia = options => { finish = options.success; resolve(); }; }); wx.uploadFile = () => { throw new Error('不应上传'); };
    handler = (route, options) => ok(options, { studentId: 101, effectiveService: true, effectiveMedia: true });
    const p = mount(); const action = p.start(); await picking; app.globalData.contextVersion++; finish({ tempFiles: [{ tempFilePath: '/tmp/fictional.png' }] }); await action; assert.equal(requests.length, 1); assert.equal(requests[0].route, '/privacy/consents'); assert.equal(p.data.job.id, 11);
  }],
  ['direct history link aligns the displayed student to its task', async () => {
    const p = mount(job({ studentId: 102 })); p.setData({ studentIds: [101] });
    handler = (route, options) => ok(options, route === '/students' ? [{ id: 101 }, { id: 102 }] : route.endsWith('/11') ? job({ studentId: 102 }) : route.endsWith('scan-status') ? { available: true } : []);
    const patch = await p.loadData(); assert.deepEqual(patch.studentIds, [102]);
  }],
  ['explicit reload recovers from a version conflict and clears obsolete selections', async () => {
    const p = mount(); p.toggleSelected(); p.editText({ currentTarget: { dataset: { field: 'questionText' } }, detail: { value: '待放弃的修改' } });
    handler = (route, options) => ok(options, job({ version: 3 }));
    await p.reloadJob(); assert.equal(p.data.job.version, 3); assert.equal(p.data.dirty, false); assert.equal(p.data.selectedCount, 0);
  }],
  ['hiding while initial loading completes does not restart background polling', async () => {
    const realSet = global.setTimeout, realClear = global.clearTimeout;
    try {
      let scheduled = 0; global.setTimeout = () => { scheduled++; return 1; }; global.clearTimeout = () => {};
      const p = mount(job({ status: 'RECOGNIZING', questions: [] })); p._visible = true; let finish; p.loadData = () => new Promise(resolve => { finish = resolve; });
      const loading = p.refresh(); p.onHide(); finish({}); await loading; assert.equal(scheduled, 0);
    } finally { global.setTimeout = realSet; global.clearTimeout = realClear; }
  }],
  ['full-image preview refreshes its short-lived private file ticket', async () => {
    const p = mount(); let shown; wx.previewImage = options => { shown = options.current; };
    handler = (route, options) => ok(options, { id: 21, url: '/api/files/21?ticket=fresh-fixture' });
    await p.preview(); assert.equal(requests[0].route, '/files/21/access'); assert.ok(shown.endsWith('ticket=fresh-fixture'));
  }],
  ['a late consent check after switching account cannot enable camera or replace consent state', async () => {
    const p = mount(); let pending, picked = false; wx.chooseMedia = () => { picked = true; };
    handler = (route, options) => { pending = options; };
    const action = p.start(); app.globalData.contextVersion++; ok(pending, { studentId: 101, effectiveMedia: true, effectiveService: true }); await action;
    assert.equal(picked, false); assert.equal(p.data.mediaConsent, null); assert.ok(!requests.some(r => r.method === 'POST'));
  }]
];
const watchdog = setTimeout(() => { console.error('扫描检查超时，存在未完成的异步操作'); process.exitCode = 1; }, 5000);
(async () => { for (const [name, test] of cases) { requests = []; toasts = []; navigation = []; handler = () => { throw new Error('unexpected request'); }; app.globalData.contextVersion++; await test(); console.log('通过：' + name); } console.log(cases.length + '/' + cases.length + ' 拍照识题检查通过'); })().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => clearTimeout(watchdog));
