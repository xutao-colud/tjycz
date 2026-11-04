const api = require('../../utils/api');
const refunds = require('../../utils/refunds');
const ui = require('../../utils/growth');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const stateCodes = ['', 'REQUESTED', 'APPROVED', 'CONFIRMED', 'REJECTED', 'CANCELLED'];
const paymentCodes = ['WECHAT_OFFLINE', 'ALIPAY', 'CASH', 'BANK'];
definePage({
  data: { id: '', studentId: '', students: [], childNames: [], childIndex: 0, stateIndex: 0, stateNames: ['全部状态', '待审核', '待线下退款', '已退款', '已驳回', '已取消'], rows: [], sources: [], sourceNames: [], sourceIndex: 0, selectedSource: null, detail: null, compose: false, amountYuan: '', lessons: '', giftLessons: '', reason: '', processNote: '', paymentIndex: 0, paymentNames: ['线下微信', '支付宝', '现金', '银行转账'], paymentReference: '' },
  onLoad(query) { this.setData({ id: query.id || '', studentId: query.studentId || '' }); },
  async loadData() {
    if (this.data.teacher && !this.data.admin) throw new Error('退款记录仅向家长和授权管理员开放');
    if (this.data.id) {
      const detail = await refunds.detail(this.data.id);
      return { detail: refunds.decorate(detail), processNote: '', paymentReference: '' };
    }
    const students = await api.students({});
    const studentId = students.some(s => String(s.id) === String(this.data.studentId)) ? Number(this.data.studentId) : '';
    const [rows, sources] = await Promise.all([refunds.list({ studentId, status: stateCodes[this.data.stateIndex] }), refunds.sources({ studentId })]);
    const decorated = sources.map(s => Object.assign({}, s, { refundableText: format.amount(s.refundableAmount) }));
    const sourceIndex = this.data.sourceIndex < sources.length ? this.data.sourceIndex : 0;
    return {
      students, studentId, childNames: [this.data.admin ? '全部学生' : '全部孩子'].concat(students.map(s => s.name + ' · ' + s.campusName)), childIndex: studentId ? students.findIndex(s => s.id === studentId) + 1 : 0,
      rows: rows.map(refunds.decorate), sources: decorated, sourceNames: decorated.map(s => s.studentName + ' · ' + s.courseName + ' · 原单 #' + s.id), sourceIndex, selectedSource: decorated[sourceIndex] || null, detail: null
    };
  },
  child(event) {
    if (this.data.busy) return;
    const index = Number(event.detail.value);
    this.setData({ studentId: index ? this.data.students[index - 1].id : '', compose: false, sourceIndex: 0, reason: '', amountYuan: '', lessons: '', giftLessons: '' }); this.refresh();
  },
  filter(event) { if (!this.data.busy) { this.setData({ stateIndex: Number(event.detail.value) }); this.refresh(); } },
  source(event) { if (!this.data.busy) { const index = Number(event.detail.value); this.setData({ sourceIndex: index, selectedSource: this.data.sources[index] || null, amountYuan: '', lessons: '', giftLessons: '' }); } },
  payment(event) { if (!this.data.busy) this.setData({ paymentIndex: Number(event.detail.value) }); },
  toggleCompose() { if (!this.data.busy) this.setData({ compose: !this.data.compose }); },
  open(event) { if (!this.data.busy) { this.setData({ id: event.currentTarget.dataset.id, compose: false, actionError: '' }); this.refresh(); } },
  list() { if (!this.data.busy) { this.setData({ id: '', detail: null, actionError: '', processNote: '', paymentReference: '' }); this.refresh(); } },
  async create() {
    if (this.data.busy) return;
    const source = this.data.selectedSource;
    let amount, lessons, giftLessons;
    try { amount = refunds.cents(this.data.amountYuan); lessons = refunds.credits(this.data.lessons); giftLessons = refunds.credits(this.data.giftLessons); }
    catch (error) { toast(error.message); return; }
    if (!source || !this.data.reason.trim() || lessons + giftLessons <= 0) { toast('请选择原续费单并填写退回课时与申请原因'); return; }
    if (amount > source.refundableAmount || lessons > source.refundableLessons || giftLessons > source.refundableGiftLessons) { toast('超过原单剩余可退额度，请刷新核对'); return; }
    if (!await confirm('确认提交退款申请', '拟退 ¥' + format.amount(amount) + '，退回购买 ' + lessons + '、赠送 ' + giftLessons + ' 课时。提交后由机构审核，不会自动转账。')) return;
    const body = { renewalId: source.id, amount, lessons, giftLessons, reason: this.data.reason.trim() };
    let row;
    const created = await this.act(async () => { row = await refunds.create(ui.request(this, 'refund-create', body)); }, '申请已提交，等待审核', false);
    if (created) {
      this.setData({ id: row.id, compose: false, amountYuan: '', lessons: '', giftLessons: '', reason: '' });
      await this.refresh();
    }
  },
  async review(event) {
    if (this.data.busy || !this.data.detail) return;
    const approved = event.currentTarget.dataset.approved === '1'; const row = this.data.detail; const note = this.data.processNote.trim();
    if (!note) { toast('请填写审核意见'); return; }
    if (!await confirm(approved ? '通过并预留退款课时' : '驳回退款申请', row.studentName + ' · 拟退 ¥' + row.moneyText + '。' + note)) return;
    await this.act(() => refunds.review(row.id, ui.request(this, 'refund-review:' + row.id, { approved, note, expectedVersion: row.version })), approved ? '已通过，等待线下退款' : '已记录驳回意见');
  },
  async cancel() {
    if (this.data.busy || !this.data.detail) return;
    const row = this.data.detail, note = this.data.processNote.trim();
    if (!note) { toast('请填写取消原因'); return; }
    if (!await confirm('确认取消退款申请', '取消后释放原单额度及已预留课时。' + note)) return;
    await this.act(() => refunds.cancel(row.id, ui.request(this, 'refund-cancel:' + row.id, { note, expectedVersion: row.version })), '申请已取消');
  },
  async confirmRefund() {
    if (this.data.busy || !this.data.detail) return;
    const row = this.data.detail, note = this.data.processNote.trim(), reference = this.data.paymentReference.trim();
    if (!reference || !note) { toast('请填写退款流水号或签收凭证，以及确认说明'); return; }
    if (!await confirm('确认已经实际退款', '请核实已在线下向 ' + row.studentName + ' 家长退还 ¥' + row.moneyText + '。确认后扣减课时，记录不可修改；本操作不会自动转账。')) return;
    await this.act(() => refunds.confirm(row.id, ui.request(this, 'refund-confirm:' + row.id, { confirmed: true, paymentMethod: paymentCodes[this.data.paymentIndex], paymentReference: reference, note, expectedVersion: row.version })), '线下退款已确认，账本已更新');
  }
});
