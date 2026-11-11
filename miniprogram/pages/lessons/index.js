const api = require('../../utils/api');
const finance = require('../../utils/course-finance');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  data: { lessonSessions: [], consumptionAvailable: true, lessonReason: '', courseGroups: [], renewalOpen: false, amountYuan: '', paidUnits: '', giftUnits: '0', paymentIndex: 0, paymentNames: ['微信线下收款','支付宝','现金','银行转账'], renewalNote: '', renewalRequest: '', renewalFingerprint: '', catalogOpen: false, catalogSupported: false, existingIndex: 0, catalogTargets: [], coursePresets: ['成长营（晚辅）','680（补弱强优）','小作家','剑桥','书法','绘画'], presetIndex: 0, rootIndex: 0, rootCourses: [], rootNames: [], students: [], accounts: [], ledger: [], renewals: [], settlements: [], accountIndex: 0, accountNames: ['全部课程账户'], courses: [], courseNames: ['选择课程'], courseIndex: 0, grantOpen: false, grantUnits: '1', grantReason: '', grantRequest: '', grantFingerprint: '' },
  async loadData() {
    const selected = await selection(this, false);
    if (!selected.studentIds.length) return Object.assign({}, selected, { lessonSessions: [], courseGroups: [], courses: [], rootCourses: [], renewalOpen: false, catalogOpen: false, accounts: [], ledger: [], renewals: [], settlements: [], accountIndex: 0, accountNames: ['全部课程账户'] });
    const studentId = selected.studentIds[0];
    const [accounts, renewals, settlements, courses] = await Promise.all([api.accounts({ studentId }), api.renewals({ studentId }), this.data.teacher ? api.settlements() : Promise.resolve([]), this.data.teacher ? api.courses() : Promise.resolve([])]);
    let lessonSessions = [], consumptionAvailable = true;
    if (this.data.teacher) {
      try { lessonSessions = await api.request('GET', '/lesson-sessions', { studentId, date: this.data.date }); }
      catch (error) { if (error.statusCode !== 404) throw error; consumptionAvailable = false; }
    }
    const child = selected.students.find(s => Number(s.id) === Number(studentId));
    const availableCourses = courses.filter(c => c.enabled && child && Number(c.campusId) === Number(child.campusId)).map(c => Object.assign({}, c, { priceText: format.amount(c.price) }));
    const accountIndex = this.data.accountIndex > accounts.length ? 0 : this.data.accountIndex;
    const accountId = accountIndex ? accounts[accountIndex - 1].id : '';
    const ledger = await api.ledger({ studentId, accountId });
    return Object.assign({}, selected, { lessonSessions, consumptionAvailable, courseGroups: finance.groups(accounts), catalogTargets: ['选择已有课程'].concat(availableCourses.map(finance.courseLabel)), rootCourses: availableCourses.filter(c => !c.parentCourseId), rootNames: availableCourses.filter(c => !c.parentCourseId).map(c => c.name), accounts: accounts.map((row, index) => Object.assign({}, row, { low: typeof row.lowBalance === 'boolean' ? row.lowBalance : Number(row.availableBalance === undefined ? row.balance : row.availableBalance) <= Number(row.warningThreshold), tone: ['green', 'blue', 'orange'][index % 3], consumptionText: row.consumptionMode === 'DAILY' ? '到校并签退，每天扣 ' + row.consumptionUnits + ' ' + row.unit : row.consumptionMode === 'TEACHER_CONFIRM' ? '老师确认每次上课，扣 ' + row.consumptionUnits + ' ' + row.unit : '签退时按已启用排课的课时扣减' })),
      courses: availableCourses, courseNames: ['选择课程'].concat(availableCourses.map(c => finance.courseLabel(c) + ' · ' + c.unit)), accountIndex, accountNames: ['全部课程账户'].concat(accounts.map(finance.courseLabel)), ledger: ledger.map(format.decorate),
      renewals: renewals.filter(row => !accountId || Number(row.accountId) === Number(accountId)).map(row => Object.assign(format.decorate(row), { money: format.amount(row.amount) })),
      settlements: settlements.filter(row => Number(row.studentId) === Number(studentId) && row.status === 'PENDING').map(format.decorate) });
  },
  pickStudents(event) { if (this.data.busy) return; this.setData({ studentIds: event.detail.ids, accountIndex: 0, courseIndex: 0, existingIndex: 0, rootIndex: 0, presetIndex: 0, grantOpen: false, renewalOpen: false, catalogOpen: false, amountYuan: '', paidUnits: '', giftUnits: '0', renewalNote: '', lessonReason: '', renewalRequest: '', grantRequest: '' }); this.refresh(); },
  account(event) { this.setData({ accountIndex: Number(event.detail.value) }); this.refresh(); },
  toggleGrant() { this.setData({ grantOpen: !this.data.grantOpen }); },
  course(event) { if (this.data.busy) return; this.setData({ courseIndex: Number(event.detail.value) }); this.quoteRenewal(); },
  renewalUnits(event) { if (this.data.busy) return; this.setData({ paidUnits: event.detail.value }); this.quoteRenewal(); },
  quoteRenewal() {
    const course = this.data.courses[this.data.courseIndex - 1];
    let amountYuan = '';
    if (course) { try { amountYuan = (finance.priceTotal(Number(course.price), this.data.paidUnits) / 100).toFixed(2); } catch (_) { /* Incomplete input stays empty until it is valid. */ } }
    this.setData({ amountYuan });
  },
  async grant() {
    const course = this.data.courses[this.data.courseIndex - 1], units = Number(this.data.grantUnits);
    if (!course || !this.data.studentIds.length || !Number.isFinite(units) || units <= 0 || units > 1000 || !this.data.grantReason.trim()) { toast('请选择课程，填写有效课时和原因'); return; }
    if (!await confirm('确认增加课时', '增加' + units + '课时到「' + course.name + '」，独立记入增补课时流水，不代表收款。')) return;
    const body = { studentId: this.data.studentIds[0], courseId: course.id, units, reason: this.data.grantReason.trim() }, fingerprint = JSON.stringify(body);
    if (!this.data.grantRequest || this.data.grantFingerprint !== fingerprint) this.setData({ grantRequest: require('../../utils/teaching').requestId(), grantFingerprint: fingerprint });
    await this.act(async () => { await api.request('POST', '/lesson-grants', Object.assign({}, body, { requestId: this.data.grantRequest })); this.setData({ grantOpen: false, grantReason: '', grantRequest: '' }); }, '课时已增加并记录流水');
  },
  toggleRenewal() { if (this.data.admin && !this.data.busy) this.setData({ renewalOpen: !this.data.renewalOpen }); },
  async toggleCatalog() {
    if (!this.data.admin || this.data.busy) return;
    if (this.data.catalogOpen) { this.setData({ catalogOpen: false }); return; }
    await this.act(async () => {
      let options;
      try { options = await api.request('GET', '/courses/catalog-options'); }
      catch (error) { if (error.statusCode === 404) throw new Error('当前服务尚未支持课程分级，请更新后台后设置'); throw error; }
      if (!options.hierarchySupported) throw new Error('当前服务尚未支持课程分级');
      this.setData({ catalogOpen: true, catalogSupported: true });
    }, '', false);
  },
  chooseFinance(event) { this.setData({ [event.currentTarget.dataset.field]: Number(event.detail.value) }); },
  async saveCourse() {
    if (!this.data.admin || this.data.busy || !this.data.catalogSupported) return;
    const child = this.data.students.find(s => Number(s.id) === Number(this.data.studentIds[0]));
    const parent = this.data.presetIndex ? this.data.rootCourses[this.data.rootIndex] : null;
    if (!child || this.data.presetIndex && !parent) { toast('请先创建成长营大类，再选择所属大类'); return; }
    const existing = this.data.existingIndex ? this.data.courses[this.data.existingIndex - 1] : null;
    if (this.data.existingIndex && !existing) { toast('课程已变化，请重新选择'); return; }
    if (existing) {
      if (!await confirm('调整课程名称与归属', '保留「' + existing.name + '」已有账户、价格、课时及流水，仅调整分类显示。')) return;
      await this.act(async () => { await api.request('PUT', '/courses/' + existing.id + '/classification', { name: this.data.coursePresets[this.data.presetIndex], parentCourseId: parent ? parent.id : null }); this.setData({ catalogOpen: false, catalogSupported: false, existingIndex: 0 }); }, '课程归属已更新');
      return;
    }
    toast('请在后台课程管理创建课程并设置单价');
  },
  async saveRenewal() {
    if (!this.data.admin || this.data.busy) return;
    const course = this.data.courses[this.data.courseIndex - 1], studentId = this.data.studentIds[0];
    if (!course || !studentId) { toast('请选择学生和续费课程'); return; }
    let amount, lessons, giftLessons;
    try { amount = finance.money(this.data.amountYuan); lessons = finance.units(this.data.paidUnits); giftLessons = finance.units(this.data.giftUnits, true); } catch (e) { toast(e.message); return; }
    const body = { studentId, courseId: course.id, amount, lessons, giftLessons, paymentMethod: ['WECHAT_OFFLINE','ALIPAY','CASH','BANK'][this.data.paymentIndex], note: this.data.renewalNote.trim() };
    const fingerprint = JSON.stringify(body);
    if (!this.data.renewalRequest || this.data.renewalFingerprint !== fingerprint) this.setData({ renewalRequest: require('../../utils/teaching').requestId(), renewalFingerprint: fingerprint });
    await this.act(async () => {
      const enrollment = await api.request('POST', '/enrollments', { studentId, courseId: course.id });
      await api.request('POST', '/renewals', { accountId: enrollment.accountId, amount, lessons, giftLessons, paymentMethod: body.paymentMethod, note: body.note, requestId: this.data.renewalRequest });
      this.setData({ renewalOpen: false, amountYuan: '', paidUnits: '', giftUnits: '0', renewalNote: '', renewalRequest: '' });
    }, '续费单已登记，核实收款后确认入账');
  },
  async confirmRenewal(event) {
    if (!this.data.admin || this.data.busy) return;
    const row = this.data.renewals.find(r => Number(r.id) === Number(event.currentTarget.dataset.id));
    if (!row || row.status !== 'PENDING' || !await confirm('确认收款并入账', '请核实已收到 ¥' + row.money + '，仅为「' + row.courseName + '」增加购买 ' + row.lessons + '、赠送 ' + row.giftLessons + ' 课时。')) return;
    await this.act(() => api.request('POST', '/renewals/' + row.id + '/confirm', {}), '收款已确认，课时已入账');
  },
  async confirmLesson(event) {
    if (!this.data.teacher || this.data.busy) return;
    const lesson = this.data.lessonSessions.find(row => Number(row.id) === Number(event.currentTarget.dataset.id));
    if (!lesson || !lesson.canConfirm) return;
    const studentId = this.data.studentIds[0];
    if (this.data.date !== format.today() && !this.data.lessonReason.trim()) { toast('补录历史上课请填写核对原因'); return; }
    if (!await confirm('确认实际上课并扣课', '请核实孩子已完成「' + lesson.courseName + '」，本次扣 ' + lesson.units + ' ' + lesson.unit + '。其他课程余额不受影响。')) return;
    if (this.data.studentIds[0] !== studentId) return;
    await this.act(async () => {
      const result = await api.request('POST', '/lesson-sessions/confirm', { studentId, scheduleId: lesson.id, expectedVersion: lesson.policyVersion, reason: this.data.lessonReason.trim() });
      toast(result.status === 'SETTLED' ? '上课已确认，课时已扣除' : '上课已记录，余额不足保留待补扣');
      this.setData({ lessonReason: '' });
    });
  },
  retrySettlement(event) { this.act(() => api.retrySettlement(event.currentTarget.dataset.id), '消课已重新处理，请核对状态'); }
});
