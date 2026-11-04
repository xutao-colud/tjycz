const format = require('./format');
const session = require('./session');
const states = { OPEN: '待跟进', IN_PROGRESS: '处理中', RESOLVED: '已解决', CANCELLED: '已撤销', PAUSED: '因故暂停', RECORDED: '已记录', PRESENT: '正常', LEFT: '正常', PUBLISHED: '已发布' };
const actions = { CREATE: '登记', STATUS: '状态更新', REASSIGN: '调整负责人', ACK: '已了解', REPLY: '回复', PARENT_DONE: '家长已配合', PENDING_REVIEW: '提交批改', PENDING_RECHECK: '提交订正', CORRECTION: '退回订正', COMPLETED: '批改完成', IN_PROGRESS: '开始辅导' };
function status(value) { return states[value] || format.label(value); }
function clock(value) { return value ? String(value).replace('T', ' ').slice(11, 16) : '当日记录'; }
function time(value) { return value ? format.time(value) : '具体时间未记录'; }
function tone(value) { return ['CORRECTION', 'OPEN'].includes(value) ? 'red' : ['COMPLETED', 'RESOLVED', 'PRESENT', 'LEFT'].includes(value) ? 'green' : value === 'PAUSED' ? 'orange' : 'blue'; }
function decorate(row) { return Object.assign({}, row, { statusText: row.status === 'IN_PROGRESS' && !row.kind ? '进行中' : status(row.status), tone: tone(row.status), timeText: clock(row.occurredAt), fullTime: time(row.occurredAt || row.createdAt), actionText: actions[row.action] || status(row.status) }); }
function dates(day) {
  const at = Date.parse(day + 'T00:00:00Z');
  return [-2, -1, 0, 1, 2].map(offset => {
    const d = new Date(at + offset * 86400000), value = d.toISOString().slice(0, 10);
    return { value, label: value === format.today() ? '今天' : ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getUTCDay()], short: value.slice(5).replace('-', '/'), selected: value === day };
  });
}
function request(page, action, body) {
  const scope = JSON.stringify([session.context(), page.data.date, page.data.studentId, page.data.classId, action, body]);
  page._growthRequests = page._growthRequests || {};
  if (!page._growthRequests[action] || page._growthRequests[action].scope !== scope) page._growthRequests[action] = { scope, body: Object.assign({}, body, { requestId: Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }) };
  return page._growthRequests[action].body;
}
function draftKey(scope) {
  const value = session.current();
  return 'wanfu.growth.draft:' + JSON.stringify([value && value.user.id, value && value.user.role, session.accessKey(value && value.user), session.context().campusId, scope]);
}
function draft(scope, body) {
  const key = draftKey(scope);
  try {
    if (body === null) { wx.removeStorageSync(key); return {}; }
    if (body !== undefined) { wx.setStorageSync(key, body); return body; }
    return wx.getStorageSync(key) || {};
  } catch (e) { return {}; }
}
function primary(homework) {
  if (!homework.length) return '无作业';
  for (const state of ['CORRECTION', 'PENDING_RECHECK', 'PENDING_REVIEW', 'IN_PROGRESS', 'NOT_STARTED']) if (homework.some(h => h.status === state)) return format.label(state);
  return '全部完成';
}
module.exports = { status, clock, time, tone, decorate, dates, request, draft, primary };
