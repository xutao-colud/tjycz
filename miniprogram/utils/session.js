const KEY = 'wanfu.session.v1';
const ROLES = ['SUPER_ADMIN', 'ADMIN', 'TEACHER', 'PARENT'];
function valid(value) { return !!(value && value.token && value.user && ROLES.includes(value.user.role)); }
function restore() {
  try {
    const value = wx.getStorageSync(KEY);
    return valid(value) ? value : null;
  } catch (error) { return null; }
}
function save(value) { wx.setStorageSync(KEY, value); }
function clear() { wx.removeStorageSync(KEY); }
function current() { return getApp().globalData.session; }
function isTeachingRole(role) { return ['SUPER_ADMIN', 'ADMIN', 'TEACHER'].includes(role); }
function isTeacher() {
  const value = current();
  return valid(value) && isTeachingRole(value.user.role);
}
function isAdmin() {
  const value = current();
  return valid(value) && ['SUPER_ADMIN', 'ADMIN'].includes(value.user.role);
}
function homePage() { const value = current(); return value && value.user.role === 'PARENT' ? 'growth' : value && value.user.role === 'TEACHER' ? 'workbench' : value && value.user.role === 'ADMIN' ? 'principal' : 'dashboard'; }
function accessibleCampuses(user) {
  const value = user || (current() || {}).user || {};
  const ids = Array.isArray(value.campusIds) ? value.campusIds.map(Number).filter(id => Number.isSafeInteger(id) && id > 0) : null;
  const rows = new Map();
  (value.campuses || []).forEach(campus => {
    const id = Number(campus.id);
    if (Number.isSafeInteger(id) && id > 0 && (value.role === 'SUPER_ADMIN' || !ids || ids.includes(id))) rows.set(id, { id, name: campus.name || '校区 ' + id });
  });
  (ids || []).forEach(id => { if (!rows.has(id)) rows.set(id, { id, name: '校区 ' + id }); });
  return Array.from(rows.values());
}
function accessKey(user) {
  const value = user || {};
  return JSON.stringify([value.role, ...['campusIds', 'classIds', 'studentIds'].map(key => Array.isArray(value[key]) ? value[key].map(Number).sort((a, b) => a - b) : null)]);
}
function context() {
  const data = getApp().globalData;
  return { version: data.contextVersion || 0, token: (data.session || {}).token, campusId: data.selectedCampusId || null };
}
function isCurrentContext(value) {
  const active = context();
  return value.version === active.version && value.token === active.token && value.campusId === active.campusId;
}
function contextError() {
  const error = new Error('校区或账号范围已变更，请重新加载');
  error.code = 'CONTEXT_CHANGED';
  return error;
}
function campusHeader() {
  const id = getApp().globalData.selectedCampusId;
  return id ? { 'X-Campus-Id': String(id) } : {};
}
function requireSession(teacherOnly, adminOnly) {
  if (!valid(current())) {
    wx.reLaunch({ url: '/pages/login/index' });
    return false;
  }
  if (teacherOnly && !isTeacher()) {
    wx.showToast({ title: '此操作仅教师或管理员可用', icon: 'none' });
    wx.reLaunch({ url: '/pages/dashboard/index' });
    return false;
  }
  if (adminOnly && !isAdmin()) {
    wx.showToast({ title: '此页面仅校长或老板可用', icon: 'none' });
    wx.reLaunch({ url: '/pages/dashboard/index' });
    return false;
  }
  return true;
}
module.exports = { valid, restore, save, clear, current, homePage, isTeachingRole, isTeacher, isAdmin, accessibleCampuses, accessKey, context, isCurrentContext, contextError, campusHeader, requireSession };
