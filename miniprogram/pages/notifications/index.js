const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  data: { students: [], items: [], complaintNotices: [] },
  async loadData() { const selected = await selection(this, false); const [items, notices] = await Promise.all([api.request('GET', '/student-notifications', { studentId: selected.studentIds[0] }), this.data.user.role === 'TEACHER' ? Promise.resolve([]) : api.request('GET', '/complaint-notices')]); return Object.assign({}, selected, { complaintNotices: notices.map(n => Object.assign({}, n, { createdText: format.time(n.updatedAt) })), items: items.map(item => Object.assign({}, item, { createdText: format.time(item.createdAt) })) }); },
  complaint(event) { wx.navigateTo({ url: '/pages/complaints/index?id=' + event.currentTarget.dataset.id }); },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids }); this.refresh(); },
  read(event) { return this.act(() => api.request('POST', '/student-notifications/' + event.currentTarget.dataset.id + '/read', {}), '已标记已读'); }
});
