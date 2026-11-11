const api = require('../../utils/api');
const session = require('../../utils/session');
const format = require('../../utils/format');
const { definePage, toast } = require('../../utils/page');
const { choosePhoto, requestId } = require('../../utils/teaching');
const names = { OPEN: '待处理', PROCESSING: '处理中', REPLIED: '已回复', CLOSED: '已办结' };
const decorate = row => Object.assign({}, row, { statusText: names[row.status], createdText: format.time(row.createdAt) });
definePage({
  data: { students: [], childNames: [], childIndex: 0, studentId: '', selectedId: '', detail: null, items: [], showForm: false, title: '', content: '', contact: '', photos: [], reply: '', actionIndex: 0, actionNames: [], requestKey: '', fingerprint: '' },
  onLoad(query) { this.setData({ selectedId: query.id || '', studentId: query.studentId || '' }); },
  async loadData() {
    const parent = !session.isTeacher();
    if (!parent && !session.isAdmin()) throw new Error('投诉仅限提交家长、校长及管理员查看');
    const [students, items, detail] = await Promise.all([api.students({}), api.request('GET', '/complaints'), this.data.selectedId ? api.request('GET', '/complaints/' + this.data.selectedId) : null]);
    const index = Math.max(0, students.findIndex(s => Number(s.id) === Number(this.data.studentId)));
    if (detail) await api.request('POST', '/complaints/' + detail.id + '/read', {});
    return { students, childNames: students.map(s => s.name + ' · ' + s.className), childIndex: index, studentId: students[index] ? students[index].id : '', items: items.map(decorate), detail: detail ? Object.assign(decorate(detail), { logs: detail.logs.map(l => Object.assign({}, l, { createdText: format.time(l.createdAt) })) }) : null,
      actionNames: parent ? ['补充反馈'] : detail && detail.status === 'CLOSED' ? ['重新受理', '内部处理记录'] : detail && detail.status === 'REPLIED' ? ['回复家长', '内部处理记录', '办结', '重新受理'] : ['回复家长', '内部处理记录', '开始处理'], actionIndex: 0 };
  },
  async chooseChild(event) { if (this.data.busy) return; const i = Number(event.detail.value); this.setData({ childIndex: i, studentId: this.data.students[i].id, photos: [] }); },
  newComplaint() { if (this.data.busy) return; this.setData({ selectedId: '', detail: null, showForm: !this.data.showForm, photos: [], reply: '', requestKey: '' }); },
  open(event) { if (this.data.busy) return; this.setData({ selectedId: event.currentTarget.dataset.id, detail: null, showForm: false, photos: [], reply: '', requestKey: '' }); return this.refresh(); },
  back() { if (this.data.busy) return; this.setData({ selectedId: '', detail: null, showForm: false, photos: [], reply: '' }); return this.refresh(); },
  action(event) { if (!this.data.busy) this.setData({ actionIndex: Number(event.detail.value) }); },
  async photo() {
    const sid = this.data.detail ? this.data.detail.studentId : this.data.studentId, student = this.data.students.find(s => Number(s.id) === Number(sid));
    if (!student || this.data.photos.length >= 6 || this.data.busy) return;
    await this.act(async () => { const photo = await choosePhoto(this, student.campusId); if (!this._unloaded) this.setData({ photos: this.data.photos.concat(photo) }); }, '', false);
  },
  removePhoto(event) { if (!this.data.busy) this.setData({ photos: this.data.photos.filter(p => Number(p.id) !== Number(event.detail.id)) }); },
  key(body) { const fingerprint = JSON.stringify(body); if (!this.data.requestKey || this.data.fingerprint !== fingerprint) this.setData({ requestKey: requestId(), fingerprint }); return this.data.requestKey; },
  async submit() {
    if (this.data.busy) return;
    if (!this.data.studentId || !this.data.title.trim() || !this.data.content.trim()) { toast('请选择孩子，填写标题和具体情况'); return; }
    const body = { studentId: this.data.studentId, title: this.data.title.trim(), content: this.data.content.trim(), contact: this.data.contact.trim(), attachmentIds: this.data.photos.map(p => p.id) };body.requestId = this.key(body);
    await this.act(async () => { const saved = await api.request('POST', '/complaints', body); if (!this._unloaded) this.setData({ selectedId: saved.id, showForm: false, title: '', content: '', contact: '', photos: [], requestKey: '' }); }, '已提交，仅校长或管理员处理');
  },
  async send() {
    const d = this.data.detail; if (!d || this.data.busy) return;
    if (!this.data.reply.trim()) { toast('请填写补充情况或处理说明'); return; }
    const selected = this.data.actionNames[this.data.actionIndex], action = !this.data.teacher ? 'SUPPLEMENT' : selected === '回复家长' ? 'REPLY' : selected === '内部处理记录' ? 'NOTE' : 'STATUS';
    const body = { action, text: this.data.reply.trim(), expectedVersion: d.version, attachmentIds: this.data.photos.map(p => p.id) };
    if (action === 'STATUS') body.status = selected === '办结' ? 'CLOSED' : 'PROCESSING';
    body.requestId = this.key(body);
    await this.act(async () => { await api.request('POST', '/complaints/' + d.id + '/actions', body); if (!this._unloaded) this.setData({ reply: '', photos: [], requestKey: '' }); }, '处理记录已保存');
  }
});
