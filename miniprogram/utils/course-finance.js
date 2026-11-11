function money(value) {
  const text = String(value).trim();
  if (!/^\d+(\.\d{1,2})?$/.test(text)) throw new Error('金额须为非负数，最多两位小数');
  const [whole, fraction = ''] = text.split('.');
  const amount = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(amount)) throw new Error('金额超出允许范围');
  return amount;
}
function units(value, allowZero = false) {
  const n = money(value) / 100;
  if ((!allowZero && n <= 0) || n > 10000) throw new Error('课时须大于0且不超过10000，最多两位小数');
  return n;
}
function courseLabel(row) { return (row.parentCourseName ? row.parentCourseName + ' / ' : '') + (row.courseName || row.name); }
function groups(accounts) {
  const result = new Map();
  for (const account of accounts) {
    const id = account.parentCourseId || account.courseId;
    if (!result.has(id)) result.set(id, { id, name: account.parentCourseName || account.courseName, count: 0, balances: new Map() });
    const group = result.get(id), value = Number(account.availableBalance === undefined ? account.balance : account.availableBalance);
    group.count++;
    group.balances.set(account.unit, (group.balances.get(account.unit) || 0) + Math.round(value * 100));
  }
  return Array.from(result.values()).map(g => ({ id: g.id, name: g.name, count: g.count, balanceText: Array.from(g.balances, ([unit, value]) => value / 100 + ' ' + unit).join(' / ') }));
}
function priceTotal(price, count) {
  units(count);
  const product = price * money(count);
  if (!Number.isSafeInteger(price) || price < 0 || !Number.isSafeInteger(product)) throw new Error('课程价格或金额超出允许范围');
  return Math.round(product / 100);
}
module.exports = { money, units, courseLabel, groups, priceTotal };
