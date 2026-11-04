const session = require('./session');
const { today } = require('./format');
const { normalizeDate } = require('./calendar');
function message(error) { return error && error.message ? error.message : '操作失败，请稍后重试'; }
function toast(text) { wx.showToast({ title: text, icon: 'none', duration: 2400 }); }
function confirm(title, content) {
  const context = session.context();
  return new Promise(resolve => wx.showModal({ title, content, success: result => resolve(result.confirm && session.isCurrentContext(context)), fail: () => resolve(false) }));
}
function definePage(options) {
  const teacherOnly = options.teacherOnly;
  const adminOnly = options.adminOnly;
  const page = Object.assign({
    data: {},
    onUnload() { this._unloaded = true; this._loadGeneration = (this._loadGeneration || 0) + 1; },
    async onShow() {
      if (!session.requireSession(teacherOnly, adminOnly)) return;
      this._visible = true;
      const context = session.context();
      this.setData({ loading: true, error: '' });
      try {
        const me = await require('./api').me();
        if (!session.isCurrentContext(context) || this._unloaded) return;
        getApp().updateUser(me, me.demo);
        if (!session.requireSession(teacherOnly, adminOnly)) return;
        const value = session.current();
        this.setData({ teacher: session.isTeacher(), admin: session.isAdmin(), user: value.user, demo: !!value.demo });
        const nav = this.selectComponent && this.selectComponent('app-nav');
        if (nav) nav.sync();
        await this.refresh();
      } catch (error) {
        if (session.isCurrentContext(context) && !this._unloaded) {
          this.setData({ loading: false, error: message(error) });
        }
      } finally { wx.stopPullDownRefresh(); }
    },
    resetScopeData() {
      this._loadGeneration = (this._loadGeneration || 0) + 1;
      const value = session.current();
      this.setData(Object.assign({}, JSON.parse(initialData), {
        date: this.data.date, teacher: session.isTeacher(), admin: session.isAdmin(), user: value ? value.user : {}, demo: !!(value && value.demo), mediaReset: (this.data.mediaReset || 0) + 1
      }));
    },
    async refresh() {
      if (this._unloaded) return;
      const generation = (this._loadGeneration || 0) + 1;
      const context = session.context();
      this._loadGeneration = generation;
      this.setData({ loading: true, error: '' });
      try {
        const patch = await this.loadData();
        if (generation === this._loadGeneration && session.isCurrentContext(context) && !this._unloaded) this.setData(Object.assign({ loading: false }, patch || {}), () => {
          if (options.afterLoad && generation === this._loadGeneration && session.isCurrentContext(context) && !this._unloaded) options.afterLoad.call(this, patch || {});
        });
      } catch (error) {
        if (generation === this._loadGeneration && session.isCurrentContext(context) && !this._unloaded) this.setData({ loading: false, error: message(error) });
      } finally { wx.stopPullDownRefresh(); }
    },
    onPullDownRefresh() { return this.onShow(); },
    retry() { return this.onShow(); },
    async act(action, successText, reload) {
      if (this.data.busy) return false;
      const context = session.context();
      this.setData({ busy: true, actionError: '' });
      try {
        await action();
        if (!session.isCurrentContext(context) || this._unloaded) return false;
        if (successText) toast(successText);
        if (reload !== false) await this.refresh();
        return session.isCurrentContext(context) && !this._unloaded;
      } catch (error) {
        if (!session.isCurrentContext(context) || this._unloaded) return false;
        this.setData({ actionError: message(error) });
        toast(message(error));
        return false;
      } finally { if (session.isCurrentContext(context) && !this._unloaded) this.setData({ busy: false }); }
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
  const initialData = JSON.stringify(page.data);
  page.onLoad = function(query) {
    this.query = query || {};
    this._unloaded = false;
    this.setData({ date: normalizeDate(this.query.date, today()) });
    if (options.onLoad) options.onLoad.call(this, this.query);
  };
  delete page.teacherOnly;
  delete page.adminOnly;
  Page(page);
}
module.exports = { definePage, toast, confirm, message };
