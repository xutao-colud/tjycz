const config = require('./config');
const session = require('./session');
let redirecting = false;
function base() { return config.apiBase.replace(/\/$/, ''); }
function query(params) {
  return Object.keys(params || {}).filter(key => params[key] !== '' && params[key] !== undefined && params[key] !== null)
    .map(key => encodeURIComponent(key) + '=' + encodeURIComponent(params[key])).join('&');
}
function expire() {
  getApp().clearSession();
  if (redirecting) return;
  redirecting = true;
  wx.reLaunch({ url: '/pages/login/index', complete: () => { redirecting = false; } });
}
function responseError(response, authenticated) {
  const body = response.data || {};
  if (response.statusCode === 401 && authenticated) expire();
  const error = new Error(body.message || (response.statusCode === 403 ? '无权访问此学生或执行此操作' : '服务暂时不可用（' + response.statusCode + '）'));
  error.code = body.code;
  error.statusCode = response.statusCode;
  return error;
}
function request(method, path, data, anonymous) {
  const value = session.current();
  if (!anonymous && !value) return Promise.reject(new Error('请先登录'));
  const suffix = method === 'GET' ? query(data) : '';
  return new Promise((resolve, reject) => wx.request({
    url: base() + path + (suffix ? '?' + suffix : ''), method,
    data: method === 'GET' ? undefined : data,
    timeout: config.requestTimeout,
    header: Object.assign({ 'content-type': 'application/json' }, !anonymous && value ? { Authorization: 'Bearer ' + value.token } : {}),
    success(response) {
      if (response.statusCode >= 200 && response.statusCode < 300) resolve(response.data);
      else reject(responseError(response, !anonymous));
    },
    fail() { reject(new Error('网络连接失败，请检查服务地址、后端启动状态及微信合法域名配置')); }
  }));
}
function upload(filePath) {
  const value = session.current();
  if (!value) return Promise.reject(new Error('请先登录'));
  return new Promise((resolve, reject) => wx.uploadFile({
    url: base() + '/files', filePath, name: 'file', timeout: config.uploadTimeout,
    header: { Authorization: 'Bearer ' + value.token },
    success(response) {
      let data;
      try { data = JSON.parse(response.data); } catch (error) { reject(new Error('文件服务返回了无效数据')); return; }
      if (response.statusCode >= 200 && response.statusCode < 300) resolve(data);
      else reject(responseError({ statusCode: response.statusCode, data }, true));
    },
    fail() { reject(new Error('文件上传失败，请检查网络及 uploadFile 合法域名')); }
  }));
}
function fileUrl(file) {
  const url = file.url || '';
  if (/^https?:\/\//i.test(url)) return url;
  if (url.startsWith('/api/')) return base().replace(/\/api$/, '') + url;
  if (url.startsWith('/files/')) return base() + url;
  return base() + '/' + url.replace(/^\//, '');
}
module.exports = {
  request, upload, fileUrl,
  demo: role => request('POST', '/auth/demo', { role }, true),
  login: data => request('POST', '/auth/login', data, true),
  health: () => request('GET', '/health', {}, true),
  me: () => request('GET', '/me'),
  settings: () => request('GET', '/settings'),
  dashboard: date => request('GET', '/dashboard', { date }),
  classes: () => request('GET', '/classes'),
  students: params => request('GET', '/students', params),
  student: id => request('GET', '/students/' + id),
  attendance: params => request('GET', '/attendance', params),
  checkIn: data => request('POST', '/attendance/check-in', data),
  checkOut: data => request('POST', '/attendance/check-out', data),
  homework: params => request('GET', '/homework', params),
  createHomework: data => request('POST', '/homework/tasks', data),
  homeworkStatus: (id, status) => request('POST', '/homework/' + id + '/status', { status }),
  reviewHomework: (id, data) => request('POST', '/homework/' + id + '/review', data),
  homeworkAttachments: (id, fileIds) => request('POST', '/homework/' + id + '/attachments', { fileIds }),
  mistakes: params => request('GET', '/mistakes', params),
  createMistake: data => request('POST', '/mistakes', data),
  correctMistake: (id, data) => request('POST', '/mistakes/' + id + '/correct', data),
  reviewMistake: (id, data) => request('POST', '/mistakes/' + id + '/review', data),
  completeReview: id => request('POST', '/mistakes/reviews/' + id + '/complete'),
  fitness: params => request('GET', '/fitness', params),
  fitnessBatch: data => request('POST', '/fitness/batch', data),
  points: params => request('GET', '/points', params),
  adjustPoints: data => request('POST', '/points', data),
  courses: () => request('GET', '/courses'),
  accounts: params => request('GET', '/accounts', params),
  ledger: params => request('GET', '/lesson-ledger', params),
  renewals: params => request('GET', '/renewals', params),
  settlements: () => request('GET', '/settlements'),
  retrySettlement: id => request('POST', '/settlements/' + id + '/retry'),
  summaries: params => request('GET', '/summaries', params),
  generateSummaries: data => request('POST', '/summaries/generate', data),
  editSummary: (id, teacherComment) => request('PATCH', '/summaries/' + id, { teacherComment }),
  publishSummary: id => request('POST', '/summaries/' + id + '/publish'),
  feedbackSummary: (id, data) => request('POST', '/summaries/' + id + '/feedback', data)
};
