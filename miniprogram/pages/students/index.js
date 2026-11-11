const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, navigate } = require('../../utils/page');

definePage({
  data: { students: [], classes: [], classNames: ['全部授权班级'], classIndex: 0, keyword: '' },
  async loadData() {
    const classes = this.data.teacher ? await api.classes() : [];
    const classId = this.data.classIndex ? (classes[this.data.classIndex - 1] || {}).id : '';
    const rows = (await api.students({ classId, keyword: this.data.keyword.trim(), date: this.data.date })).map(format.decorate);
    const students = rows.map((item, index) => Object.assign({}, item, {
      avatarUrl: item.avatar ? api.fileUrl(item.avatar) : '',
      avatarText: String(item.name || '学').slice(0, 1),
      avatarTone: index % 4,
      checkInClock: item.checkInTime ? format.time(item.checkInTime).slice(11) : '未签到',
      checkOutClock: item.checkOutTime ? format.time(item.checkOutTime).slice(11) : '未签退'
    }));
    return { students, classes, classNames: ['全部授权班级'].concat(classes.map(item => item.name)) };
  },
  changeClass(event) { this.setData({ classIndex: Number(event.detail.value) }); this.refresh(); },
  search() { this.refresh(); },
  openStudent(event) {
    if (this.data.loading || this.data.error) return;
    const id = Number(event.currentTarget.dataset.id);
    if (!Number.isSafeInteger(id) || id <= 0 || !this.data.students.some(item => Number(item.id) === id)) return;
    navigate(this, '/pages/student-detail/index?studentId=' + id + '&date=' + this.data.date);
  }
});
