const api = require('./api');
const labels = { OPEN: '待审核', APPROVED: '已审核，待处理', REJECTED: '未通过', COMPLETED: '已完成', GRANTED: '已同意', WITHDRAWN: '已撤回', UNSET: '尚未选择' };
const request = (method, path, body) => api.request(method, '/privacy' + path, body, false, true);
module.exports = {
  caption: value => labels[value] || value,
  policy: () => request('GET', '/policy'),
  consents: studentId => request('GET', '/consents', { studentId }),
  consent: body => request('POST', '/consents', body),
  requests: () => request('GET', '/requests'),
  createRequest: body => request('POST', '/requests', body),
  review: (id, body) => request('POST', '/requests/' + id + '/review', body),
  export: id => request('POST', '/requests/' + id + '/export', {}),
  checkErasure: id => request('GET', '/requests/' + id + '/erasure-check'),
  erase: (id, body) => request('POST', '/requests/' + id + '/erase', body)
};
