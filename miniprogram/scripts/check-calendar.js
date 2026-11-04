const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const calendar = require('../utils/calendar');
const { today } = require('../utils/format');

assert.equal(calendar.daysInMonth(2024, 2), 29, '2024 是闰年');
assert.equal(calendar.daysInMonth(2025, 2), 28, '2025 不是闰年');
assert.equal(calendar.daysInMonth(2000, 2), 29);
assert.equal(calendar.daysInMonth(1900, 2), 28);
assert.equal(calendar.formatDate(calendar.parseDate('2024-02-29')), '2024-02-29');
for (const value of ['', null, 'invalid', '2025-02-29', '2024-02-30', '2024-13-01', '2024-00-01', '2024-01-00', '2024-2-01', '0000-01-01']) {
  assert.equal(calendar.parseDate(value), null, '拒绝无效日期：' + value);
  assert.equal(calendar.normalizeDate(value, '2026-10-28'), '2026-10-28', '无效 value 回退今天');
}
assert.deepEqual(calendar.shiftMonth({ year: 2024, month: 12, day: 31 }, 1), { year: 2025, month: 1, day: 31 }, '12 月跨到次年 1 月');
assert.deepEqual(calendar.shiftMonth({ year: 2025, month: 1, day: 31 }, -1), { year: 2024, month: 12, day: 31 }, '1 月返回上一年 12 月');
assert.deepEqual(calendar.shiftMonth({ year: 2024, month: 1, day: 31 }, 1), { year: 2024, month: 2, day: 29 }, '闰年月末截到 29 日');
assert.deepEqual(calendar.shiftMonth({ year: 2025, month: 1, day: 31 }, 1), { year: 2025, month: 2, day: 28 }, '平年月末截到 28 日');
assert.deepEqual(calendar.shiftMonth({ year: 2025, month: 3, day: 31 }, -1), { year: 2025, month: 2, day: 28 });
assert.equal(calendar.describeDate('2024-01-01'), '2024年1月1日 周一');
const leapCells = calendar.buildMonth(2024, 2, '2024-02-29', '2024-02-29');
assert.equal(leapCells.length, 35, '闰年二月只展示实际需要的五周');
assert.equal(calendar.buildMonth(2021, 2, '', '').length, 28, '四周月份不追加两行空白');
assert.equal(calendar.buildMonth(2021, 5, '', '').length, 42, '六周月份保留全部日期');
assert.equal(leapCells[3].value, '2024-02-01', '每周从周一开始');
assert.equal(leapCells.filter(cell => cell.value).length, 29);
assert.equal(leapCells.filter(cell => cell.selected && cell.today).length, 1);
assert.equal(leapCells.find(cell => cell.value === '2024-02-03').weekend, true);
assert.equal(leapCells.find(cell => cell.value === '2024-02-04').weekend, true);
assert.equal(leapCells.find(cell => cell.value === '2024-02-05').weekend, false);
assert.equal(calendar.buildMonth(2025, 2, '', '').filter(cell => cell.value).length, 28);
for (const [year, month] of [[2021, 2], [2024, 2], [2021, 5]]) {
 const cells = calendar.buildMonth(year, month, '', '');
 assert.equal(cells.length % 7, 0, '日历保持完整周');
 assert.ok(cells.slice(-7).some(cell => cell.value), '不保留整行空白周');
}

assert.equal(calendar.buildYears(calendar.yearPageStart(1800), 1800).find(year => year.selected).value, 1800, '历史年份不受默认范围限制');
assert.equal(calendar.buildYears(calendar.yearPageStart(9999), 9999).at(-1).value, 9999);

let definition;
const timers = new Map();
let timerId = 0;
const componentFile = path.resolve(__dirname, '../components/date-picker/index.js');
vm.runInNewContext(fs.readFileSync(componentFile, 'utf8'), {
  Component(value) { definition = value; },
  require: createRequire(componentFile),
  setTimeout(callback) { const id = ++timerId; timers.set(id, callback); return id; },
  clearTimeout(id) { timers.delete(id); }
}, { filename: componentFile });
function instance(value) {
  return Object.assign({}, definition.methods, {
    data: Object.assign({ value, label: '选择测试日期' }, JSON.parse(JSON.stringify(definition.data))),
    events: [],
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { this.events.push({ name, detail }); }
  });
}
function tap(field, value) { return { currentTarget: { dataset: { [field]: value } } }; }
function finishAnimation() {
  for (const [id, callback] of Array.from(timers)) { timers.delete(id); callback(); }
}

const realNow = Date.now;
const initialTimezone = process.env.TZ;
try {
  Date.now = () => Date.UTC(2026, 9, 27, 17, 30);
  for (const timezone of ['Asia/Shanghai', 'America/Los_Angeles', 'Pacific/Kiritimati']) {
    process.env.TZ = timezone;
    assert.equal(today(), '2026-10-28', '复用上海业务日期，设备时区不影响今天');
    assert.equal(calendar.buildMonth(2024, 2, '', '')[3].value, '2024-02-01', '日历无 UTC 日期解析偏移');
    assert.equal(calendar.formatDate(calendar.shiftMonth(calendar.parseDate('2024-12-31'), 1)), '2025-01-31');
  }
  const shortMonth = instance('2021-02-01'), longMonth = instance('2021-05-01');
  shortMonth.open(); longMonth.open(); assert.ok(shortMonth.data.bodyHeight < longMonth.data.bodyHeight, '日历面板随实际周数收缩');
  const picker = instance('2024-12-31');
  picker.open();
  assert.equal(picker.data.draft, '2024-12-31');
  picker.next();
  assert.equal(picker.data.viewYear, 2025);
  assert.equal(picker.data.viewMonth, 1);
  assert.equal(picker.data.draft, '2024-12-31', '浏览月份不更改待确认日期');
  picker.previous();
  assert.equal(picker.data.viewYear, 2024);
  assert.equal(picker.data.viewMonth, 12);
  picker.showYears();
  const firstYearStart = picker.data.yearStart;
  picker.previous();
  assert.equal(picker.data.yearStart, firstYearStart - 12);
  picker.next();
  assert.equal(picker.data.yearStart, firstYearStart);
  picker.chooseYear(tap('year', 2025));
  assert.equal(picker.data.mode, 'months');
  picker.chooseMonth(tap('month', 2));
  assert.equal(picker.data.mode, 'days');
  assert.equal(picker.data.cursorDay, 28, '年月网格切换处理月末');
  assert.equal(picker.events.length, 0, '浏览年月不会发筛选事件');
  picker.chooseDay(tap('value', '2025-02-29'));
  assert.equal(picker.data.draft, '2024-12-31', '无效日期不能选中');
  picker.chooseDay(tap('value', '2025-03-01'));
  assert.equal(picker.data.draft, '2024-12-31', '仅接收当前日历月份的日期');
  picker.chooseDay(tap('value', '2025-02-09'));
  assert.equal(picker.data.draft, '2025-02-09');
  picker.close();
  picker.confirm();
  assert.equal(picker.events.length, 0, '取消和关闭动画期间不发 change');
  assert.equal(picker.data.value, '2024-12-31');
  finishAnimation();
  assert.equal(picker.data.open, false);
  picker.open();
  assert.equal(picker.data.draft, '2024-12-31', '取消后重新打开恢复页面原日期');
  picker.confirm();
  assert.equal(picker.events.length, 0, '相同日期确认不重复筛选');
  finishAnimation();
  picker.open();
  picker.showYears();
  picker.chooseYear(tap('year', 2024));
  picker.chooseMonth(tap('month', 2));
  picker.chooseDay(tap('value', '2024-02-09'));
  picker.confirm();
  picker.confirm();
  assert.equal(picker.events.length, 1, '连续确认仅发送一次事件');
  assert.equal(picker.events[0].name, 'change');
  assert.equal(picker.events[0].detail.value, '2024-02-09', '事件格式为补零 YYYY-MM-DD');
  assert.equal(Object.keys(picker.events[0].detail).join(','), 'value');
  finishAnimation();

  const invalid = instance('2025-02-29');
  invalid.open();
  assert.equal(invalid.data.draft, '2026-10-28', '无效 value 打开回退今天');
  assert.equal(invalid.data.cells.find(cell => cell.today).value, today());
  invalid.chooseDay(tap('value', '2026-10-01'));
  invalid.goToday();
  assert.equal(invalid.data.draft, today());
  assert.equal(invalid.events.length, 0, '今天按钮同样需要确认');
  invalid.chooseDay(tap('value', '2026-10-01'));
  definition.pageLifetimes.hide.call(invalid);
  assert.equal(invalid.data.open, false, '页面隐藏立即关闭');
  assert.equal(invalid.events.length, 0, '页面隐藏不确认临时选择');
  invalid.open();
  invalid.close();
  definition.lifetimes.detached.call(invalid);
  assert.equal(invalid.data.open, false, '组件卸载关闭');
  assert.equal(timers.size, 0, '组件卸载清理动画定时器');

  const historical = instance('1800-01-01');
  historical.open();
  assert.equal(historical.data.viewYear, 1800);
  historical.showYears();
  assert.ok(historical.data.years.some(year => year.value === 1800));
  historical.dismissImmediately();
  const edge = instance('9999-12-31');
  edge.open();
  edge.next();
  assert.equal(edge.data.viewYear, 9999);
  assert.equal(edge.data.viewMonth, 12);
  edge.showYears();
  assert.equal(edge.data.nextDisabled, true);
  edge.dismissImmediately();
} finally {
  Date.now = realNow;
  if (initialTimezone === undefined) delete process.env.TZ;
  else process.env.TZ = initialTimezone;
}
console.log('通过日历校验：闰年和平年、跨年、月末、周一开周、周末和今天、任意有效历史年份、年月网格、无效值回退、取消无事件、确认单次补零事件、上海业务日期与多时区一致、页面隐藏和卸载关闭。');
