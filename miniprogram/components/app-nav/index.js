const session = require('../../utils/session');
const format = require('../../utils/format');
const motion = require('../../utils/parent-motion');
const roleNavigation = require('../../utils/role-navigation');
const entries = [
  { page: 'complaints', name: '投诉与建议', icon: 'summaries', parentOrAdmin: true, group: '班级与家校' },
  { page: 'homework-drafts', name: '拍照作业草稿', icon: 'homework', teacherOnly: true, group: '学习成长' },
  { page: 'principal', name: '校区运营明细', icon: 'home', adminOnly: true, group: '班级与家校' },
  { page: 'growth', name: '今日成长', icon: 'home', group: '学习成长' },
  { page: 'adventure', name: '成长乐园', icon: 'points', group: '学习成长' },
  { page: 'workbench', name: '教师工作台', icon: 'homework', teacherOnly: true, group: '班级与家校' },
  { page: 'follow-ups', name: '关注与沟通', icon: 'summaries', group: '班级与家校' },
  { page: 'homework', name: '学习作业', icon: 'homework', group: '学习成长' },
  { page: 'mistakes', name: '错题复习', icon: 'mistakes', group: '学习成长' },
  { page: 'fitness', name: '体能打卡', icon: 'fitness', group: '学习成长' },
  { page: 'scan', name: '拍照识题', icon: 'homework', teacherOnly: true, group: '学习成长' },
  { page: 'rewards', name: '积分与奖品', icon: 'points', group: '学习成长' },
  { page: 'notifications', name: '通知中心', icon: 'attendance', group: '班级与家校' },
  { page: 'points', name: '成长积分', icon: 'points', group: '学习成长' },
  { page: 'students', name: '学生名册', parentName: '我的孩子', icon: 'students', group: '班级与家校' },
  { page: 'family-services', name: '课程与订餐', icon: 'lessons', group: '班级与家校' },
  { page: 'safety', name: '接送管理', icon: 'attendance', group: '班级与家校' },
  { page: 'privacy', name: '隐私与数据', icon: 'students', parentOrAdmin: true, group: '班级与家校' },
  { page: 'refunds', name: '退款与记录', icon: 'lessons', parentOrAdmin: true, group: '班级与家校' },
  { page: 'attendance', name: '考勤签到', icon: 'attendance', teacherOnly: true, group: '班级与家校' },
  { page: 'teacher-attendance', name: '老师签到', icon: 'attendance', teacherOnly: true, group: '班级与家校' },
  { page: 'campuses', name: '校区与权限', icon: 'students', adminOnly: true, group: '班级与家校' },
  { page: 'lessons', name: '课程课时', icon: 'lessons', group: '班级与家校' },
  { page: 'campus-reports', name: '校区总结', icon: 'summaries', adminOnly: true, group: '班级与家校' },
  { page: 'class-reports', name: '班级日报', icon: 'summaries', teacherOnly: true, group: '班级与家校' },
  { page: 'summaries', name: '每日小结', icon: 'summaries', group: '班级与家校' }
];
Component({
  properties: { current: String, section: String },
  data: { roleName: '', userName: '', demo: false, teacher: false, open: false, active: '', allActive: false, groups: [], campuses: [], campusNames: [], campusIndex: 0, campusName: '' },
  observers: { section() { this.sync(); } },
  lifetimes: { attached() { this.sync(); } },
  pageLifetimes: { show() { this.sync(); } },
  methods: {
    sync() {
      const value = session.current();
      const teacher = session.isTeacher();
      const admin = session.isAdmin();
      const campuses = session.accessibleCampuses();
      const campusIndex = campuses.findIndex(campus => campus.id === getApp().globalData.selectedCampusId) + 1;
      const campusNames = [value && value.user.role === 'SUPER_ADMIN' ? '全部校区' : '全部授权校区'].concat(campuses.map(campus => campus.name));
      const pages = getCurrentPages();
      const route = pages.length ? pages[pages.length - 1].route : '';
      const current = this.data.current || route.split('/')[1] || 'dashboard';
      const role = value && value.user.role;
      const principal = role === 'ADMIN';
      const tabs = roleNavigation.tabs(role);
      const allowed = entries.filter(item => (!item.teacherOnly || teacher) && (!item.adminOnly || admin) && (!item.parentOrAdmin || admin || !teacher));
      const groups = roleNavigation.groups(allowed, role);
      const activePage = ['growth', 'workbench', 'principal'].includes(current) ? 'dashboard' : current === 'homework-detail' ? 'homework' : current;
      const section = this.data.section || 'overview';
      this._navigating = false;
      this.setData({ owner: role === 'SUPER_ADMIN', parent: role === 'PARENT', reducedMotion: motion.reduced(), tabs: tabs.map(tab => Object.assign({}, tab, { key: tab.section || tab.page,
        selected: principal && current === 'principal' ? tab.section === (['todos', 'affairs'].includes(section) ? section : 'overview') : tab.page === activePage })),
        sheetTitle: role === 'SUPER_ADMIN' ? '管理' : role === 'ADMIN' ? '更多校务' : role === 'TEACHER' ? '带班工具' : '我的', roleName: value ? format.label(value.user.role) : '', userName: value ? value.user.name : '', demo: !!(value && value.demo), teacher, campuses, campusNames, campusIndex, campusName: campuses.length === 1 ? campuses[0].name : campusNames[campusIndex], active: ['growth', 'workbench'].includes(current) ? 'dashboard' : current === 'homework-detail' ? 'homework' : current === 'mistake-detail' ? 'mistakes' : current, allActive: !tabs.some(tab => tab.page === activePage) && !(principal && current === 'principal'), groups });
    },
    changeCampus(event) {
      const pages = getCurrentPages();
      const page = pages[pages.length - 1];
      if (page && page.data && (page.data.busy || page.data.uploading)) { wx.showToast({ title: '请等待当前操作完成', icon: 'none' }); return; }
      const index = Number(event.detail.value);
      if (!Number.isInteger(index) || index < 0 || index > this.data.campuses.length) return;
      if (getApp().setCampus(index ? this.data.campuses[index - 1].id : null)) {
        this.sync();
        if (page && page.refresh) page.refresh();
      }
    },
    toggle() { if (this.data.owner && this.data.open) return; this.setData({ open: !this.data.open }); },
    toggleMotion(event) {
      if (!this.data.parent) return;
      try { motion.save(event.detail.value); }
      catch (_) { wx.showToast({ title: '偏好未保存，请重试', icon: 'none' }); return; }
      this.setData({ reducedMotion: !!event.detail.value });
      getCurrentPages().forEach(page => {
        if (page.data.user && page.data.user.role === 'PARENT') page.setData({ reducedMotion: !!event.detail.value });
      });
    },
    close() { this.setData({ open: false }); },
    noop() {},
    navigate(event) {
      if (this.data.parent && this._navigating) return;
      const page = event.currentTarget.dataset.page === 'dashboard' ? session.homePage() : event.currentTarget.dataset.page;
      const { kind, section } = event.currentTarget.dataset;
      if (!this.data.tabs.some(item => item.page === event.currentTarget.dataset.page) && !this.data.groups.some(group => group.items.some(item => item.page === page))) return;
      const query = page === 'principal' && section ? '?section=' + section : page === 'follow-ups' && kind === 'MESSAGE' ? '?kind=MESSAGE' : '';
      const url = '/pages/' + page + '/index' + query;
      const pages = getCurrentPages();
      this.setData({ open: false });
      const current = pages[pages.length - 1];
      if (current && current.route === 'pages/' + page + '/index') {
        if (page === 'principal' && section) current.showSection(section);
        return;
      }
      if (this.data.parent) this._navigating = true;
      wx.reLaunch({ url, fail: () => { this._navigating = false; wx.showToast({ title: '页面暂未打开，请重试', icon: 'none' }); } });
    },
    async signOut() {
      if (this.data.accountBusy) return;
      this.setData({ accountBusy: true });
      try {
        await getApp().logout();
        this.setData({ open: false });
        wx.reLaunch({ url: '/pages/login/index' });
      } catch (error) { wx.showToast({ title: error.message || '退出失败，请重试', icon: 'none' }); }
      finally { this.setData({ accountBusy: false }); }
    },
    account() { wx.reLaunch({ url: '/pages/login/index?switch=1' }); }
  }
});
