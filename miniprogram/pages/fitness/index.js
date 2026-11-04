const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  data: { students: [], records: [], project: '跳绳', count: '', duration: '', result: '', formOpen: false },
  async loadData() {
    const selected = await selection(this, this.data.teacher);
    let records = await api.fitness({ date: this.data.date, studentId: selected.studentIds.length === 1 ? selected.studentIds[0] : '' });
    if (selected.studentIds.length > 1) records = records.filter(row => selected.studentIds.includes(Number(row.studentId)));
    return Object.assign({}, selected, { records: records.map(row => {
      const project = String(row.project || '');
      const tone = /跳绳|跳跃/.test(project) ? 'orange' : /跑|走|步/.test(project) ? 'blue' : /仰卧|俯卧|拉伸/.test(project) ? 'purple' : 'green';
      return Object.assign(format.decorate(row), { confirmation: row.confirmed ? '已确认' : '待确认', tone });
    }) });
  },
  toggleForm() { this.setData({ formOpen: !this.data.formOpen }); },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids }); this.refresh(); },
  async submit() {
    const count = this.data.count.trim() === '' ? NaN : Number(this.data.count);
    const duration = this.data.duration.trim() === '' ? NaN : Number(this.data.duration);
    if (!this.data.studentIds.length || !this.data.project.trim()) { toast('请选择学生并填写运动项目'); return; }
    if (!Number.isInteger(count) || count < 0 || !Number.isInteger(duration) || duration < 0 || (!count && !duration)) { toast('次数与秒数须为非负整数，至少一项大于 0'); return; }
    this.act(async () => {
      const result = await api.fitnessBatch({ date: this.data.date, studentIds: this.data.studentIds, project: this.data.project.trim(), count, duration });
      this.setData({ result: '已新增 ' + result.recordedCount + ' 条运动记录，同一天的相同项目不会重复记录。' });
    }, '体能记录已提交');
  }
});
