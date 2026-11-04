const session = require('./utils/session');
App({
  globalData: { session: null, selectedStudentIds: [] },
  onLaunch() { this.globalData.session = session.restore(); },
  setSession(value) {
    session.save(value);
    this.globalData.session = value;
    this.globalData.selectedStudentIds = [];
  },
  clearSession() {
    session.clear();
    this.globalData.session = null;
    this.globalData.selectedStudentIds = [];
  }
});
