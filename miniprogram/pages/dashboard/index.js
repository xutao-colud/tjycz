const api = require('../../utils/api');
const session = require('../../utils/session');
const format = require('../../utils/format');
const { definePage } = require('../../utils/page');
const applications = [
  { page: 'homework', name: '学习作业', icon: 'homework' },
  { page: 'mistakes', name: '错题成长', icon: 'mistakes' },
  { page: 'fitness', name: '体能打卡', icon: 'fitness' },
  { page: 'points', name: '成长积分', icon: 'points' }
];
definePage({
  data: { satisfactionStats: [], dashboard: null, settings: {}, metrics: [], operationMetrics: [], activities: [], progress: [], children: [], weekly: [], applications, management: [], percent: 0, arriving: false, roleName: '' },
  onLoad(query) {
    this.query = query || {};
    this._unloaded = false;
    this.setData({ arriving: this.query.fromLogin === '1' });
  },
  go(event) {
    const page = event.currentTarget.dataset.page;
    if (this.data.applications.concat(this.data.management, this.data.metrics, this.data.operationMetrics).some(item => item.page === page)) {
      const date = event.currentTarget.dataset.date || this.data.date;
      wx.navigateTo({ url: '/pages/' + page + '/index' + (['attendance', 'teacher-attendance', 'homework', 'summaries'].includes(page) ? '?date=' + encodeURIComponent(date) : '') });
    }
  },
  async loadData() {
    const value = session.current();
    const me = value.user;
    const admin = session.isAdmin();
    const owner = me.role === 'SUPER_ADMIN';
    const [dashboard, settings, satisfactionStats] = await Promise.all([api.dashboard(this.data.date), api.settings(), admin && this.data.date <= format.today() ? api.classSatisfaction({ date: this.data.date }) : Promise.resolve([])]);
    const teacher = session.isTeacher();
    const total = Number(dashboard.homeworkCount) || 0;
    const completed = Number(dashboard.completedHomeworkCount) || 0;
    const percent = total ? Math.min(100, Math.round(completed * 100 / total)) : 0;
    const metrics = [
      { page: teacher ? 'attendance' : 'students', name: teacher ? '今日到校' : '当日到校', value: dashboard.presentCount || 0, unit: '人', icon: 'attendance', tone: 'green' },
      { page: 'homework', name: '作业完成', value: completed + '/' + total, unit: '份', icon: 'homework', tone: 'purple' },
      { page: 'homework', name: '待订正', value: dashboard.pendingCorrectionCount || 0, unit: '份', icon: 'mistakes', tone: 'blue' },
      { page: teacher ? 'summaries' : 'students', name: teacher ? '待发小结' : '绑定孩子', value: teacher ? dashboard.pendingSummaryCount || 0 : dashboard.studentCount || 0, unit: teacher ? '份' : '人', icon: teacher ? 'summaries' : 'students', tone: 'orange' }
    ];
    const operationMetrics = [
      { page: 'attendance', name: '今日到校', value: dashboard.presentCount || 0, suffix: '/' + (dashboard.studentCount || 0) + ' 人', icon: 'attendance', tone: 'blue' },
      { page: 'teacher-attendance', name: '老师签到', value: dashboard.checkedInTeacherCount || 0, suffix: '/' + (dashboard.teacherCount || 0) + ' 人次', icon: 'attendance', tone: 'coral' },
      { page: 'students', name: '学生总数', value: dashboard.studentCount || 0, suffix: '人', icon: 'students', tone: 'orange' },
      owner ? { page: 'lessons', name: '课时预警', value: dashboard.lowBalanceCount || 0, suffix: '人', icon: 'lessons', tone: 'amber' } : { page: 'homework', name: '作业完成', value: completed, suffix: '/' + total + ' 份', icon: 'homework', tone: 'amber' }
    ];
    const management = [
      { page: 'growth', name: '今日成长', icon: 'home' },
      ...(teacher ? [{ page: 'workbench', name: '教师工作台', icon: 'homework' }] : []),
      { page: 'follow-ups', name: '关注与沟通', icon: 'summaries' },
      { page: 'students', name: teacher ? '学生名册' : '我的孩子', icon: 'students' },
      ...(teacher ? [{ page: 'attendance', name: '考勤签到', icon: 'attendance' }] : []),
      ...(teacher ? [{ page: 'teacher-attendance', name: admin ? '老师签到' : '我的打卡', icon: 'attendance' }] : []),
      ...(admin ? [{ page: 'campuses', name: '校区与权限', icon: 'students' }, { page: 'campus-reports', name: '校区总结', icon: 'summaries' }] : []),
      { page: 'safety', name: '接送管理', icon: 'attendance' },
      { page: 'lessons', name: '课程课时', icon: 'lessons' },
      { page: 'summaries', name: '每日小结', icon: 'summaries' }
    ];
    return { user: me, teacher, admin, owner, roleName: format.label(me.role), demo: !!value.demo, dashboard, settings, satisfactionStats, metrics, operationMetrics: admin ? operationMetrics : [], management: owner ? [
      { page: 'family-services', name: '续费意向与订餐', icon: 'lessons' },
      { page: 'campus-reports', name: '校区总结', icon: 'summaries' },
      { page: 'teacher-attendance', name: '教师考勤', icon: 'attendance' },
      { page: 'follow-ups', name: '服务跟进', icon: 'summaries' }
    ] : management, applications: owner ? [
      { page: 'principal', name: '校区运营明细', icon: 'home' },
      { page: 'lessons', name: '课时与续费', icon: 'lessons' },
      { page: 'refunds', name: '退款与审核', icon: 'lessons' },
      { page: 'campuses', name: '校区与权限', icon: 'students' }
    ] : applications, percent,
      greeting: teacher ? '今天也要元气满满' : '陪伴每一个小小进步',
      children: (dashboard.students || []).map(format.decorate),
      activities: (dashboard.activities || []).slice(0, 5).map((row, index) => Object.assign({}, row, { timeText: format.time(row.time), tone: index % 3 })),
      progress: (dashboard.subjectProgress || []).map((row, index) => Object.assign({}, row, { percent: row.total ? Math.min(100, Math.round(row.completed * 100 / row.total)) : 0, tone: ['green', 'blue', 'purple'][index % 3] })),
      weekly: (dashboard.weeklyAttendance || []).map(row => Object.assign({}, row, { percent: row.total ? Math.min(100, Math.round(row.present * 100 / row.total)) : 0, shortDate: row.date ? row.date.slice(5).replace('-', '/') : row.label })) };
  }
});
