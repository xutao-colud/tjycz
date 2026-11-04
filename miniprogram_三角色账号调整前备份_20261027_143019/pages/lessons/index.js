const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  data: { students: [], accounts: [], ledger: [], renewals: [], settlements: [], accountIndex: 0, accountNames: ['全部课程账户'] },
  async loadData() {
    const selected = await selection(this, false);
    if (!selected.studentIds.length) return Object.assign({}, selected, { accounts: [], ledger: [], renewals: [], settlements: [] });
    const studentId = selected.studentIds[0];
    const [accounts, renewals, settlements] = await Promise.all([api.accounts({ studentId }), api.renewals({ studentId }), this.data.teacher ? api.settlements() : Promise.resolve([])]);
    const accountIndex = this.data.accountIndex > accounts.length ? 0 : this.data.accountIndex;
    const accountId = accountIndex ? accounts[accountIndex - 1].id : '';
    const ledger = await api.ledger({ studentId, accountId });
    return Object.assign({}, selected, { accounts: accounts.map(row => Object.assign({}, row, { low: Number(row.balance) <= Number(row.warningThreshold) })),
      accountIndex, accountNames: ['全部课程账户'].concat(accounts.map(row => row.courseName)), ledger: ledger.map(format.decorate),
      renewals: renewals.filter(row => !accountId || Number(row.accountId) === Number(accountId)).map(row => Object.assign(format.decorate(row), { money: format.amount(row.amount) })),
      settlements: settlements.filter(row => Number(row.studentId) === Number(studentId) && row.status === 'PENDING').map(format.decorate) });
  },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids, accountIndex: 0 }); this.refresh(); },
  account(event) { this.setData({ accountIndex: Number(event.detail.value) }); this.refresh(); },
  retrySettlement(event) { this.act(() => api.retrySettlement(event.currentTarget.dataset.id), '消课已重新处理，请核对状态'); }
});
