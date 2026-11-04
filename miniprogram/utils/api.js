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
function request(method, path, data, anonymous, allCampuses) {
  const value = session.current();
  if (!anonymous && !value) return Promise.reject(new Error('请先登录'));
  const context = session.context();
  const suffix = method === 'GET' ? query(data) : '';
  return new Promise((resolve, reject) => wx.request({
    url: base() + path + (suffix ? '?' + suffix : ''), method,
    data: method === 'GET' ? undefined : data,
    timeout: config.requestTimeout,
    header: Object.assign({ 'content-type': 'application/json' }, !anonymous && value ? { Authorization: 'Bearer ' + value.token } : {}, !anonymous && !allCampuses ? session.campusHeader() : {}),
    success(response) {
      if (!anonymous && !session.isCurrentContext(context)) { reject(session.contextError()); return; }
      if (response.statusCode >= 200 && response.statusCode < 300) resolve(response.data);
      else reject(responseError(response, !anonymous));
    },
    fail(error) {
      if (!anonymous && !session.isCurrentContext(context)) { reject(session.contextError()); return; }
      const detail = error && typeof error.errMsg === 'string' ? error.errMsg
        .replace(/https?:\/\/[^\s"'<>)]+/gi, url => url.split(/[?#]/)[0]).slice(0, 300) : '';
      const errno = error && (error.errno === undefined ? error.errCode : error.errno);
      let hint = '请确认手机与电脑连接同一 Wi-Fi；iPhone 还需允许微信访问“本地网络”。';
      if (/domain|url not in|not in domain/i.test(detail)) hint = '微信拦截了请求域名，请核对真机调试的合法域名校验设置；正式环境使用已配置的 HTTPS 合法域名。';
      else if (/ssl|tls|certificate|cert_|clear.?text|app transport security|https (?:required|only)/i.test(detail)) hint = '安全连接检查未通过，请使用证书有效、已配置合法域名的 HTTPS 服务。';
      else if (/permission|denied|local.?network|not authorized/i.test(detail)) hint = 'iPhone 请到“设置 → 隐私与安全性 → 本地网络”允许微信访问，再重新进入小程序。';
      else if (/timeout|timed out|超时/i.test(detail)) hint = '请求超时，请确认手机与电脑连接同一 Wi-Fi，并检查此接口能否在手机浏览器打开。';
      const nativeCode = typeof errno === 'number' || typeof errno === 'string' ? '（错误码 ' + String(errno).slice(0, 32) + '）' : '';
      const failure = new Error('网络连接失败（' + base() + path.split(/[?#]/)[0] + '）：' + hint
        + '\n微信错误：' + (detail || '微信未提供具体原因') + nativeCode);
      failure.code = 'NETWORK_ERROR';
      failure.errMsg = detail;
      failure.errno = errno;
      reject(failure);
    }
  }));
}
async function login(data) {
  const { username, password, role } = data;
  const challenge = await request('GET', '/auth/challenge', { purpose: 'LOGIN' }, true);
  const payload = await require('./password').encryptPassword(challenge, username, password, 'LOGIN');
  if (role !== undefined) payload.role = role;
  return request('POST', '/auth/login', payload, true);
}
function upload(filePath, campusId) {
  const value = session.current();
  if (!value) return Promise.reject(new Error('请先登录'));
  const context = session.context();
  return new Promise((resolve, reject) => wx.uploadFile({
    url: base() + '/files', filePath, name: 'file', timeout: config.uploadTimeout,
    header: Object.assign({ Authorization: 'Bearer ' + value.token }, campusId ? { 'X-Campus-Id': String(campusId) } : session.campusHeader()),
    success(response) {
      if (!session.isCurrentContext(context)) { reject(session.contextError()); return; }
      let data;
      try { data = JSON.parse(response.data); } catch (error) { reject(new Error('文件服务返回了无效数据')); return; }
      if (response.statusCode >= 200 && response.statusCode < 300) resolve(data);
      else reject(responseError({ statusCode: response.statusCode, data }, true));
    },
    fail() { reject(session.isCurrentContext(context) ? new Error('文件上传失败，请检查网络及 uploadFile 合法域名') : session.contextError()); }
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
  login,
  health: () => request('GET', '/health', {}, true),
  me: () => request('GET', '/me', {}, false, true),
  campusReports: params => request('GET', '/campus-reports?' + query(params)),
  saveCampusReport: (campusId, date, data) => request('PUT', '/campus-reports/' + campusId + '?' + query({ date }), data),
  commentClassReport: (classId, date, data) => request('POST', '/class-reports/' + classId + '/comments?' + query({ date }), data),
  classReports: params => request('GET', '/class-reports?' + query(params)),
  saveClassReport: (classId, date, data) => request('PUT', '/class-reports/' + classId + '?' + query({ date }), data),
  campuses: () => request('GET', '/campuses', {}, false, true),
  users: () => request('GET', '/users', {}, false, true),
  updateAccess: (id, campusIds) => request('PUT', '/users/' + id + '/access', { campusIds }, false, true),
  settings: () => request('GET', '/settings'),
  dashboard: date => request('GET', '/dashboard', { date }),
  growth: params => request('GET', '/growth', params),
  principal: params => request('GET', '/principal-overview', params),
  workbench: params => request('GET', '/workbench', params),
  growthNote: data => request('POST', '/growth/notes', data),
  fitnessPause: data => request('POST', '/growth/fitness-pause', data),
  followUps: params => request('GET', '/follow-ups', params),
  followUp: id => request('GET', '/follow-ups/' + id),
  createFollowUp: data => request('POST', '/follow-ups', data),
  replyFollowUp: (id, data) => request('POST', '/follow-ups/' + id + '/reply', data),
  readFollowUp: (id, lastLogId) => request('POST', '/follow-ups/' + id + '/read', { lastLogId }),
  updateFollowUp: (id, data) => request('PUT', '/follow-ups/' + id + '/status', data),
  owners: studentId => request('GET', '/growth/owners', { studentId }),
  reassignFollowUp: (id, data) => request('PUT', '/follow-ups/' + id + '/owner', data),
  classes: () => request('GET', '/classes'),
  students: params => request('GET', '/students', params),
  student: id => request('GET', '/students/' + id),
  attendance: params => request('GET', '/attendance', params),
  teacherAttendance: date => request('GET', '/teacher-attendance', { date }),
  teacherCheckIn: (campusId, location) => request('POST', '/teacher-attendance/check-in', { campusId, location }),
  updateCampusLocation: (campusId, data) => request('PUT', '/campuses/' + campusId + '/check-in-location', data, false, true),
  checkIn: data => request('POST', '/attendance/check-in', data),
  checkOut: data => request('POST', '/attendance/check-out', data),
  homework: params => request('GET', '/homework', params),
  createHomework: data => request('POST', '/homework/tasks', data),
  homeworkDetail: id => request('GET', '/homework/' + id),
  homeworkStatus: (id, status, data) => request('POST', '/homework/' + id + '/status', Object.assign({ status }, data)),
  reviewHomework: (id, data) => request('POST', '/homework/' + id + '/review', data),
  batchReview: data => request('POST', '/homework/batch-review', data),
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
  publishSummary: (id, confirmedConcerns) => request('POST', '/summaries/' + id + '/publish', { confirmedConcerns: !!confirmedConcerns }),
  batchPublish: data => request('POST', '/summaries/batch-publish', data),
  feedbackSummary: (id, data) => request('POST', '/summaries/' + id + '/feedback', data)
};
