const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  teacherOnly: true,
  data: { students: [], records: [], classes: [], classNames: ['全部授权班级'], classIndex: 0, results: [] },
  async loadData() {
    const [selected, classes] = await Promise.all([selection(this, true), api.classes()]);
    const classId = this.data.classIndex ? (classes[this.data.classIndex - 1] || {}).id : '';
    const records = (await api.attendance({ date: this.data.date, classId })).map(format.decorate);
    const students = classId ? selected.students.filter(item => Number(item.classId) === Number(classId)) : selected.students;
    const ids = new Set(students.map(item => Number(item.id)));
    return { students, studentIds: selected.studentIds.filter(id => ids.has(Number(id))), records, classes, classNames: ['全部授权班级'].concat(classes.map(item => item.name)) };
  },
  changeClass(event) { this.setData({ classIndex: Number(event.detail.value), results: [] }); this.refresh(); },
  async batch(event) {
    if (!this.data.studentIds.length) { toast('请先选择学生'); return; }
    const out = event.currentTarget.dataset.action === 'out';
    if (!await confirm(out ? '确认人工签退' : '确认人工签到', '为已选 ' + this.data.studentIds.length + ' 位学生' + (out ? '签退；后端将按排课结算课时。' : '签到。'))) return;
    await this.act(async () => {
      const result = await (out ? api.checkOut : api.checkIn)({ studentIds: this.data.studentIds, date: this.data.date });
      const names = new Map(this.data.students.map(item => [Number(item.id), item.name]));
      this.setData({ results: (result.results || []).map(item => Object.assign({}, item, { name: names.get(Number(item.studentId)) || '学生', outcome: item.success ? '操作成功' : '操作失败' })) });
    }, '批量结果已更新');
  }
});
