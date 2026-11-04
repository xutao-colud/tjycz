const api = require('./api');
const format = require('./format');
const states = { ACTIVE: '有效', UPCOMING: '未生效', NOT_YET_VALID: '未生效', OUTSIDE_WINDOW: '非接送时段', EXPIRED: '已失效', INVALIDATED: '已失效', USED: '已使用', USED_TODAY: '今日已接送', REVOKED: '已撤销' };
function contact(row) {
  return Object.assign({}, row, { hasAvatar: !!row.avatar, avatarUrl: row.avatar ? api.fileUrl(row.avatar) : '/assets/icons/user.png', displayPhone: row.phoneMasked || (row.phone ? '•••• ' + String(row.phone).slice(-4) : '电话待确认') });
}
function decorate(row) {
  const effective = row.effectiveStatus || row.status;
  return Object.assign({}, row, {
    statusText: states[effective] || format.label(effective),
    modeText: row.mode === 'MULTI' ? '多次接送' : '单次接送',
    datesText: row.startDate ? row.startDate + (row.endDate && row.endDate !== row.startDate ? ' 至 ' + row.endDate : '') : String(row.validFrom || '').slice(0, 10),
    windowText: row.startTime && row.endTime ? String(row.startTime).slice(0, 5) + ' – ' + String(row.endTime).slice(0, 5) : String(row.validFrom || '').slice(11, 16) + ' – ' + String(row.expiresAt || '').slice(11, 16),
    isHistory: ['EXPIRED', 'INVALIDATED', 'USED', 'REVOKED'].includes(effective) || ['USED', 'REVOKED'].includes(row.status),
    tone: ['REVOKED', 'EXPIRED', 'INVALIDATED', 'USED'].includes(effective) ? 'dim' : effective === 'ACTIVE' ? 'green' : 'warn',
    verificationText: '接送码与老师现场核对',
    logs: (row.logs || []).map(log => Object.assign({}, log, { createdText: format.time(log.createdAt) }))
  });
}
function validate(body) {
  if (!body.studentId) return '请先选择一位孩子';
  if (!body.contactId && (!body.pickupName || !body.relationship || !body.phone)) return '请完整填写接送人信息';
  if (!body.contactId && !/^[0-9]{6,20}$/.test(body.phone)) return '请填写6至20位有效电话号码';
  if (!body.startDate || !body.endDate || body.endDate < body.startDate) return '请核对授权起止日期';
  if (body.mode === 'SINGLE' && body.startDate !== body.endDate) return '单次接送须选择同一天';
  if (!body.startTime || !body.endTime || body.endTime <= body.startTime) return '结束时间须晚于开始时间，暂不支持跨午夜';
  if ((body.note || '').length > 1000) return '备注最多1000字';
  return '';
}
module.exports = {
  contact, decorate, validate,
  contacts: studentId => api.request('GET', '/pickup-contacts', { studentId }),
  createContact: body => api.request('POST', '/pickup-contacts', body),
  editContact: (id, body) => api.request('PATCH', '/pickup-contacts/' + id, body),
  deleteContact: (id, body) => api.request('DELETE', '/pickup-contacts/' + id, body),
  setDefault: (id, version) => api.request('POST', '/pickup-contacts/' + id + '/default', { expectedVersion: version })
};
