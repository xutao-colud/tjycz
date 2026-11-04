const api = require('./api');
const format = require('./format');
const labels = { REQUESTED: '待审核', APPROVED: '待线下退款', REJECTED: '已驳回', CANCELLED: '已取消', CONFIRMED: '已退款' };
const tones = { REQUESTED: 'orange', APPROVED: 'blue', REJECTED: 'red', CANCELLED: 'purple', CONFIRMED: 'green' };
function cents(value) {
  const text = String(value).trim();
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) throw new Error('退款金额须为非负金额，最多两位小数');
  const parts = text.split('.');
  const amount = Number(parts[0]) * 100 + Number((parts[1] || '').padEnd(2, '0'));
  if (!Number.isSafeInteger(amount)) throw new Error('退款金额超出允许范围');
  return amount;
}
function credits(value) {
  const text = String(value).trim() || '0';
  if (!/^\d+(?:\.\d{1,2})?$/.test(text) || Number(text) > 999999999) throw new Error('退回课时须为非负数，最多两位小数');
  return Number(text);
}
function decorate(row) {
  return Object.assign({}, row, {
    statusText: labels[row.status] || row.status, tone: tones[row.status] || 'purple', moneyText: format.amount(row.amount), createdText: format.time(row.createdAt),
    confirmedText: format.time(row.confirmedAt),
    logs: (row.logs || []).map(log => Object.assign({}, log, { statusText: labels[log.toStatus] || log.toStatus, createdText: format.time(log.createdAt) }))
  });
}
module.exports = {
  cents, credits, decorate,
  list: params => api.request('GET', '/refunds', params),
  sources: params => api.request('GET', '/refunds/sources', params),
  detail: id => api.request('GET', '/refunds/' + id),
  create: body => api.request('POST', '/refunds', body),
  review: (id, body) => api.request('POST', '/refunds/' + id + '/review', body),
  cancel: (id, body) => api.request('POST', '/refunds/' + id + '/cancel', body),
  confirm: (id, body) => api.request('POST', '/refunds/' + id + '/confirm', body)
};
