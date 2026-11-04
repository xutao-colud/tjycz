const api = require('./api');
const format = require('./format');
function decorate(row) {
  return Object.assign({}, row, { statusText: format.label(row.effectiveStatus || row.status), fromText: format.time(row.validFrom), untilText: format.time(row.expiresAt), logs: (row.logs || []).map(log => Object.assign({}, log, { createdText: format.time(log.createdAt) })) });
}
module.exports = {
  decorate,
  leaves: params => api.request('GET', '/leaves', params),
  pickups: params => api.request('GET', '/pickups', params),
  createLeave: body => api.request('POST', '/leaves', body),
  createPickup: body => api.request('POST', '/pickups', body),
  alerts: params => api.request('GET', '/pickup-alerts', params),
  createAlert: body => api.request('POST', '/pickup-alerts', body),
  handleAlert: (id, body) => api.request('POST', '/pickup-alerts/' + id + '/handle', body),
  action: (kind, id, action, body) => api.request('POST', '/' + kind + '/' + id + '/' + action, body)
};
