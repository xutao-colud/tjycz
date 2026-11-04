const api = require('../../utils/api');
const session = require('../../utils/session');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  teacherOnly: true,
  data: { campuses: [], campusIndex: 0, campusNames: ['全部授权校区'], campusId: 0, students: [], records: [], classes: [], classNames: ['全部授权班级'], classIndex: 0, results: [], resultSuccess: 0, resultFailed: 0, presentCount: 0, leftCount: 0, historicalReason: '' },
  async loadData() {
    const [selected, classes, campuses] = await Promise.all([selection(this, true), api.classes(), api.campuses()]);
    const campusScope = session.context().campusId;
    const available = campuses.filter(item => (!campusScope || Number(item.id) === Number(campusScope)) && item.enabled !== false);
    const campus = available.find(item => Number(item.id) === Number(this.data.campusId)) || (available.length === 1 ? available[0] : null);
    const campusId = campus ? campus.id : 0;
    const campusIndex = campus ? available.findIndex(item => item.id === campus.id) + 1 : 0;
    const classId = this.data.classIndex ? (classes[this.data.classIndex - 1] || {}).id : '';
    const records = (await api.attendance({ date: this.data.date, classId })).map((item, index) => Object.assign(format.decorate(item), {
      avatarText: String(item.studentName || '学').slice(0, 1),
      avatarTone: index % 3,
      checkInClock: item.checkInTime ? format.time(item.checkInTime).slice(11) : '未签到',
      checkOutClock: item.checkOutTime ? format.time(item.checkOutTime).slice(11) : '未签退'
    })).filter(item => !campusId || Number(item.campusId) === Number(campusId));
    const students = selected.students.filter(item => (!classId || Number(item.classId) === Number(classId)) && (!campusId || Number(item.campusId) === Number(campusId)));
    const ids = new Set(students.map(item => Number(item.id)));
    return { today: format.today(), campuses: available, campusId, campusIndex, campusNames: ['全部授权校区'].concat(available.map(item => item.name)), students, studentIds: selected.studentIds.filter(id => ids.has(Number(id))), records, classes, classNames: ['全部授权班级'].concat(classes.map(item => item.campusName ? item.campusName + ' · ' + item.name : item.name)), presentCount: records.filter(item => item.status === 'PRESENT').length, leftCount: records.filter(item => item.status === 'LEFT').length };
  },
  changeCampus(event) { const index = Number(event.detail.value); this.setData({ campusIndex: index, classIndex: 0, campusId: index ? this.data.campuses[index - 1].id : 0, studentIds: [], results: [] }); this.refresh(); },
  changeDate(event) { this.setData({ date: event.detail.value, results: [], resultSuccess: 0, resultFailed: 0 }); this.refresh(); },
  changeClass(event) { this.setData({ classIndex: Number(event.detail.value), results: [], resultSuccess: 0, resultFailed: 0 }); this.refresh(); },
  async photoMark(event) {
    if (this.data.date !== format.today()) { toast('拍照考勤仅支持当天'); return; }
    const row = this.data.records.find(r => Number(r.studentId) === Number(event.currentTarget.dataset.id));
    if (!row) return;
    const action = event.currentTarget.dataset.action;
    await this.act(async () => {
      const result = await require('../../utils/teaching').recordPickup(this, row.studentId, row.campusId, action);
      if (this._unloaded) return;
      this.setData({ results: [{ studentId: row.studentId, name: row.studentName, success: true, outcome: '拍照登记成功', message: result.message + '，已为' + result.notificationCount + '位绑定家长生成站内消息' }], resultSuccess: 1, resultFailed: 0 });
    }, '考勤照片与家长消息已记录');
  },
  async batch(event) {
    if (!this.data.studentIds.length) { toast('请先选择学生'); return; }
    const out = event.currentTarget.dataset.action === 'out';
    if (out && this.data.date < format.today() && (!this.data.admin || !this.data.historicalReason.trim())) { toast('历史离校补录须由校长或老板填写核对原因'); return; }
    if (!await confirm(out ? '确认人工签退' : '确认到校登记', '为已选 ' + this.data.studentIds.length + ' 位学生' + (out ? '签退，并按排课结算课时。' : '确认实际到校后登记，重复签到不会重复创建。'))) return;
    await this.act(async () => {
      if (this._unloaded) return;
      const result = await (out ? api.checkOut : api.checkIn)({ studentIds: this.data.studentIds, date: this.data.date, historicalReason: this.data.historicalReason.trim() });
      const names = new Map(this.data.students.map(item => [Number(item.id), item.name]));
      const results = (result.results || []).map(item => Object.assign({}, item, { name: names.get(Number(item.studentId)) || '学生', outcome: item.success ? '操作成功' : '操作失败' }));
      this.setData({ results, resultSuccess: results.filter(item => item.success).length, resultFailed: results.filter(item => !item.success).length });
    }, '批量结果已更新');
  }
});
