const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

let definition;
const file = path.resolve(__dirname, '../components/select-picker/index.js');
vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component(value) { definition = value; } }, { filename: file });
function instance(props = {}) {
  return Object.assign({}, definition.methods, {
    data: { open: false, range: ['全部', '语文', '数学'], value: 0, disabled: false, ...props },
    events: [],
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { this.events.push({ name, detail }); }
  });
}
const tap = index => ({ currentTarget: { dataset: { index } } });
const picker = instance();
picker.choose(tap(1));
assert.equal(picker.events.length, 0, '关闭时不接收选择');
picker.open();
assert.equal(picker.data.open, true);
for (const value of [-1, 3, 1.5, 'bad', undefined]) picker.choose(tap(value));
assert.equal(picker.events.length, 0, '拒绝越界和无效索引');
picker.choose(tap('2'));
assert.equal(picker.events[0].name, 'change');
assert.equal(picker.events[0].detail.value, 2, '保留原生 picker 的 detail.value 索引契约');
assert.equal(picker.data.open, false);
picker.choose(tap(1));
assert.equal(picker.events.length, 1, '连点只发一次事件');
picker.open(); picker.choose(tap(0));
assert.equal(picker.events.length, 1, '相同值不重复筛选');
picker.open(); picker.close();
assert.equal(picker.events.length, 1, '取消不改变筛选');
picker.open();
picker.data.range = ['已更新的列表'];
definition.observers['range, rangeKey, disabled'].call(picker);
picker.choose(tap(0));
assert.equal(picker.events.length, 1, '范围变化关闭面板，旧选项不能被提交');
for (const props of [{ disabled: true }, { range: [] }]) {
  const unavailable = instance(props);
  unavailable.open();
  assert.equal(unavailable.data.open, false, '禁用或空列表不能打开');
  unavailable.data.open = true;
  unavailable.choose(tap(0));
  assert.equal(unavailable.events.length, 0);
}
picker.open(); definition.pageLifetimes.hide.call(picker);
assert.equal(picker.data.open, false, '页面隐藏关闭');
picker.open(); definition.lifetimes.detached.call(picker);
assert.equal(picker.data.open, false, '卸载关闭');
const objects = instance({ range: [{ id: 1, name: '样例甲' }, { id: 2, name: '样例乙' }], rangeKey: 'name' });
objects.open();
assert.equal(objects.data.items[1].label, '样例乙', '按 rangeKey 展示对象名称');
assert.equal(objects.data.items[1].index, 1, '列表使用稳定索引，允许不同选项同名');
objects.choose(tap(1));
assert.equal(objects.events[0].detail.value, 1, '对象列表仍返回索引而非业务 ID');
console.log('通过选择面板校验：索引与对象列表、取消、重复值与连点、无效索引、空列表、禁用、范围变化、页面隐藏和卸载。');
