const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage } = require('../../utils/page');

const sections = ['overview', 'todos', 'affairs', 'arrived', 'present', 'warnings', 'classroom', 'schedule'];
const affairs = [
  { title: '学生与班级', links: [['学生档案', 'students'], ['班级日报', 'class-reports']] },
  { title: '教师与安排', links: [['教师考勤', 'teacher-attendance'], ['班级工作台', 'workbench'], ['当日排课', 'principal', 'schedule']] },
  { title: '课时与费用', links: [['课时账户', 'lessons'], ['续费意向与订餐', 'family-services'], ['退款与审核', 'refunds']] },
  { title: '统计与设置', links: [['投诉与建议', 'complaints'], ['校区总结', 'campus-reports'], ['校区设置', 'campuses'], ['运营统计', 'dashboard']] }
];
function todoUrl(row, date) {
  const student = 'date=' + (row.date || date) + '&studentId=' + row.studentId;
  return {
    COMPLAINT: '/pages/complaints/index?id=' + row.id,
    SAFETY: '/pages/safety/index?view=pickups&' + student,
    FOLLOW_UP: '/pages/follow-ups/index?id=' + row.id,
    SUMMARY: '/pages/summaries/index?' + student,
    LEAVE: '/pages/safety/index?tab=leaves&' + student,
    REFUND: '/pages/refunds/index?id=' + row.id,
    TEACHER: '/pages/teacher-attendance/index?date=' + date
  }[row.kind];
}

definePage({
  adminOnly: true,
  data: { section: 'overview', schedules: [], overview: null, classId: '', affairs, detailTitle: '', detailRows: [], classroom: null },
  onLoad(query) {
    this.setData({ section: sections.includes(query.section) ? query.section : 'overview' });
  },
  async loadData() {
    const date = this.data.date;
    if (this.data.section === 'affairs') return {};
    if (this.data.section === 'schedule') return { schedules: await api.request('GET', '/schedules', { date }) };
    const [result, satisfaction] = await Promise.all([api.principal({ date }), date <= format.today() ? api.classSatisfaction({ date }) : Promise.resolve([])]);
    result.classes = result.classes.map(row => Object.assign({}, row, { satisfaction: satisfaction.find(item => Number(item.classId) === Number(row.id)) || null }));
    const todos = result.todos.map(row => Object.assign({}, row, { key: row.kind + ':' + row.id, url: todoUrl(row, date) }));
    return { overview: Object.assign({}, result, {
      todos, attention: todos.filter(row => row.kind === 'SAFETY').concat(todos.filter(row => row.kind !== 'SAFETY').slice(0, 3)),
      metrics: [
        { id: 'arrived', label: date === format.today() ? '今日到校' : '当日到校', value: result.arrived.length + ' / ' + result.expectedCount },
        { id: 'present', label: date === format.today() ? '当前在校' : '当日在校记录', value: result.present.length },
        { id: 'todos', label: '待处理', value: todos.length },
        { id: 'warnings', label: '课时预警', value: result.warnings.length }
      ]
    }) };
  },
  afterLoad() { this.showSection(this.data.section, false); },
  showSection(section, scroll = true) {
    if (!sections.includes(section)) return;
    const overview = this.data.overview;
    const classroom = overview && overview.classes.find(row => String(row.id) === String(this.data.classId));
    const title = { arrived: '已到校名单', present: '在校名单', warnings: '课时预警学生', classroom: '班级运行详情', schedule: '当日排课' }[section];
    const rows = overview && (section === 'classroom' ? overview.arrived.filter(s => classroom && s.classId === classroom.id) : overview[section]);
    this.setData({ section, detailTitle: title || '', detailRows: (Array.isArray(rows) && title ? rows : []).map(format.decorate), classroom: classroom || null });
    if (scroll && wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    if (scroll && section !== 'affairs' && (section === 'schedule' || !overview || overview.date !== this.data.date)) this.refresh();
  },
  openSection(event) { this.showSection(event.currentTarget.dataset.section); },
  openClass(event) {
    this.setData({ classId: event.currentTarget.dataset.id });
    this.showSection('classroom');
  }
});
