/* Runs real page handlers + API wrappers against in-memory wx fixtures only.
 * node miniprogram/scripts/check-business-flows.js
 * Does not connect to a server, persist real data or emulate WeChat rendering.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const storage = new Map();
const app = { globalData: { session: { token: 'business-fixture', user: { id: 1, role: 'ADMIN' } }, contextVersion: 0, selectedCampusId: 1 } };
const issuedCode = '638294';
const pickup = { id: 41, studentId: 101, studentName: '虚构学生甲', pickupName: '虚构接送人', relationship: '监护人', version: 3, canCheckout: true };
let definition;
let requests = [];
let toasts = [];
let navigation = [];
let clipboard = [];
let phoneCalls = [];
let handler;
let modal;
let locate;
global.getApp = () => app;
global.Page = value => { definition = value; };
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, structuredClone(value)), removeStorageSync: key => storage.delete(key),
  showToast: value => toasts.push(value.title), stopPullDownRefresh() {},
  navigateTo: value => navigation.push(value.url), redirectTo: value => navigation.push(value.url), setClipboardData: value => clipboard.push(value.data), makePhoneCall: value => phoneCalls.push(value.phoneNumber),
  showModal(options) { if (modal) modal(options); else options.success({ confirm: true }); },
  getLocation(options) { if (locate) locate(options); else options.success({ latitude: 31.23, longitude: 121.47, accuracy: 5 }); },
  request(options) {
    const route = new URL(options.url).pathname.replace(/^\/api/, '');
    requests.push({ route, method: options.method, body: structuredClone(options.data), headers: options.header });
    const response = handler ? handler(route, options) : { statusCode: 200, data: {} };
    if (response) options.success(response);
  }
};
const definitions = {};
for (const name of ['safety', 'pickup-authorization', 'workbench', 'attendance', 'class-reports', 'campus-reports', 'family-services', 'summaries', 'capture', 'rewards', 'lessons', 'mistakes', 'notifications', 'privacy', 'scan', 'homework-drafts', 'complaints']) {
  require(path.join(root, 'pages', name, 'index.js'));
  definitions[name] = definition;
}
const format = require(path.join(root, 'utils/format'));
const event = data => ({ currentTarget: { dataset: data } });
const flush = () => new Promise(resolve => setImmediate(resolve));
function mount(name, patch = {}) {
  const page = Object.assign({}, definitions[name], {
    data: structuredClone(definitions[name].data),
    setData(value, done) { Object.assign(this.data, value); if (done) done(); },
    async loadData() { return {}; }
  });
  page.onLoad({ date: format.today(), studentId: '101' });
  page.setData(Object.assign({ loading: false, teacher: true, admin: true }, patch));
  return page;
}
function pickupForm() { return mount('safety', { studentId: 101, compose: 'pickup', pickupName: ' 虚构接送人 ', relationship: ' 监护人 ', phone: '13800000000', pickups: [pickup] }); }
function authorizationForm(patch = {}) {
  const tomorrow = new Date(Date.parse(format.today() + 'T00:00:00Z') + 86400000).toISOString().slice(0, 10);
  return mount('pickup-authorization', Object.assign({ teacher: false, studentId: 101, pickupName: ' 虚构接送人 ', relationship: ' 亲属 ', phone: '13800001234', startDate: tomorrow, endDate: tomorrow }, patch));
}
function desk(patch = {}) {
  return mount('workbench', Object.assign({ studentIds: [101, 102], roster: [{ id: 101, name: '虚构学生甲' }, { id: 102, name: '虚构学生乙' }] }, patch));
}
function ok(data) { return { statusCode: 200, data }; }
function reset() {
  requests = []; toasts = []; navigation = []; clipboard = []; phoneCalls = []; handler = null; modal = null; locate = null; storage.clear();
  app.globalData.contextVersion++;
}
const cases = [
  ['complaints preserve request identity after failure and block duplicate taps', async () => {
    const p=mount('complaints',{teacher:false,studentId:101,title:'反馈',content:'具体情况',photos:[{id:7}]});let pending;
    handler=(route,options)=>{pending=options;return null;};const first=p.submit();await flush();await p.submit();assert.equal(requests.length,1);const key=requests[0].body.requestId;
    pending.fail({errMsg:'request:fail timeout'});await first;assert.equal(p.data.content,'具体情况');assert.equal(p.data.photos.length,1);
    handler=()=>ok({id:54});await p.submit();assert.equal(requests[1].body.requestId,key);assert.equal(p.data.selectedId,54);assert.equal(p.data.photos.length,0);
  }],
  ['complaint supplement and internal note use separate actions and versions', async () => {
    const p=mount('complaints',{teacher:false,detail:{id:54,version:2},reply:'补充反馈',actionNames:['补充反馈'],photos:[]});handler=()=>ok({id:54});await p.send();assert.equal(requests[0].body.action,'SUPPLEMENT');assert.equal(requests[0].body.expectedVersion,2);
    const admin=mount('complaints',{detail:{id:54,version:3},reply:'内部处理',actionNames:['内部处理记录'],photos:[]});await admin.send();assert.equal(requests[1].body.action,'NOTE');assert.equal(requests[1].body.expectedVersion,3);
  }],
  ['scan creates a homework draft from selected questions without importing mistakes', async () => {
    const p=mount('scan',{jobId:12,job:{id:12,version:4},selectedCount:1,questions:[{id:32,selected:true},{id:33,selected:false}],dirty:false});handler=()=>ok({id:7});await p.homeworkDraft();assert.equal(requests[0].route,'/teaching-capture/jobs/12/homework-drafts');assert.deepEqual(requests[0].body.questionIds,[32]);assert.equal(requests[0].body.expectedVersion,4);assert.match(navigation[0],/homework-drafts\/index\?id=7/);assert.equal(requests.some(r=>r.route.endsWith('/import')),false);
  }],
  ['homework draft assignment requires selection and explicit confirmation', async () => {
    const p=mount('homework-drafts',{draft:{id:7,version:1,subject:'数学'},items:[{id:1,displayNumber:'3',title:'第三题',questionText:'题干'}],studentIds:[],taskDate:format.today()});await p.assign();assert.equal(requests.length,0);
    p.setData({studentIds:[101]});modal=o=>o.success({confirm:false});await p.assign();assert.equal(requests.length,0);
    modal=o=>o.success({confirm:true});handler=(route)=>route.endsWith('/assign')?ok({id:7,status:'ASSIGNED',taskIds:[9]}):ok({id:7,version:2,items:p.data.items});await p.assign();assert.equal(requests[0].method,'PATCH');assert.equal(requests[1].route,'/homework-drafts/7/assign');assert.deepEqual(requests[1].body,{expectedVersion:2,studentIds:[101],confirmed:true});
  }],

  ['disabled guardian authorization cannot open a consent dialog or send a grant', async () => {
    const page = mount('privacy', { parent: true, studentId: 101, policy: { version: 'fixture', consentRequired: false } });
    await page.consent(event({ scope: 'MEDIA', grant: '1' }));
    assert.equal(requests.length, 0); assert.equal(navigation.length, 0);
  }],
  ['avatar consent recovery opens the selected child without uploading first', async () => {
    const choose = wx.chooseMedia, upload = wx.uploadFile;
    try {
      let picked = false, uploaded = false;
      wx.chooseMedia = options => { picked = true; options.success({ tempFiles: [{ tempFilePath: '/tmp/avatar.png' }] }); };
      wx.uploadFile = options => { uploaded = true; options.success({ statusCode: 200, data: JSON.stringify({ id: 99 }) }); };
      handler = route => route === '/privacy/consents' ? ok({ canConsent: true, effectiveService: true, effectiveMedia: false }) : { statusCode: 403, data: { message: '孩子的服务或媒体授权尚未完成或已撤回，请监护人在隐私与授权中确认' } };
      const page = mount('family-services', { teacher: false, family: { student: { id: 102, name: '虚构学生乙', campusId: 1 } } });
      await page.choosePhoto(event({ kind: 'avatar' }));
      assert.equal(picked, false, '缺少授权时不要先让家长选图再上传失败');
      assert.equal(uploaded, false);
      assert.deepEqual(navigation, ['/pages/privacy/index?studentId=102']);
      assert.ok(!requests.some(r => r.method === 'POST'), '不能自动替监护人授权');
      const privacy = mount('privacy'); privacy.onLoad({ studentId: '102' });
      assert.equal(String(privacy.data.studentId), '102', '授权页必须定位到正在上传头像的孩子');
    } finally { wx.chooseMedia = choose; wx.uploadFile = upload; }
  }],
  ['avatar consent cancellation keeps the page and sends no upload or grant', async () => {
    handler = () => ok({ canConsent: true, effectiveService: false, effectiveMedia: false });
    modal = options => options.success({ confirm: false });
    const page = mount('family-services', { teacher: false, family: { student: { id: 101, name: '虚构学生甲', campusId: 1 } } });
    await page.choosePhoto(event({ kind: 'avatar' }));
    assert.equal(navigation.length, 0); assert.equal(page.data.uploading, false);
    assert.equal(requests.length, 1); assert.equal(requests[0].route, '/privacy/consents');
  }],
  ['staff cannot grant avatar consent on behalf of a guardian', async () => {
    handler = () => ok({ canConsent: false, effectiveMedia: false });
    const page = mount('family-services', { family: { student: { id: 101, name: '虚构学生甲', campusId: 1 } } });
    await page.choosePhoto(event({ kind: 'avatar' }));
    assert.equal(navigation.length, 0); assert.equal(requests.length, 1);
    assert.ok(toasts.some(text => /绑定监护人/.test(text)));
    page.openMediaConsent(); assert.equal(navigation.length, 0);
  }],
  ['payment QR preview refreshes its private access ticket before opening', async () => {
    const before = wx.previewImage; let image;
    try {
      wx.previewImage = options => { image = options.current; };
      handler = () => ok({ id: 77, url: '/api/files/77?ticket=fresh-fixture' });
      const page = mount('family-services', { teacher: false, family: { settings: { paymentQr: { id: 77, url: '/api/files/77?ticket=expired-fixture' } } } });
      await page.openQr(); assert.equal(requests.length, 1); assert.equal(requests[0].route, '/files/77/access');
      assert.ok(image.includes('fresh-fixture')); assert.ok(!image.includes('expired-fixture'));
    } finally { wx.previewImage = before; }
  }],

  ['child avatar upload works without a guardian grant when the feature is disabled', async () => {
    const beforeChoose = wx.chooseMedia, beforeUpload = wx.uploadFile;
    try {
      wx.chooseMedia = options => options.success({ tempFiles: [{ tempFilePath: '/tmp/fictional-avatar.png' }] });
      wx.uploadFile = options => { assert.equal(options.header['X-Campus-Id'], '1'); options.success({ statusCode: 200, data: JSON.stringify({ id: 99, url: '/api/files/99?ticket=fixture' }) }); };
      handler = route => route === '/privacy/consents' ? ok({ consentRequired: false, effectiveMedia: true, effectiveService: true, canConsent: false }) : ok({});
      const page = mount('family-services', { teacher: false, family: { student: { id: 101, campusId: 1 }, settings: {} } });
      await page.choosePhoto(event({ kind: 'avatar' }));
      assert.equal(requests.length, 2); assert.equal(requests[1].route, '/students/101/avatar'); assert.deepEqual(requests[1].body, { avatarFileId: 99 });
      assert.equal(navigation.length, 0); assert.ok(!requests.some(r => r.method === 'POST'));
    } finally { wx.chooseMedia = beforeChoose; wx.uploadFile = beforeUpload; }
  }],
  ['late avatar uploads cannot change a child after context switch or page unload', async () => {
    const beforeChoose = wx.chooseMedia, beforeUpload = wx.uploadFile;
    try {
      for (const kind of ['context', 'unload']) {
        requests = [];
        handler = route => route === '/privacy/consents' ? ok({ effectiveMedia: true, canConsent: true }) : ok({});
        const page = mount('family-services', { teacher: false, family: { student: { id: 101, campusId: 1 }, settings: {} } });
        wx.chooseMedia = options => options.success({ tempFiles: [{ tempFilePath: '/tmp/fictional-avatar.png' }] });
        wx.uploadFile = options => { if (kind === 'context') app.globalData.contextVersion++; else page.onUnload(); options.success({ statusCode: 200, data: JSON.stringify({ id: 99, url: '/api/files/99?ticket=fixture' }) }); };
        await page.choosePhoto(event({ kind: 'avatar' })); assert.equal(requests.filter(r => r.method === 'PUT').length, 0);
      }
    } finally { wx.chooseMedia = beforeChoose; wx.uploadFile = beforeUpload; }
  }],

  ['parent renewal intentions send the account and its current version', async () => {
    const page = mount('family-services', { teacher: false, accounts: [{ id: 91, intentVersion: 2 }] });
    await page.intention({ ...event({ id: 91 }), detail: { value: '1' } });
    assert.equal(requests[0].method, 'PUT'); assert.equal(requests[0].route, '/accounts/91/renewal-intent');
    assert.deepEqual(requests[0].body, { intention: 'INTERESTED', expectedVersion: 2 });
    requests = []; await page.intention({ ...event({ id: 91 }), detail: { value: '0' } }); assert.equal(requests.length, 0);
  }],
  ['failed renewal intent retains the visible account and reports the error', async () => {
    handler = () => ({ statusCode: 409, data: { message: '意向已更新，请刷新' } });
    const page = mount('family-services', { teacher: false, accounts: [{ id: 91, intentVersion: 2, intention: 'NOT_NOW' }] });
    await page.intention({ ...event({ id: 91 }), detail: { value: '1' } });
    assert.equal(page.data.accounts[0].intention, 'NOT_NOW'); assert.match(page.data.actionError, /请刷新/);
  }],
  ['meal orders use the selected child and version without client money or dates', async () => {
    const page = mount('family-services', { teacher: false, family: { student: { id: 101 }, settings: { mealPrice: 1800 }, meal: { version: 3 } } });
    await page.orderMeal(event({ ordered: 'yes' })); assert.equal(requests[0].route, '/students/101/meal-order');
    assert.deepEqual(requests[0].body, { ordered: true, expectedVersion: 3 });
    page.setData({ family: { student: { id: 101 }, settings: { mealPrice: null }, meal: null } }); requests = [];
    await page.orderMeal(event({ ordered: 'yes' })); assert.equal(requests.length, 0);
    await page.orderMeal(event({ ordered: 'no' })); assert.deepEqual(requests[0].body, { ordered: false, expectedVersion: 0 });
  }],
  ['dissatisfied daily feedback requires a reason and submits no numeric score', async () => {
    const page = mount('summaries', { teacher: false, feedbackStudentId: 101, satisfaction: 'DISSATISFIED', feedbackText: '  ' });
    await page.sendFeedback(); assert.equal(requests.length, 0); assert.ok(toasts.some(text => /原因/.test(text)));
    page.setData({ feedbackText: ' 希望补充学习进展 ' }); await page.sendFeedback();
    assert.deepEqual(requests[0].body, { date: format.today(), satisfaction: 'DISSATISFIED', text: '希望补充学习进展' });
    assert.equal(requests[0].route, '/students/101/daily-feedback'); assert.equal(requests[0].method, 'PUT');
    assert.ok(!('rating' in requests[0].body));
  }],
  ['satisfied daily feedback allows a blank reason', async () => {
    const page = mount('summaries', { teacher: false, feedbackStudentId: 101, satisfaction: 'SATISFIED', feedbackText: '' });
    await page.sendFeedback(); assert.deepEqual(requests[0].body, { date: format.today(), satisfaction: 'SATISFIED', text: '' });
  }],

  ['campus summaries save the selected campus and original version', async () => {
    handler = (_route, options) => { assert.ok(options.url.includes('date=' + format.today())); return ok({ id: 7, version: 1 }); };
    const page = mount('campus-reports', { campusId: 1 }); page.edit(); page.setData({ content: ' 校区教学与服务有序 ', followUp: ' 跟进辅导 ', nextPlan: ' 组织教研 ' }); await page.save();
    assert.equal(requests[0].route, '/campus-reports/1'); assert.equal(requests[0].method, 'PUT'); assert.equal(requests[0].body.expectedVersion, 0); assert.equal(requests[0].body.content, '校区教学与服务有序'); assert.equal(page.data.editing, false);
  }],
  ['campus summary conflicts keep edits and future summaries send nothing', async () => {
    handler = () => ({ statusCode: 409, data: { message: '校区总结已由其他人更新' } });
    const page = mount('campus-reports', { campusId: 1, summary: { content: '原总结', version: 2 } }); page.edit(); page.setData({ content: '校长尚未保存的补充' }); await page.save();
    assert.equal(requests[0].body.expectedVersion, 2); assert.equal(page.data.editing, true); assert.equal(page.data.content, '校长尚未保存的补充');
    requests = []; page.setData({ readOnly: true }); await page.save(); assert.equal(requests.length, 0);
  }],
  ['principal class comment retries reuse the request id and preserve failed text', async () => {
    let attempts = 0;
    handler = () => ++attempts === 1 ? { statusCode: 500, data: { message: '虚构暂时失败' } } : ok({ id: 8 });
    const page = mount('campus-reports', { campusId: 1, classes: [{ id: 11, name: '向阳一班' }] }); page.comment(event({ id: 11 })); page.setData({ commentText: ' 保持分组辅导，继续关注审题习惯 ' }); await page.saveComment();
    assert.equal(page.data.commentingClassId, 11); assert.match(page.data.commentText, /分组辅导/); await page.saveComment();
    assert.equal(requests[0].route, '/class-reports/11/comments'); assert.equal(requests[0].body.text, '保持分组辅导，继续关注审题习惯'); assert.equal(requests[0].body.requestId, requests[1].body.requestId); assert.equal(page.data.commentingClassId, 0);
  }],
  ['teachers can read comments but cannot submit principal comments', async () => {
    const page = mount('class-reports', { classId: 11, admin: false }); page.comment(); assert.equal(page.data.commenting, false);
    page.setData({ commenting: true, commentText: '教师尝试点评' }); await page.saveComment(); assert.equal(requests.length, 0);
    handler = () => ok({ classId: 11, classes: [{ id: 11, name: '向阳一班', campusName: '主校区' }], overview: {}, readOnly: false, report: { content: '老师原始日报', version: 1 }, comments: [{ id: 8, text: '校长独立点评', createdAt: '2026-10-28T18:00:00' }] });
    const result = await definitions['class-reports'].loadData.call(page); assert.equal(result.report.content, '老师原始日报'); assert.equal(result.comments[0].text, '校长独立点评');
  }],
  ['class reports save the selected class date version and trimmed text', async () => {
    handler = () => ok({ id: 5, version: 1 });
    const page = mount('class-reports', { classId: 11 }); page.edit();
    page.setData({ content: ' 班级辅导按计划完成 ', followUp: ' 继续复习计算 ', nextPlan: ' 组织朗读 ' });
    await page.save();
    assert.equal(requests.length, 1); assert.equal(requests[0].method, 'PUT');
    assert.equal(requests[0].route, '/class-reports/11'); assert.equal(requests[0].body.expectedVersion, 0);
    assert.equal(requests[0].body.content, '班级辅导按计划完成'); assert.equal(page.data.editing, false);
  }],
  ['class report conflicts keep unsaved content and the original version', async () => {
    handler = () => ({ statusCode: 409, data: { message: '班级日报已由其他人更新' } });
    const page = mount('class-reports', { classId: 11, report: { content: '原记录', followUp: '', nextPlan: '', version: 3 } });
    page.edit(); page.setData({ content: '老师尚未保存的补充' }); await page.save();
    assert.equal(requests[0].body.expectedVersion, 3); assert.equal(page.data.editing, true);
    assert.equal(page.data.content, '老师尚未保存的补充'); assert.match(page.data.actionError, /其他人更新/);
  }],
  ['class reports require content and disallow future records', async () => {
    const page = mount('class-reports', { classId: 11 }); page.edit(); page.setData({ content: ' ' }); await page.save();
    page.setData({ content: '未来事实', readOnly: true }); await page.save(); assert.equal(requests.length, 0);
  }],
  ['workbench opens the current class daily report without selecting students', async () => {
    const page = desk({ classId: 11, studentIds: [] }); page.openClassReport();
    assert.deepEqual(navigation, ['/pages/class-reports/index?classId=11&date=' + page.data.date]);
  }],
  ['workbench arrivals send selected students without location or face calls', async () => {
    handler = () => ok({ results: [{ studentId: 101, success: true }] });
    locate = () => { throw new Error('student arrival must not request location'); };
    const page = desk({ classId: 11, classes: [{ id: 11, campusId: 1 }] }); await page.attendance(event({ out: '0' }));
    assert.equal(requests.length, 1); assert.equal(requests[0].route, '/attendance/check-in');
    assert.deepEqual(requests[0].body.studentIds, [101, 102]); assert.ok(!('location' in requests[0].body));
  }],
  ['arrival works without a configured location and never invokes phone location', async () => {
    handler = () => ok({ results: [{ studentId: 101, success: true, message: '签到成功' }] });
    locate = () => { throw new Error('student arrival must not request location'); };
    const page = mount('attendance', { studentIds: [101], students: [{ id: 101, name: '虚构学生' }] });
    await page.batch(event({ action: 'in' }));
    assert.equal(requests.length, 1); assert.equal(requests[0].route, '/attendance/check-in');
    assert.ok(!('location' in requests[0].body)); assert.ok(!('campusId' in requests[0].body));
  }],
  ['cancelled arrival confirmation sends no request', async () => {
    modal = options => options.success({ confirm: false });
    await mount('attendance', { studentIds: [101] }).batch(event({ action: 'in' }));
    assert.equal(requests.length, 0);
  }],
  ['changing account during arrival confirmation cancels the request', async () => {
    modal = options => { app.globalData.contextVersion++; options.success({ confirm: true }); };
    await mount('attendance', { studentIds: [101] }).batch(event({ action: 'in' }));
    assert.equal(requests.length, 0);
  }],
  ['unloading during arrival confirmation cancels the request', async () => {
    for (const page of [mount('attendance', { studentIds: [101] }), desk()]) {
      modal = options => { page.onUnload(); options.success({ confirm: true }); };
      await (page.batch ? page.batch(event({ action: 'in' })) : page.attendance(event({ out: '0' })));
    }
    assert.equal(requests.length, 0);
  }],
  ['pickup codes remain in page memory and clear on hide, scope reset and manual dismissal', async () => {
    handler = () => ok({ id: pickup.id, pickupCode: issuedCode });
    const page = pickupForm();
    await page.create();
    assert.equal(page.data.issuedCode, issuedCode, '创建结果应仅展示本次返回码');
    assert.equal(requests[0].route, '/pickups');
    assert.equal(requests[0].headers.Authorization, 'Bearer business-fixture');
    assert.ok(!JSON.stringify([...storage]).includes(issuedCode), '接送码不得写入本机缓存');
    assert.deepEqual(clipboard, [], '接送码不应自动写入剪贴板');
    page.setData({ code: issuedCode, identityChecked: true });
    page.onHide();
    assert.equal(page.data.issuedCode, ''); assert.equal(page.data.code, ''); assert.equal(page.data.identityChecked, false);
    await page.refresh();
    assert.equal(page.data.issuedCode, '', '重新显示页面不能从缓存恢复返回码');
    page.setData({ issuedCode }); page.clearCode(); assert.equal(page.data.issuedCode, '');
    page.setData({ issuedCode }); page.resetScopeData(); assert.equal(page.data.issuedCode, '');
  }],
  ['pickup/leave creation requires the student and every required field', async () => {
    for (const patch of [{ studentId: '' }, { pickupName: ' ' }, { relationship: ' ' }, { phone: ' ' }]) {
      const page = pickupForm(); page.setData(patch); await page.create();
    }
    await mount('safety', { studentId: 101, compose: 'leave', reason: ' ' }).create();
    assert.equal(requests.length, 0, '缺少必填字段不得发送请求');
    assert.equal(toasts.length, 5);
  }],
  ['creating or rotating a code cannot refill after hide, unload or a changed context', async () => {
    for (const rotate of [false, true]) for (const kind of ['hide', 'unload', 'context']) {
      const page = pickupForm();
      let pendingRequest;
      handler = (_route, options) => { pendingRequest = options; return null; };
      const pending = rotate ? page.processPickup(event({ id: pickup.id, action: 'rotate-code' })) : page.create();
      await flush();
      if (kind === 'hide') page.onHide();
      else if (kind === 'unload') page.onUnload();
      else { app.globalData.contextVersion++; page.resetScopeData(); }
      pendingRequest.success(ok({ pickupCode: issuedCode }));
      await pending;
      assert.equal(page.data.issuedCode, '', '迟到创建/重置结果不能重新显示接送码');
    }
  }],
  ['a context switch during post-create reload still rejects code refill', async () => {
    const page = pickupForm();
    handler = () => ok({ pickupCode: issuedCode });
    let finishReload;
    page.loadData = () => new Promise(resolve => { finishReload = resolve; });
    const pending = page.create();
    await flush();
    assert.equal(typeof finishReload, 'function');
    app.globalData.contextVersion++; page.resetScopeData();
    finishReload({});
    await pending;
    assert.equal(page.data.issuedCode, '', '刷新期间改权限后 act 不得返回成功并回填旧码');
  }],
  ['a confirmation opened under an old context cannot authorize a new-context request', async () => {
    const page = pickupForm();
    let dialog;
    modal = options => { dialog = options; };
    const pending = page.create();
    app.globalData.contextVersion++;
    dialog.success({ confirm: true });
    await pending;
    assert.equal(requests.length, 0, '旧身份确认框不得发送新上下文操作');
  }],
  ['identity verification requires name, four phone digits, six code digits and an explicit check', async () => {
    const valid = { pickups: [pickup], verifyId: pickup.id, pickupName: ' 虚构接送人 ', phoneLast4: '1234', code: issuedCode, identityChecked: true };
    for (const patch of [{ pickupName: ' ' }, { phoneLast4: '123' }, { phoneLast4: 'abcd' }, { code: '12345' }, { code: 'abcdef' }, { identityChecked: false }]) {
      const page = mount('safety', valid); page.setData(patch); await page.verify();
    }
    assert.equal(requests.length, 0, '不完整现场核验不得提交');
    handler = () => ok({ verified: true });
    const page = mount('safety', valid); await page.verify();
    assert.equal(requests.length, 1);
    assert.deepEqual(Object.assign({}, requests[0].body, { requestId: undefined }), { pickupName: '虚构接送人', phoneLast4: '1234', code: issuedCode, confirmedIdentity: true, expectedVersion: 3, requestId: undefined });
    assert.equal(page.data.code, ''); assert.equal(page.data.identityChecked, false);
    const template = fs.readFileSync(path.join(root, 'pages/safety/index.wxml'), 'utf8');
    assert.match(template, /bindtap="identity"[^>]*role="checkbox"/);
    assert.match(template, /wx:if="{{item\.canCheckout}}"[^>]*bindtap="checkout"/, '实际离校入口必须由服务端 capability 控制');
  }],
  ['a backend checkout item failure is shown as failure even with HTTP 200', async () => {
    const page = mount('safety', { pickups: [pickup] });
    handler = () => ok({ results: [{ studentId: 101, success: false, message: '请先现场核验' }] });
    await page.checkout(event({ id: pickup.id }));
    assert.equal(page.data.actionError, '请先现场核验');
    assert.ok(!toasts.includes('安全交接已记录'), '单项失败不得显示交接成功');
    handler = () => ok({ results: [{ studentId: 101, success: true }] });
    await page.checkout(event({ id: pickup.id }));
    assert.ok(toasts.includes('安全交接已记录'));
  }],
  ['common pickup contacts create edit default and delete with the current version', async () => {
    const row = { id: 71, studentId: 101, name: '虚构妈妈', relationship: '妈妈', phone: '13800001234', version: 5, canManage: true, isDefault: false };
    const page = mount('safety', { teacher: false, studentId: 101, contacts: [row], contactName: ' 虚构亲属 ', contactRelationship: ' 亲属 ', contactPhone: '13800005678', contactDefault: true });
    handler = () => ok(row);
    await page.saveContact();
    page.editContact(event({ id: row.id })); page.setData({ contactName: '虚构妈妈改名' }); await page.saveContact();
    await page.setDefault(event({ id: row.id })); await page.removeContact(event({ id: row.id }));
    assert.deepEqual(requests.map(row => [row.method, row.route]), [['POST', '/pickup-contacts'], ['PATCH', '/pickup-contacts/71'], ['POST', '/pickup-contacts/71/default'], ['DELETE', '/pickup-contacts/71']]);
    assert.equal(requests[0].body.name, '虚构亲属'); assert.equal(requests[0].body.isDefault, true); assert.ok(requests[0].body.requestId);
    for (const request of requests.slice(1)) assert.equal(request.body.expectedVersion, 5);
    page.setData({ teacher: true, contactName: '越权' }); await page.saveContact(); assert.equal(requests.length, 4);
  }],
  ['two-step authorization uses explicit guardian confirmation and makes no face request', async () => {
    const page = authorizationForm(); page.next(); assert.equal(page.data.step, 3);
    handler = () => ok({ id: 81, pickupCode: issuedCode, studentId: 101, pickupName: '虚构接送人', relationship: '亲属', mode: 'SINGLE', startDate: page.data.startDate, endDate: page.data.endDate, startTime: '18:30', endTime: '20:00', status: 'ACTIVE' });
    await page.create(); assert.equal(requests.length, 0);
    page.authorizationCheck(); await page.create(); assert.equal(page.data.step, 4); assert.equal(page.data.issuedCode, issuedCode);
    assert.equal(requests.length, 1); assert.equal(requests[0].route, '/pickups'); assert.equal(requests[0].body.mode, 'SINGLE');
    assert.ok(!('faceStatus' in requests[0].body)); assert.ok(!requests.some(row => row.route.includes('face')));
    page.shareCode(); assert.ok(clipboard[0].includes(issuedCode)); assert.ok(!JSON.stringify([...storage]).includes(issuedCode));
    page.onHide(); assert.equal(page.data.issuedCode, '');
    const source = fs.readFileSync(path.join(root, 'pages/pickup-authorization/index.js'), 'utf8') + fs.readFileSync(path.join(root, 'pages/pickup-authorization/index.wxml'), 'utf8');
    assert.doesNotMatch(source, /身份证|证件号|人脸|chooseMedia|chooseImage|uploadFile|createCameraContext/);
  }],
  ['multi-day authorization validates the window and binds a chosen contact version', async () => {
    const page = authorizationForm({ mode: 'MULTI', contactId: 71, contactVersion: 0, contactIndex: 1, authorizationChecked: true });
    page.setData({ endDate: '2000-01-01' }); page.next(); assert.equal(page.data.step, 1); assert.equal(requests.length, 0);
    page.setData({ endDate: page.data.startDate, startTime: '20:00', endTime: '18:30' }); page.next(); assert.equal(page.data.step, 1);
    page.setData({ endDate: new Date(Date.parse(page.data.startDate) + 2 * 86400000).toISOString().slice(0, 10), startTime: '18:30', endTime: '20:00', step: 3 });
    handler = () => ok({ pickupCode: issuedCode }); await page.create();
    assert.equal(requests.length, 1); assert.equal(requests[0].body.mode, 'MULTI'); assert.equal(requests[0].body.contactId, 71); assert.equal(requests[0].body.expectedContactVersion, 0);
    assert.ok(!('phone' in requests[0].body)); assert.ok(!('pickupName' in requests[0].body));
    const helper = require(path.join(root, 'utils/pickup'));
    assert.equal(helper.decorate({ status: 'ACTIVE', effectiveStatus: 'INVALIDATED' }).isHistory, true);
    assert.equal(helper.decorate({ status: 'ACTIVE', mode: 'MULTI', usedToday: true }).isHistory, false);
  }],
  ['changed or deleted common contacts reset the confirmation instead of silently authorizing a new person', async () => {
    for (const contacts of [[{ id: 71, version: 1, name: '变更后的虚构接送人', relationship: '亲属', phone: '13800005678' }], []]) {
      const page = authorizationForm({ step: 3, contactId: 71, contactVersion: 0, contactIndex: 1, authorizationChecked: true });
      handler = route => ok(route === '/students' ? [{ id: 101, name: '虚构学生甲', campusName: '虚构校区' }] : route === '/pickup-contacts' ? contacts : { providerConfigured: false, status: 'NOT_CONFIGURED' });
      page.loadData = definitions['pickup-authorization'].loadData; await page.refresh();
      assert.equal(page.data.step, 1); assert.equal(page.data.authorizationChecked, false);
      assert.ok(!requests.some(row => row.route === '/pickups'));
    }
  }],
  ['wizard authorization codes cannot refill after hide unload or a changed account context', async () => {
    for (const kind of ['hide', 'unload', 'context']) {
      const page = authorizationForm({ step: 3, authorizationChecked: true }); let pendingRequest;
      handler = (_route, options) => { pendingRequest = options; return null; };
      const pending = page.create(); await flush();
      if (kind === 'hide') page.onHide(); else if (kind === 'unload') page.onUnload(); else { app.globalData.contextVersion++; page.resetScopeData(); }
      pendingRequest.success(ok({ pickupCode: issuedCode })); await pending; assert.equal(page.data.issuedCode, ''); assert.notEqual(page.data.step, 4);
    }
  }],
  ['revocation cancellation sends nothing and a failed revoke cannot show a success screen', async () => {
    const row = Object.assign({}, pickup, { canRevoke: true }); const page = mount('safety', { teacher: false, pickups: [row] });
    page.openRevoke(event({ id: row.id })); page.cancelRevoke(); assert.equal(requests.length, 0);
    page.openRevoke(event({ id: row.id })); page.setData({ revokeReason: ' 接送安排有变 ' });
    handler = () => ({ statusCode: 409, data: { message: '授权已变化，请刷新' } }); await page.confirmRevoke();
    assert.equal(page.data.revokeSuccess, null); assert.equal(page.data.revokeRow.id, row.id);
    handler = () => ok({ status: 'REVOKED' }); await page.confirmRevoke(); assert.equal(page.data.revokeSuccess.id, row.id); assert.equal(page.data.revokeRow, null);
    assert.deepEqual(requests[1].body, { expectedVersion: 3, note: '接送安排有变' }); page.closeRevokeSuccess(); assert.equal(page.data.revokeSuccess, null);
  }],
  ['teacher pickup alerts contact the guardian and record hold or resolution without bypassing checkout', async () => {
    const alert = { id: 91, studentId: 101, version: 2, canHandle: true, parentPhone: '13800001234', status: 'OPEN' };
    const page = mount('safety', { studentId: 101, alerts: [alert], alertName: '虚构来接人', alertLast4: '', alertReason: '不在授权名单', alertNote: ' 已联系监护人，孩子留校等待 ' });
    handler = () => ok(alert); await page.reportAlert(); assert.equal(requests[0].route, '/pickup-alerts'); assert.equal(requests[0].body.phoneLast4, '');
    page.contactParent(event({ id: alert.id })); assert.deepEqual(phoneCalls, ['13800001234']);
    await page.handleAlert(event({ id: alert.id, action: 'HOLD' })); assert.equal(requests[1].body.action, 'HOLD'); assert.equal(requests[1].body.expectedVersion, 2);
    page.setData({ alerts: [Object.assign({}, alert, { version: 3 })] }); await page.handleAlert(event({ id: alert.id, action: 'RESOLVED' })); assert.equal(requests[2].body.expectedVersion, 3);
    page.setData({ teacher: false }); await page.reportAlert(); await page.handleAlert(event({ id: alert.id, action: 'HOLD' })); assert.equal(requests.length, 3);
    page.setData({ pickups: [Object.assign({}, pickup, { canCheckout: false })] }); await page.checkout(event({ id: pickup.id })); assert.equal(requests.length, 3, '异常未解除或未核验不能调用签退');
    page.setData({ teacher: true, alertNote: '' }); await page.handleAlert(event({ id: alert.id, action: 'RESOLVED' })); assert.equal(requests.length, 3, '缺少核对结果不能解除异常');
    await page.handleAlert(event({ id: alert.id, action: 'HOLD' })); assert.equal(requests.length, 4); assert.match(requests[3].body.note, /孩子继续留在校区/, '快捷暂不离校记录实际留校安排');
  }],
  ['workbench only publishes canPublish summaries and preserves approved snapshot content', async () => {
    const summaries = [
      { id: 11, studentId: 101, status: 'APPROVED', canPublish: true, teacherComment: '已审固定快照', openConcerns: [] },
      { id: 12, studentId: 102, status: 'DRAFT', canPublish: false, openConcerns: [] },
      { id: 13, studentId: 103, status: 'IN_REVIEW', canPublish: false, openConcerns: [] },
      { id: 14, studentId: 104, status: 'PUBLISHED', canPublish: false, openConcerns: [] }
    ];
    const page = desk({ summaryPreviews: structuredClone(summaries), summaries: structuredClone(summaries) });
    handler = () => ok({ results: [{ id: 11, success: true }] });
    await page.publish();
    assert.equal(requests.length, 1, '发布不能先调用编辑或重新生成接口');
    assert.equal(requests[0].route, '/summaries/batch-publish');
    assert.deepEqual(requests[0].body, { summaryIds: [11], confirmedConcernStudentIds: [] });
    assert.equal(page.data.summaries[0].teacherComment, '已审固定快照');
    const template = fs.readFileSync(path.join(root, 'pages/workbench/index.wxml'), 'utf8');
    assert.doesNotMatch(template, /bindinput="summaryComment"/, '工作台已审预览不能继续编辑老师点评');
  }],
  ['workbench concern checks gate publishing and mixed results preserve only failed students', async () => {
    const previews = [{ id: 11, studentId: 101, canPublish: true, openConcerns: [{ id: 1 }] }, { id: 12, studentId: 102, canPublish: true, openConcerns: [] }];
    const page = desk({ summaryPreviews: previews });
    await page.publish();
    assert.equal(requests.length, 0, '未勾选关注事项确认不得发布');
    page.setData({ confirmedIds: [101] });
    handler = () => ok({ results: [{ id: 11, success: true }, { id: 12, success: false, message: '审核版本已变更' }] });
    await page.publish();
    assert.deepEqual(requests[0].body.summaryIds, [11, 12]);
    assert.deepEqual(page.data.studentIds, [102], '失败学生保留选择，成功学生取消选择');
    assert.equal(page.data.results[1].success, false);
    assert.equal(page.data.results[1].message, '审核版本已变更');
    assert.ok(toasts.some(title => title.startsWith('部分记录未完成')));
    assert.ok(!toasts.some(title => /全部.*成功/.test(title)));
  }],
  ['workbench sends unapproved selections to the summary review page without publishing', async () => {
    const page = desk({ summaryPreviews: [{ id: 12, studentId: 101, status: 'DRAFT', canPublish: false, openConcerns: [] }] });
    await page.publish();
    assert.equal(requests.length, 0);
    assert.deepEqual(navigation, ['/pages/summaries/index?date=' + page.data.date]);
  }],
  ['historical checkout requires an administrator reason and sends the trimmed reason', async () => {
    for (const name of ['workbench', 'attendance']) {
      const page = name === 'workbench' ? desk() : mount(name, { studentIds: [101], students: [{ id: 101, name: '虚构学生甲' }] });
      const checkout = () => name === 'workbench' ? page.attendance(event({ out: '1' })) : page.batch(event({ action: 'out' }));
      page.setData({ date: '2000-01-01', admin: false, historicalReason: '核对纸质交接记录' });
      await checkout();
      assert.equal(requests.length, 0, '教师不得补录历史签退');
      page.setData({ admin: true, historicalReason: ' ' });
      await checkout();
      assert.equal(requests.length, 0, '历史补录缺少原因不得发送');
      page.setData({ historicalReason: ' 核对纸质交接记录 ' });
      handler = () => ok({ results: [{ studentId: 101, success: true }] });
      await checkout();
      assert.equal(requests.length, 1);
      assert.equal(requests[0].body.historicalReason, '核对纸质交接记录');
      requests = [];
    }
  }]
];

cases.push(
  ['photo attendance associates one selected student and reports bound-parent delivery without GPS', async () => {
    const choose = wx.chooseMedia, upload = wx.uploadFile, location = wx.getLocation;
    try {
      wx.getLocation = () => { throw new Error('学生拍照不应获取定位'); };
      wx.chooseMedia = options => { assert.deepEqual(options.sourceType, ['camera']); options.success({ tempFiles: [{ tempFilePath: '/tmp/fictional-arrival.png' }] }); };
      wx.uploadFile = options => options.success({ statusCode: 200, data: JSON.stringify({ id: 901, url: '/api/files/901?ticket=fixture' }) });
      handler = () => ok({ message: '签到成功', notificationCount: 1 });
      const page = mount('attendance', { records: [{ studentId: 101, studentName: '虚构学生', campusId: 1 }] });
      await page.photoMark(event({ id: 101, action: 'CHECK_IN' }));
      assert.equal(requests.length, 1); assert.equal(requests[0].route, '/attendance/photo'); assert.deepEqual(requests[0].body, { studentId: 101, photoFileId: 901, action: 'CHECK_IN', teacherPickup: true }); assert.equal(page.data.resultSuccess, 1);
    } finally { wx.chooseMedia = choose; wx.uploadFile = upload; wx.getLocation = location; }
  }],
  ['leaving during a camera upload cannot register attendance', async () => {
    const choose = wx.chooseMedia, upload = wx.uploadFile;
    try {
      const page = mount('attendance', { records: [{ studentId: 101, campusId: 1 }] });
      wx.chooseMedia = options => options.success({ tempFiles: [{ tempFilePath: '/tmp/fictional.png' }] });
      wx.uploadFile = options => { page.onUnload(); options.success({ statusCode: 200, data: JSON.stringify({ id: 901 }) }); };
      await page.photoMark(event({ id: 101, action: 'CHECK_OUT' })); assert.equal(requests.length, 0);
    } finally { wx.chooseMedia = choose; wx.uploadFile = upload; }
  }],
  ['manual capture directs to page scanning and saves the confirmed question with retry identity', async () => {
    const choose = wx.chooseMedia, upload = wx.uploadFile;
    try {
      wx.chooseMedia = options => options.success({ tempFiles: [{ tempFilePath: '/tmp/question.png' }] });
      wx.uploadFile = options => options.success({ statusCode: 200, data: JSON.stringify({ id: 902, url: '/api/files/902?ticket=fixture' }) });
      handler = () => ok({ status: 'NOT_CONFIGURED', text: '', message: '识别服务尚未配置，请手工校对' });
      const page = mount('capture', { students: [{ id: 101, campusId: 1 }], studentIds: [101] });
      await page.snap(); assert.match(page.data.note, /手工校对/); assert.equal(page.data.question, ''); page.setData({ question: '12 + 8 = ?' });
      handler = () => ({ statusCode: 503, data: { message: 'fixture retry' } }); await page.save(); const first = requests.at(-1).body.requestId; assert.equal(page.data.saved, false);
      handler = () => ok({ id: 1 }); await page.save(); const body = requests.at(-1).body; assert.equal(body.requestId, first); assert.equal(body.questionText, '12 + 8 = ?'); assert.equal(body.kind, 'MISTAKE'); assert.equal(body.photoFileId, 902); assert.equal(page.data.saved, true);
    } finally { wx.chooseMedia = choose; wx.uploadFile = upload; }
  }],
  ['teacher lesson grants use the chosen course and preserve request identity on retry', async () => {
    const page = mount('lessons', { studentIds: [101], courses: [{ id: 34, name: '虚构周末课' }], courseIndex: 1, grantUnits: '2.5', grantReason: '补充课时' });
    handler = () => ({ statusCode: 503, data: { message: 'fixture retry' } }); await page.grant(); const first = requests.at(-1).body.requestId;
    handler = () => ok({ id: 10 }); await page.grant(); const body = requests.at(-1).body; assert.equal(body.requestId, first); assert.equal(body.courseId, 34); assert.equal(body.units, 2.5); assert.equal(body.studentId, 101); assert.equal(body.reason, '补充课时');
  }],
  ['preset awards post rule identity instead of a client amount', async () => {
    const page = mount('rewards', { child: { id: 101, name: '虚构学生' }, presets: [{ id: 81, name: '主动思考', points: 3, experience: 12 }], presetIndex: 1 }); handler = () => ok({ id: 1 }); await page.award(); assert.equal(requests[0].route, '/point-awards'); assert.equal(requests[0].body.presetId, 81); assert.equal(requests[0].body.studentId, 101); assert.equal('amount' in requests[0].body, false); assert.equal('experience' in requests[0].body, false);
  }],
  ['prize redemption submits version and evidence, preserving both after failure', async () => {
    const page = mount('rewards', { child: { id: 101, name: '虚构学生' }, prizes: [{ id: 82, name: '文具', pointsCost: 3, version: 4, canRedeem: true, requiredLevel: 2 }], receipt: { id: 903 } });
    handler = () => ({ statusCode: 409, data: { message: '库存已变化' } }); await page.redeem(event({ id: 82 })); const first = requests[0].body; assert.equal(first.expectedVersion, 4); assert.deepEqual(first.attachmentIds, [903]); assert.equal(page.data.receipt.id, 903);
    handler = () => ok({ id: 2 }); await page.redeem(event({ id: 82 })); assert.equal(requests[1].body.requestId, first.requestId); assert.equal(page.data.receipt, null);
  }],
  ['locked gifts cannot open redemption or send a request despite sufficient local points', async () => {
    const page = mount('rewards', { child: { id: 101, points: 1000 }, prizes: [{ id: 82, pointsCost: 3, canRedeem: false, redemptionHint: 'Lv.3 解锁' }] });
    await page.redeem(event({ id: 82 })); assert.equal(requests.length, 0);
  }],
  ['reward editors submit independent experience and level requirements and reject invalid values', async () => {
    handler = () => ok({ id: 1 });
    const page = mount('rewards', { configCampusId: 1, presetName: '主动思考', presetPoints: '3', presetExperience: '12' });
    await page.savePreset(); assert.equal(requests[0].body.points, 3); assert.equal(requests[0].body.experience, 12);
    requests = []; page.data.presetExperience = ''; await page.savePreset(); assert.equal(requests.length, 0);
    page.data.presetExperience = '0'; await page.savePreset(); assert.equal(requests[0].body.experience, 0);
    requests = []; Object.assign(page.data, { prizeName: '礼品', pointsCost: '20', stock: '5', requiredLevel: '3' });
    await page.savePrize(); assert.equal(requests[0].body.requiredLevel, 3); assert.equal(requests[0].body.pointsCost, 20);
    for (const level of ['', '0', '2.5', '1001']) { requests = []; page.data.requiredLevel = level; await page.savePrize(); assert.equal(requests.length, 0); }
  }],
  ['only the super administrator submits automatic point and experience rules', async () => {
    handler = () => ok({});
    const page = mount('rewards', { superAdmin: false, automaticRules: [{ event: 'FITNESS', points: '5' }], learningExperience: '15', fitnessExperience: '8' });
    await page.saveRules(); assert.equal(requests.length, 0);
    page.data.superAdmin = true; await page.saveRules(); assert.deepEqual(requests[0].body, { pointsRules: [{ event: 'FITNESS', points: 5 }], experienceRules: [{ category: 'LEARNING', experience: 15 }, { category: 'FITNESS', experience: 8 }] });
    requests = []; page.data.fitnessExperience = ''; await page.saveRules(); assert.equal(requests.length, 0);
  }],
  ['print books use the visible selected questions and refresh private access before opening PDF', async () => {
    const download = wx.downloadFile, open = wx.openDocument; let downloaded, opened;
    try {
      const page = mount('mistakes', { studentIds: [101], items: [{ id: 7 }, { id: 8 }] }); handler = () => ok({ id: 904, url: '/api/files/904?ticket=stale' }); await page.printBook(); assert.deepEqual(requests[0].body, { studentId: 101, mistakeIds: [7, 8] });
      handler = () => ok({ id: 904, url: '/api/files/904?ticket=fresh' }); wx.downloadFile = options => { downloaded = options.url; options.success({ statusCode: 200, tempFilePath: '/tmp/book.pdf' }); }; wx.openDocument = options => { opened = options; options.success(); };
      await page.openBook(); assert.equal(requests.at(-1).route, '/files/904/access'); assert.ok(downloaded.includes('ticket=fresh')); assert.equal(opened.fileType, 'pdf'); assert.equal(opened.showMenu, true);
    } finally { wx.downloadFile = download; wx.openDocument = open; }
  }],
  ['notification acknowledgement marks only its selected message', async () => { const page = mount('notifications'); handler = () => ok({ success: true }); await page.read(event({ id: 121 })); assert.equal(requests.length, 1); assert.equal(requests[0].route, '/student-notifications/121/read'); }]
);

(async () => {
  let failures = 0;
  for (const [name, run] of cases) {
    reset();
    try { await run(); console.log('PASS ' + name); }
    catch (error) { failures++; console.error('FAIL ' + name + ': ' + String(error.message).split('\n')[0]); }
  }
  console.log(`${cases.length - failures}/${cases.length} passed; ${failures} failed (local wx fixtures; no server access)`);
  process.exitCode = failures ? 1 : 0;
})();
