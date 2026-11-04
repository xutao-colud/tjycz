const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  data: { students: [], items: [], balance: 0, rules: [], amount: '', reason: '', requestId: '', requestPayload: null },
  async loadData() {
    const selected = await selection(this, false);
    const [items, settings] = await Promise.all([selected.studentIds.length ? api.points({ studentId: selected.studentIds[0] }) : Promise.resolve([]), api.settings()]);
    const student = selected.students.find(row => Number(row.id) === Number(selected.studentIds[0]));
    return Object.assign({}, selected, { items: items.map(format.decorate), balance: student ? student.points : 0, rules: (settings.pointsRules || []).map(rule => ({ name: format.label(rule.event), points: rule.points })) });
  },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids, requestId: '', requestPayload: null }); this.refresh(); },
  async submit() {
    const amount = this.data.amount.trim() === '' ? NaN : Number(this.data.amount);
    if (!this.data.studentIds.length || !Number.isInteger(amount) || amount < -100 || amount > 100 || amount === 0 || !this.data.reason.trim()) { toast('请选择学生，填写 -100 至 100 的非零整数和调整原因'); return; }
    if (!await confirm('确认积分调整', '本次调整 ' + (amount > 0 ? '+' : '') + amount + ' 分。原因：' + this.data.reason.trim())) return;
    const payload = { studentId: this.data.studentIds[0], amount, reason: this.data.reason.trim() };
    const fingerprint = JSON.stringify(payload);
    if (!this.data.requestId || this.data.requestPayload !== fingerprint) this.setData({ requestId: 'mp-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12), requestPayload: fingerprint });
    await this.act(async () => {
      await api.adjustPoints(Object.assign({}, payload, { requestId: this.data.requestId }));
      this.setData({ amount: '', reason: '', requestId: '', requestPayload: null });
    }, '积分调整已入账');
  }
});
