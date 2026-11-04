const session = require('../../utils/session');
const format = require('../../utils/format');
const entries = [
  { page: 'growth', name: '今日成长', icon: 'home', group: '学习成长' },
  { page: 'workbench', name: '教师工作台', icon: 'homework', teacherOnly: true, group: '班级与家校' },
  { page: 'follow-ups', name: '关注与沟通', icon: 'summaries', group: '班级与家校' },
  { page: 'homework', name: '学习作业', icon: 'homework', group: '学习成长' },
  { page: 'mistakes', name: '错题复习', icon: 'mistakes', group: '学习成长' },
  { page: 'fitness', name: '体能打卡', icon: 'fitness', group: '学习成长' },
  { page: 'scan', name: '拍照识题', icon: 'homework', teacherOnly: true, group: '学习成长' },
  { page: 'rewards', name: '积分与奖品', icon: 'points', group: '学习成长' },
  { page: 'notifications', name: '到离校消息', icon: 'attendance', group: '班级与家校' },
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
      const home = { page: 'dashboard', name: principal ? '概览' : role === 'PARENT' ? '今日' : role === 'TEACHER' ? '工作台' : '首页', icon: 'home' };
      const tabs = principal ? [
        Object.assign(home, { section: 'overview' }),
        { page: 'principal', section: 'todos', name: '待办', icon: 'summaries' },
        { page: 'principal', section: 'affairs', name: '校务', icon: 'students' }
      ] : [
        home,
        role === 'TEACHER' ? { page: 'students', name: '学生', icon: 'students' } : { page: 'homework', name: '学习', icon: 'homework' },
        { page: 'follow-ups', kind: 'MESSAGE', name: '消息', icon: 'summaries' }
      ];
      const groups = ['学习成长', '班级与家校'].map(title => ({ title, items: entries.filter(item => item.group === title && (!item.teacherOnly || teacher) && (!item.adminOnly || admin) && (!item.parentOrAdmin || admin || !teacher)).map(item => Object.assign({}, item, { name: !teacher && item.parentName ? item.parentName : item.page === 'teacher-attendance' && !admin ? '我的打卡' : item.name })) }));
      const activePage = ['growth', 'workbench', 'principal'].includes(current) ? 'dashboard' : current === 'homework-detail' ? 'homework' : current;
      const section = this.data.section || 'overview';
      this.setData({ tabs: tabs.map(tab => Object.assign({}, tab, { key: tab.section || tab.page,
        selected: principal && current === 'principal' ? tab.section === (['todos', 'affairs'].includes(section) ? section : 'overview') : tab.page === activePage })),
        sheetTitle: role === 'SUPER_ADMIN' ? '全部应用' : '我的', roleName: value ? format.label(value.user.role) : '', userName: value ? value.user.name : '', demo: !!(value && value.demo), teacher, campuses, campusNames, campusIndex, campusName: campuses.length === 1 ? campuses[0].name : campusNames[campusIndex], active: ['growth', 'workbench'].includes(current) ? 'dashboard' : current === 'homework-detail' ? 'homework' : current === 'mistake-detail' ? 'mistakes' : current, allActive: !['dashboard', 'growth', 'workbench', 'homework', 'homework-detail', 'follow-ups'].includes(current), groups });
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
    toggle() { this.setData({ open: !this.data.open }); },
    close() { this.setData({ open: false }); },
    noop() {},
    navigate(event) {
      const page = event.currentTarget.dataset.page === 'dashboard' ? session.homePage() : event.currentTarget.dataset.page;
      const { kind, section } = event.currentTarget.dataset;
      const query = page === 'principal' && section ? '?section=' + section : page === 'follow-ups' && kind === 'MESSAGE' ? '?kind=MESSAGE' : '';
      const url = '/pages/' + page + '/index' + query;
      const pages = getCurrentPages();
      this.setData({ open: false });
      const current = pages[pages.length - 1];
      if (current && current.route === 'pages/' + page + '/index') {
        if (page === 'principal' && section) current.showSection(section);
        return;
      }
      wx.reLaunch({ url });
    },
    account() { wx.reLaunch({ url: '/pages/login/index?switch=1' }); }
  }
});
