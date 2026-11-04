const session = require('../../utils/session');
const format = require('../../utils/format');
Component({
  properties: { current: String },
  data: { roleName: '', demo: false, teacher: false, open: false },
  lifetimes: { attached() {
    const value = session.current();
    this.setData({ roleName: value ? format.label(value.user.role) : '', demo: !!(value && value.demo), teacher: session.isTeacher() });
  } },
  methods: {
    toggle() { this.setData({ open: !this.data.open }); },
    navigate(event) {
      const url = '/pages/' + event.currentTarget.dataset.page + '/index';
      const pages = getCurrentPages();
      if ('/' + pages[pages.length - 1].route === url) { this.setData({ open: false }); return; }
      wx.reLaunch({ url });
    },
    account() { wx.reLaunch({ url: '/pages/login/index?switch=1' }); }
  }
});
