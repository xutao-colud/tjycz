const session = require('./session');
const { today } = require('./format');
function message(error) { return error && error.message ? error.message : '操作失败，请稍后重试'; }
function toast(text) { wx.showToast({ title: text, icon: 'none', duration: 2400 }); }
function confirm(title, content) {
  return new Promise(resolve => wx.showModal({ title, content, success: result => resolve(result.confirm), fail: () => resolve(false) }));
}
function definePage(options) {
  const teacherOnly = options.teacherOnly;
  const page = Object.assign({
    data: {},
    onLoad(query) { this.query = query || {}; this._unloaded = false; },
    onUnload() { this._unloaded = true; this._loadGeneration = (this._loadGeneration || 0) + 1; },
    onShow() {
      if (!session.requireSession(teacherOnly)) return;
      const value = session.current();
      this.setData({ teacher: session.isTeacher(), user: value.user, demo: !!value.demo });
      this.refresh();
    },
    async refresh() {
      if (this._unloaded) return;
      const generation = (this._loadGeneration || 0) + 1;
      this._loadGeneration = generation;
      this.setData({ loading: true, error: '' });
      try {
        const patch = await this.loadData();
        if (generation === this._loadGeneration) this.setData(Object.assign({ loading: false }, patch || {}));
      } catch (error) {
        if (generation === this._loadGeneration) this.setData({ loading: false, error: message(error) });
      } finally { wx.stopPullDownRefresh(); }
    },
    onPullDownRefresh() { this.refresh(); },
    retry() { this.refresh(); },
    async act(action, successText, reload) {
      if (this.data.busy) return false;
      this.setData({ busy: true, actionError: '' });
      try {
        await action();
        if (successText) toast(successText);
        if (reload !== false) await this.refresh();
        return true;
      } catch (error) {
        this.setData({ actionError: message(error) });
        toast(message(error));
        return false;
      } finally { this.setData({ busy: false }); }
    },
    changeDate(event) { this.setData({ date: event.detail.value }); this.refresh(); },
    field(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }); },
    pickStudents(event) { this.setData({ studentIds: event.detail.ids }); },
    go(event) {
      const url = event.currentTarget.dataset.url;
      if (url) wx.navigateTo({ url });
    }
  }, options);
  page.data = Object.assign({ loading: true, error: '', busy: false, actionError: '', date: today(), teacher: false, user: {}, demo: false, studentIds: [] }, options.data || {});
  delete page.teacherOnly;
  Page(page);
}
module.exports = { definePage, toast, confirm, message };
