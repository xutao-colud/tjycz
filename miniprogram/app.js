const session = require('./utils/session');
App({
  globalData: { session: null, selectedStudentIds: [], selectedCampusId: null, contextVersion: 0 },
  onLaunch() { this.globalData.session = session.restore(); },
  invalidateScope() {
    this.globalData.contextVersion++;
    this.globalData.selectedStudentIds = [];
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
    pages.forEach(page => { if (page.resetScopeData) page.resetScopeData(); });
  },
  setSession(value) {
    if (!session.valid(value)) throw new Error('登录返回缺少有效会话，请重试');
    session.save(value);
    this.globalData.session = value;
    this.globalData.selectedCampusId = null;
    this.invalidateScope();
  },
  updateUser(user, demo) {
    const value = session.current();
    if (!value) return;
    const updated = Object.assign({}, value, { user: Object.assign({}, value.user, user) });
    if (!session.valid(updated)) throw new Error('账号角色无效，请重新登录');
    if (demo !== undefined) updated.demo = demo;
    const changed = session.accessKey(value.user) !== session.accessKey(updated.user);
    session.save(updated);
    this.globalData.session = updated;
    const selected = this.globalData.selectedCampusId;
    if (selected && !session.accessibleCampuses(updated.user).some(campus => campus.id === selected)) this.globalData.selectedCampusId = null;
    if (changed || selected !== this.globalData.selectedCampusId) this.invalidateScope();
  },
  setCampus(id) {
    const campusId = id === '' || id === null || id === undefined ? null : Number(id);
    if (!session.valid(session.current())) throw new Error('请先登录');
    if (campusId !== null && !session.accessibleCampuses().some(campus => campus.id === campusId)) throw new Error('无权访问此校区');
    if (campusId === this.globalData.selectedCampusId) return false;
    this.globalData.selectedCampusId = campusId;
    this.invalidateScope();
    return true;
  },
  clearSession() {
    session.clear();
    this.globalData.session = null;
    this.globalData.selectedCampusId = null;
    this.invalidateScope();
  },
  async logout() {
    const value = session.current();
    if (!value) { this.clearSession(); return; }
    try { await require('./utils/api').request('POST', '/auth/logout', {}, false, true); }
    catch (error) { if (error.statusCode !== 401) throw new Error('退出失败，服务器会话尚未撤销，请联网重试。'); }
    if (session.current() && session.current().token === value.token) this.clearSession();
  }
});
