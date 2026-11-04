const api = require('../../utils/api');
const session = require('../../utils/session');
const format = require('../../utils/format');
const { definePage } = require('../../utils/page');
definePage({
  data: { dashboard: null, settings: {}, metrics: [], activities: [], progress: [], children: [] },
  async loadData() {
    const [me, dashboard, settings] = await Promise.all([api.me(), api.dashboard(this.data.date), api.settings()]);
    const value = session.current();
    if (value) {
      const updated = Object.assign({}, value, { user: { id: me.id, name: me.name, role: me.role }, demo: me.demo });
      session.save(updated);
      getApp().globalData.session = updated;
    }
    const teacher = ['ADMIN', 'TEACHER'].includes(me.role);
    const metrics = teacher ? [
      { name: '授权学生', value: dashboard.studentCount || 0 }, { name: '今日到校', value: dashboard.presentCount || 0 },
      { name: '作业已完成', value: (dashboard.completedHomeworkCount || 0) + '/' + (dashboard.homeworkCount || 0) },
      { name: '待订正', value: dashboard.pendingCorrectionCount || 0 },
      { name: '待发小结', value: dashboard.pendingSummaryCount || 0 }, { name: '低课时提醒', value: dashboard.lowBalanceCount || 0 }
    ] : [
      { name: '绑定孩子', value: dashboard.studentCount || 0 }, { name: '当日到校', value: dashboard.presentCount || 0 },
      { name: '今日作业完成', value: (dashboard.completedHomeworkCount || 0) + '/' + (dashboard.homeworkCount || 0) },
      { name: '待订正', value: dashboard.pendingCorrectionCount || 0 }
    ];
    return { user: me, teacher, demo: !!me.demo, dashboard, settings, metrics,
      children: (dashboard.students || []).map(format.decorate),
      activities: (dashboard.activities || []).map(row => Object.assign({}, row, { timeText: format.time(row.time) })),
      progress: (dashboard.subjectProgress || []).map(row => Object.assign({}, row, { percent: row.total ? Math.round(row.completed * 100 / row.total) : 0 })),
      weekly: dashboard.weeklyAttendance || [] };
  }
});
