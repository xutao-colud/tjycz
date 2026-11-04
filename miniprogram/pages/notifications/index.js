const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage } = require('../../utils/page');
const { selection } = require('../../utils/students');
definePage({
  data: { students: [], items: [] },
  async loadData() { const selected = await selection(this, false); const items = await api.request('GET', '/student-notifications', { studentId: selected.studentIds[0] }); return Object.assign({}, selected, { items: items.map(item => Object.assign({}, item, { createdText: format.time(item.createdAt) })) }); },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids }); this.refresh(); },
  read(event) { return this.act(() => api.request('POST', '/student-notifications/' + event.currentTarget.dataset.id + '/read', {}), '已标记已读'); }
});
