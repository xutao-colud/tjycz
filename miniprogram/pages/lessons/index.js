const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  data: { students: [], accounts: [], ledger: [], renewals: [], settlements: [], accountIndex: 0, accountNames: ['全部课程账户'], courses: [], courseNames: ['选择课程'], courseIndex: 0, grantOpen: false, grantUnits: '1', grantReason: '', grantRequest: '', grantFingerprint: '' },
  async loadData() {
    const selected = await selection(this, false);
    if (!selected.studentIds.length) return Object.assign({}, selected, { accounts: [], ledger: [], renewals: [], settlements: [], accountIndex: 0, accountNames: ['全部课程账户'] });
    const studentId = selected.studentIds[0];
    const [accounts, renewals, settlements, courses] = await Promise.all([api.accounts({ studentId }), api.renewals({ studentId }), this.data.teacher ? api.settlements() : Promise.resolve([]), this.data.teacher ? api.courses() : Promise.resolve([])]);
    const child = selected.students.find(s => Number(s.id) === Number(studentId));
    const availableCourses = courses.filter(c => c.enabled && child && Number(c.campusId) === Number(child.campusId));
    const accountIndex = this.data.accountIndex > accounts.length ? 0 : this.data.accountIndex;
    const accountId = accountIndex ? accounts[accountIndex - 1].id : '';
    const ledger = await api.ledger({ studentId, accountId });
    return Object.assign({}, selected, { accounts: accounts.map((row, index) => Object.assign({}, row, { low: Number(row.availableBalance === undefined ? row.balance : row.availableBalance) <= Number(row.warningThreshold), tone: ['green', 'blue', 'orange'][index % 3] })),
      courses: availableCourses, courseNames: ['选择课程'].concat(availableCourses.map(c => c.name + ' · ' + format.label(c.type))), accountIndex, accountNames: ['全部课程账户'].concat(accounts.map(row => row.courseName)), ledger: ledger.map(format.decorate),
      renewals: renewals.filter(row => !accountId || Number(row.accountId) === Number(accountId)).map(row => Object.assign(format.decorate(row), { money: format.amount(row.amount) })),
      settlements: settlements.filter(row => Number(row.studentId) === Number(studentId) && row.status === 'PENDING').map(format.decorate) });
  },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids, accountIndex: 0, courseIndex: 0, grantOpen: false, grantRequest: '' }); this.refresh(); },
  account(event) { this.setData({ accountIndex: Number(event.detail.value) }); this.refresh(); },
  toggleGrant() { this.setData({ grantOpen: !this.data.grantOpen }); },
  course(event) { this.setData({ courseIndex: Number(event.detail.value) }); },
  async grant() {
    const course = this.data.courses[this.data.courseIndex - 1], units = Number(this.data.grantUnits);
    if (!course || !this.data.studentIds.length || !Number.isFinite(units) || units <= 0 || units > 1000 || !this.data.grantReason.trim()) { toast('请选择课程，填写有效课时和原因'); return; }
    if (!await confirm('确认增加课时', '增加' + units + '课时到「' + course.name + '」，独立记入增补课时流水，不代表收款。')) return;
    const body = { studentId: this.data.studentIds[0], courseId: course.id, units, reason: this.data.grantReason.trim() }, fingerprint = JSON.stringify(body);
    if (!this.data.grantRequest || this.data.grantFingerprint !== fingerprint) this.setData({ grantRequest: require('../../utils/teaching').requestId(), grantFingerprint: fingerprint });
    await this.act(async () => { await api.request('POST', '/lesson-grants', Object.assign({}, body, { requestId: this.data.grantRequest })); this.setData({ grantOpen: false, grantReason: '', grantRequest: '' }); }, '课时已增加并记录流水');
  },
  retrySettlement(event) { this.act(() => api.retrySettlement(event.currentTarget.dataset.id), '消课已重新处理，请核对状态'); }
});
