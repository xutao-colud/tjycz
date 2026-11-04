const KEY = 'wanfu.session.v1';
const ROLES = ['ADMIN', 'TEACHER', 'PARENT'];
function restore() {
  try {
    const value = wx.getStorageSync(KEY);
    return value && value.token && value.user && ROLES.includes(value.user.role) ? value : null;
  } catch (error) { return null; }
}
function save(value) { wx.setStorageSync(KEY, value); }
function clear() { wx.removeStorageSync(KEY); }
function current() { return getApp().globalData.session; }
function isTeacher() {
  const value = current();
  return !!value && ['ADMIN', 'TEACHER'].includes(value.user.role);
}
function requireSession(teacherOnly) {
  const value = current();
  if (!value) {
    wx.reLaunch({ url: '/pages/login/index' });
    return false;
  }
  if (teacherOnly && !isTeacher()) {
    wx.showToast({ title: '此操作仅教师可用', icon: 'none' });
    wx.reLaunch({ url: '/pages/dashboard/index' });
    return false;
  }
  return true;
}
module.exports = { restore, save, clear, current, isTeacher, requireSession };
