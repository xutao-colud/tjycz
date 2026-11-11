const api = require('../../utils/api');
const format = require('../../utils/format');
const growth = require('../../utils/growth');
const summaryReview = require('../../utils/summary-review');
const childCharacter = require('../../utils/child-character');
const { definePage, navigate } = require('../../utils/page');

definePage({
  data: { student: null, homework: [], completed: 0, summary: null },
  async loadData() {
    const studentId = Number(this.query.studentId);
    if (!Number.isSafeInteger(studentId) || studentId <= 0) throw new Error('学生编号无效，请返回学生名册');
    const params = { studentId, date: this.data.date };
    const [value, summaries] = await Promise.all([
      api.growth(Object.assign({ compact: true }, params)),
      this.data.teacher ? api.summaries(params) : []
    ]);
    if (!value.student || Number(value.student.id) !== studentId) throw new Error('该学生不存在或不在当前权限范围内，请返回学生名册');
    const student = Object.assign(format.decorate(value.student), {
      characterSrc: childCharacter(value.student.gender),
      avatarUrl: value.student.avatar ? api.fileUrl(value.student.avatar) : '',
      avatarText: String(value.student.name || '学').slice(0, 1),
      arrival: value.student.checkInTime ? growth.clock(value.student.checkInTime) : '未签到',
      departure: value.student.checkOutTime ? growth.clock(value.student.checkOutTime) : '未签退'
    });
    const homework = (value.homework || []).filter(row => Number(row.studentId) === studentId).map(growth.decorate);
    const summary = this.data.teacher ? summaries.find(row => Number(row.studentId) === studentId) : value.summary && Object.assign({ status: 'PUBLISHED' }, value.summary);
    return { student, homework, completed: homework.filter(row => row.status === 'COMPLETED').length, summary: summary ? summaryReview.decorate(summary) : null };
  },
  openSection(event) {
    if (this.data.loading || this.data.error || !this.data.student) return;
    const section = event.currentTarget.dataset.section;
    const page = { homework: 'homework', attendance: 'safety', summary: 'summaries' }[section];
    if (!page) return;
    navigate(this, '/pages/' + page + '/index?studentId=' + this.data.student.id + '&date=' + this.data.date + (section === 'summary' && this.data.teacher ? '&quick=1' : ''));
  },
  openHomework(event) {
    if (this.data.loading || this.data.error) return;
    const item = this.data.homework.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    if (item) navigate(this, '/pages/homework-detail/index?id=' + item.id);
  }
});
