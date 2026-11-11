// Real component/page handlers with wx fixtures; no server or business data writes.
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const app = { globalData: { session: { token: 'daily-fixture', user: { id: 7, role: 'PARENT' } }, contextVersion: 0, selectedCampusId: 1, selectedStudentIds: [] } };
let component, pageDefinition, requests = [], drawings = [], pending;
const points = [
  { date: '2026-11-01', accuracy: 80, errorRate: 20 },
  { date: '2026-11-02', accuracy: null, errorRate: null },
  { date: '2026-11-03', accuracy: 60, errorRate: 40 },
  { date: '2026-11-04', accuracy: 0, errorRate: 100 }
];
global.getApp = () => app;
global.Component = value => { component = value; };
global.Page = value => { pageDefinition = value; };
global.wx = {
  getStorageSync() {}, showToast() {}, stopPullDownRefresh() {},
  request(options) {
    const url = new URL(options.url), route = url.pathname.replace(/^\/api/, '');
    requests.push({ route, query: url.searchParams, body: options.data, method: options.method });
    if (pending) { pending(options); return; }
    let data = [];
    if (route === '/homework-trend') data = { points };
    if (route === '/students') data = [{ id: 101, name: '虚构孩子', classId: 2, campusId: 1 }];
    if (route === '/daily-feedback') data = [{ studentId: 101, feedbackSatisfaction: 'SATISFIED', feedbackSubmitted: false, canFeedback: true }];
    if (route === '/class-satisfaction') data = [{ classId: 2, parentCount: 2, submittedCount: 1, defaultCount: 1, satisfactionPercent: 50 }];
    if (route === '/fitness/batch') data = { recordedCount: 1 };
    options.success({ statusCode: 200, data });
  },
  createSelectorQuery() { return { in() { return this; }, select() { return this; }, boundingClientRect(callback) { callback({ width: 320 }); return this; }, exec() {} }; },
  createCanvasContext() {
    let color = '';
    const ctx = {};
    for (const method of ['clearRect', 'setFontSize', 'setLineWidth', 'beginPath', 'stroke', 'setFillStyle', 'fillText', 'arc', 'fill', 'setTextAlign', 'draw']) ctx[method] = () => {};
    ctx.setStrokeStyle = value => { color = value; };
    for (const method of ['moveTo', 'lineTo']) ctx[method] = (x, y) => drawings.push({ color, method, x, y });
    return ctx;
  }
};
require(path.join(root, 'components/homework-trend/index.js'));
const definitions = {};
for (const name of ['summaries', 'fitness']) { require(path.join(root, 'pages', name, 'index.js')); definitions[name] = pageDefinition; }
const format = require(path.join(root, 'utils/format'));
function mount(definition, patch = {}, methods = definition) {
  return Object.assign({}, definition, methods, { data: { ...structuredClone(definition.data), ...patch }, query: {}, setData(value, done) { Object.assign(this.data, value); if (done) done(); }, async refresh() {} });
}
async function main() {
  const chart = mount(component, { studentId: 101, endDate: format.today() }, component.methods);
  chart.refresh = component.methods.refresh;
  await chart.refresh();
  assert.equal(requests[0].query.get('days'), '7'); assert.equal(chart.data.hasData, true);
  const correct = drawings.filter(item => item.color === '#0071e3');
  assert.deepEqual(correct.map(item => item.method), ['moveTo', 'moveTo', 'lineTo']);
  assert.equal(correct.at(-1).y, 160, 'zero accuracy must be drawn, not treated as missing');
  chart.period({ currentTarget: { dataset: { days: 30 } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.at(-1).query.get('days'), '30');
  let stale;
  pending = options => { stale = options; };
  const waiting = chart.refresh();
  app.globalData.contextVersion++;
  stale.success({ statusCode: 200, data: { points } });
  await waiting; assert.deepEqual(chart.data.points, [], 'old account response cannot populate the chart');
  pending = null;
  chart.data.studentId = 102; await chart.refresh();
  assert.equal(requests.at(-1).query.get('studentId'), '102');
  component.lifetimes.detached.call(chart); const count = requests.length; await chart.refresh(); assert.equal(requests.length, count);
  requests = [];
  const parent = mount(definitions.summaries, { teacher: false, admin: false, studentIds: [], date: format.today() });
  const parentData = await definitions.summaries.loadData.call(parent);
  assert.deepEqual(parentData.items, []); assert.equal(parentData.dailyFeedback.length, 1, 'rating remains available before a summary is published');
  assert.ok(!requests.some(item => item.route === '/class-satisfaction'));
  requests = [];
  app.globalData.session.user.role = 'TEACHER';
  const teacher = mount(definitions.summaries, { teacher: true, admin: false, studentIds: [], date: format.today() });
  const teacherData = await definitions.summaries.loadData.call(teacher);
  assert.deepEqual(teacherData.dailyFeedback, []); assert.deepEqual(teacherData.satisfactionStats, []);
  assert.ok(!requests.some(item => ['/daily-feedback', '/class-satisfaction'].includes(item.route)));
  const exercise = mount(definitions.fitness, { studentIds: [101], project: '自选篮球训练', count: '', duration: '120', date: format.today() });
  requests = []; await exercise.submit();
  assert.equal(requests[0].body.project, '自选篮球训练'); assert.equal(requests[0].body.count, 0); assert.equal(requests[0].body.duration, 120);
  exercise.data.count = ''; exercise.data.duration = ''; requests = []; await exercise.submit(); assert.equal(requests.length, 0);
  console.log('PASS：7/30天切换、日期断点与零值绘图、账号切换与卸载隔离、无小结仍可评价、教师无评价请求、自定义运动与单项输入。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
