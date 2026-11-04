const api = require('../../utils/api');
const session = require('../../utils/session');
const privacy = require('../../utils/privacy');
const scan = require('../../utils/scan');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
const { choosePhoto, requestId } = require('../../utils/teaching');
const stateNames = { QUEUED: '等待扫描', RECOGNIZING: '正在识别与分题', READY: '待校对选择', FAILED: '扫描未完成' };
definePage({
  teacherOnly: true,
  data: { students: [], subjects: ['语文', '数学', '英语'], subjectIndex: 1, service: null, mediaConsent: null, job: null, jobId: '', pageUrl: '', history: [], historyOpen: false, questions: [], activeQuestion: null, activeIndex: 0, selectedIndices: [], selectedCount: 0, dirty: false, adjusting: false, editRegion: 0, uploading: false, results: [], pendingPhoto: null, createRequestId: '' },
  onLoad(query) { this.setData({ jobId: query.jobId || '' }); },
  async loadData() {
    const selected = await selection(this, false), sid = selected.studentIds[0];
    let [service, history, job, mediaConsent] = await Promise.all([
      api.request('GET', '/teaching-capture/scan-status'), sid ? api.request('GET', '/teaching-capture/jobs', { studentId: sid }) : [],
      this.data.jobId ? api.request('GET', '/teaching-capture/jobs/' + this.data.jobId) : null,
      sid ? privacy.consents(sid) : null
    ]);
    if (job && !selected.students.some(s => Number(s.id) === Number(job.studentId))) throw new Error('当前账号不能查看该孩子的扫描记录');
    if (job && Number(job.studentId) !== Number(sid)) [history, mediaConsent] = await Promise.all([api.request('GET', '/teaching-capture/jobs', { studentId: job.studentId }), privacy.consents(job.studentId)]);
    const patch = Object.assign({}, selected, { service, mediaConsent, history: history.map(row => Object.assign({}, row, { statusText: stateNames[row.status], photoUrl: api.fileUrl(row.sourcePhoto), dateText: String(row.createdAt).replace('T', ' ').slice(0, 16) })) });
    if (job) {
      patch.studentIds = [Number(job.studentId)];
      patch.pageUrl = api.fileUrl(job.pagePhoto || job.sourcePhoto);
      if (!this.data.dirty || Number(this.data.jobId) !== Number(job.id)) Object.assign(patch, this.jobPatch(job, this.data.selectedIndices));
    }
    return patch;
  },
  afterLoad() { this.schedulePoll(); },
  onHide() { this._visible = false; clearTimeout(this._poll); },
  onUnload() { this._visible = false; this._unloaded = true; clearTimeout(this._poll); },
  jobPatch(job, selected) {
    const questions = job.questions || [], active = Math.min(this.data.activeIndex || 0, Math.max(0, questions.length - 1));
    return Object.assign({ job, jobId: job.id, pageUrl: api.fileUrl(job.pagePhoto || job.sourcePhoto), subjectIndex: Math.max(0, this.data.subjects.indexOf(job.subject)), dirty: false, adjusting: false, selectedIndices: selected || [] }, scan.decorate(job, selected || [], active), { activeIndex: active });
  },
  schedulePoll() {
    clearTimeout(this._poll);
    if (!this._visible || this._unloaded || !this.data.job || !['QUEUED', 'RECOGNIZING'].includes(this.data.job.status)) return;
    const context = session.context(), id = this.data.job.id;
    if (this._polledId !== id) { this._polledId = id; this._pollAttempts = 0; }
    const delay = this._pollAttempts++ < 12 ? 250 : 1000;
    this._poll = setTimeout(async () => {
      try {
        const job = await api.request('GET', '/teaching-capture/jobs/' + id);
        if (!this._visible || this._unloaded || !session.isCurrentContext(context) || Number(this.data.jobId) !== Number(id)) return;
        this.setData(Object.assign({ actionError: '' }, this.jobPatch(job, []))); this.schedulePoll();
      } catch (error) {
        if (this._visible && !this._unloaded && session.isCurrentContext(context)) this.setData({ actionError: error.message });
      }
    }, delay);
  },
  async reloadJob() {
    if (!this.data.jobId || this.data.busy) return;
    if (this.data.dirty && !await confirm('重新载入任务？', '尚未保存的校对会放弃，并清空勾选，请按最新题框重新确认。')) return;
    const context = session.context();
    await this.act(async () => { const job = await api.request('GET', '/teaching-capture/jobs/' + this.data.jobId); if (session.isCurrentContext(context) && !this._unloaded) { this.setData(this.jobPatch(job, [])); this.schedulePoll(); } }, '', false);
  },
  async reloadService() {
    const context = session.context();
    await this.act(async () => { const service = await api.request('GET', '/teaching-capture/scan-status'); if (session.isCurrentContext(context) && !this._unloaded) this.setData({ service }); }, '', false);
  },
  async checkMediaConsent(studentId) {
    const context = session.context(), consent = await privacy.consents(studentId);
    if (!session.isCurrentContext(context) || this._unloaded || Number(this.data.studentIds[0]) !== Number(studentId)) return false;
    this.setData({ mediaConsent: consent });
    return !!consent.effectiveMedia;
  },
  async recheckConsent() {
    if (this.data.uploading || !this.data.studentIds.length) return;
    await this.act(async () => { if (await this.checkMediaConsent(this.data.studentIds[0])) toast('授权已生效，可以继续扫描'); }, '', false);
  },
  openMediaConsent() {
    const id = this.data.studentIds[0];
    if (!id || this.data.busy || this.data.uploading || this._unloaded) return;
    wx.navigateTo({ url: '/pages/privacy/index?studentId=' + id });
  },
  async pickStudents(event) {
    if (this.data.busy || this.data.uploading) return;
    if (this.data.dirty && !await confirm('切换孩子？', '尚未保存的题框调整将放弃，已保存的扫描任务可从历史记录继续。')) return;
    clearTimeout(this._poll);
    this.setData({ studentIds: event.detail.ids, mediaConsent: null, jobId: '', job: null, questions: [], activeQuestion: null, selectedIndices: [], selectedCount: 0, dirty: false, adjusting: false, results: [], pendingPhoto: null, createRequestId: '', actionError: '' });
    await this.refresh();
  },
  subject(event) { if (!this.data.job && !this.data.busy) this.setData({ subjectIndex: Number(event.detail.value) }); },
  async start() {
    const child = this.data.students.find(s => Number(s.id) === Number(this.data.studentIds[0]));
    if (!child || this.data.busy || this.data.uploading) return;
    if (this.data.dirty && !await confirm('重新扫描？', '尚未保存的题框调整将放弃。')) return;
    const context = session.context(); this.setData({ uploading: true, actionError: '' });
    try {
      if (!await this.checkMediaConsent(child.id)) return;
      const photo = await choosePhoto(this, child.campusId);
      if (!session.isCurrentContext(context) || this._unloaded) return;
      this.setData({ pendingPhoto: photo, createRequestId: requestId() });
      await this.createScan();
    } catch (error) { if (session.isCurrentContext(context) && !this._unloaded) this.setData({ actionError: error.message }); }
    finally { if (session.isCurrentContext(context) && !this._unloaded) this.setData({ uploading: false }); }
  },
  async createScan() {
    const photo = this.data.pendingPhoto, child = this.data.students.find(s => Number(s.id) === Number(this.data.studentIds[0]));
    if (!photo || !child) return;
    const context = session.context();
    await this.act(async () => {
      if (!await this.checkMediaConsent(child.id)) return;
      const job = await api.request('POST', '/teaching-capture/jobs', { studentId: child.id, photoFileId: photo.id, subject: this.data.subjects[this.data.subjectIndex], requestId: this.data.createRequestId });
      if (!session.isCurrentContext(context) || this._unloaded) return;
      this.setData(Object.assign(this.jobPatch(job, []), { results: [], pendingPhoto: null })); this.schedulePoll();
    }, '', false);
  },
  async retryScan() {
    const id = this.data.jobId; if (!id) return;
    const context = session.context();
    await this.act(async () => { if (!await this.checkMediaConsent(this.data.studentIds[0])) return; const job = await api.request('POST', '/teaching-capture/jobs/' + id + '/retry', {}); if (session.isCurrentContext(context) && !this._unloaded) { this.setData(this.jobPatch(job, [])); this.schedulePoll(); } }, '', false);
  },
  async rescan() {
    const job = this.data.job;
    if (!job || job.status !== 'READY' || !job.sourcePhoto || this.data.busy || this.data.uploading) return;
    const context = session.context();
    if (!await confirm('用原图重新识别？', '将创建新的扫描记录。当前记录中的校对及已入库题目继续保留。')) return;
    if (!session.isCurrentContext(context) || this._unloaded || this.data.jobId !== job.id) return;
    this.setData({ pendingPhoto: job.sourcePhoto, createRequestId: requestId(), actionError: '' });
    await this.createScan();
  },
  focus(event) { const index = Number(event.currentTarget.dataset.index); if (!this.data.questions[index] || this.data.busy) return; this.setData(Object.assign({ activeIndex: index, editRegion: 0, adjusting: false }, scan.decorate({ questions: this.data.questions }, this.data.selectedIndices, index))); },
  toggleSelected() {
    const index = this.data.activeIndex, q = this.data.questions[index]; if (!q || q.importedMistakeId || this.data.busy) return;
    const ids = this.data.selectedIndices.includes(index) ? this.data.selectedIndices.filter(i => i !== index) : this.data.selectedIndices.concat(index);
    this.setData(Object.assign({ selectedIndices: ids }, scan.decorate({ questions: this.data.questions }, ids, index)));
  },
  editText(event) {
    const index = this.data.activeIndex, questions = this.data.questions.slice(), q = questions[index];
    if (!q || q.importedMistakeId || this.data.busy) return;
    questions[index] = Object.assign({}, q, { [event.currentTarget.dataset.field]: event.detail.value }); this.changeQuestions(questions);
  },
  changeQuestions(questions, active) { const index = active === undefined ? this.data.activeIndex : active; this.setData(Object.assign({ dirty: true, activeIndex: index, results: [] }, scan.decorate({ questions }, this.data.selectedIndices, index))); },
  adjust() { if (!this.data.activeQuestion || this.data.activeQuestion.importedMistakeId || this.data.busy) return; this.setData({ adjusting: !this.data.adjusting, editRegion: this.data.activeQuestion.regions.length - 1 }); },
  region(event) { this.setData({ editRegion: Number(event.currentTarget.dataset.index) }); },
  dragStart(event) {
    if (!this.data.adjusting || this.data.busy || this.data.activeQuestion.importedMistakeId) return;
    const touch = event.touches[0], regionIndex = Number(event.currentTarget.dataset.region), mode = event.currentTarget.dataset.mode, active = this.data.activeIndex;
    if (!touch) return;
    const original = Object.assign({}, this.data.activeQuestion.regions[regionIndex]); this._drag = null;
    wx.createSelectorQuery().in(this).select('.scan-canvas').boundingClientRect(rect => { if (rect && rect.width && rect.height && !this._unloaded && this.data.adjusting && this.data.activeIndex === active) this._drag = { x: touch.clientX, y: touch.clientY, rect, original, regionIndex, resize: mode === 'resize' }; }).exec();
  },
  dragMove(event) {
    const drag = this._drag, touch = event.touches[0]; if (!drag || !touch || !this.data.adjusting) return;
    const questions = this.data.questions.slice(), q = Object.assign({}, questions[this.data.activeIndex]), regions = q.regions.slice();
    regions[drag.regionIndex] = scan.moveRegion(drag.original, (touch.clientX - drag.x) / drag.rect.width, (touch.clientY - drag.y) / drag.rect.height, drag.resize);
    q.regions = regions; questions[this.data.activeIndex] = q; this.changeQuestions(questions);
  },
  dragEnd() { this._drag = null; },
  addQuestion() {
    if (!this.data.job || this.data.job.status !== 'READY' || this.data.questions.length >= 40 || this.data.busy) return;
    const questions = this.data.questions.concat({ id: null, displayNumber: String(this.data.questions.length + 1), groupName: '手工框题', questionText: '', regions: [{ x: .05, y: .25, width: .9, height: .18 }], warnings: ['请拖动边框选择完整题目'], importedMistakeId: null });
    this.changeQuestions(questions, questions.length - 1); this.setData({ adjusting: true, editRegion: 0 });
  },
  split() {
    const q = this.data.activeQuestion; if (!q || q.importedMistakeId || this.data.questions.length >= 40 || this.data.busy) return;
    const questions = this.data.questions.slice(), index = this.data.activeIndex, regions = q.regions.map(r => ({ x: r.x, y: r.y, width: r.width, height: r.height })), body = regions.pop();
    if (body.height < .06) { toast('范围过小，请先调大题框'); return; }
    const first = Object.assign({}, q, { questionText: '', displayNumber: q.displayNumber + '上', regions: regions.concat(Object.assign({}, body, { height: body.height / 2 })) });
    const second = Object.assign({}, q, { id: null, questionText: '', displayNumber: q.displayNumber + '下', regions: regions.concat(Object.assign({}, body, { y: body.y + body.height / 2, height: body.height / 2 })) });
    questions.splice(index, 1, first, second); this.setData({ selectedIndices: [] }); this.changeQuestions(questions); toast('已拆成两题，请分别调整范围并校对题干');
  },
  async mergeNext() {
    const i = this.data.activeIndex, a = this.data.questions[i], b = this.data.questions[i + 1];
    if (!a || !b || a.importedMistakeId || b.importedMistakeId || this.data.busy) return;
    if (!await confirm('与下一题合并？', '请确认它们属于同一道题。合并后可继续调整题框和题干。')) return;
    const regions = a.regions.concat(b.regions).filter((r, index, all) => all.findIndex(v => v.x === r.x && v.y === r.y && v.width === r.width && v.height === r.height) === index);
    if (regions.length > 8) { toast('合并范围过多，请先缩减题框'); return; }
    const questions = this.data.questions.slice(); questions.splice(i, 2, Object.assign({}, a, { regions, questionText: (a.questionText + '\n' + b.questionText).slice(0, 10000) })); this.setData({ selectedIndices: [] }); this.changeQuestions(questions);
  },
  async removeQuestion() {
    if (!this.data.activeQuestion || this.data.activeQuestion.importedMistakeId || this.data.questions.length < 2 || this.data.busy) return;
    if (!await confirm('移除这个候选题？', '原始照片继续保留，这个题框不会加入错题本。')) return;
    const questions = this.data.questions.slice(); questions.splice(this.data.activeIndex, 1); this.setData({ selectedIndices: [] }); this.changeQuestions(questions, Math.min(this.data.activeIndex, questions.length - 1));
  },
  async persist() {
    if (!this.data.dirty) return true;
    const context = session.context(), id = this.data.jobId, selected = this.data.selectedIndices.slice();
    const job = await api.request('PATCH', '/teaching-capture/jobs/' + id + '/questions', { expectedVersion: this.data.job.version, questions: scan.payload(this.data.questions) });
    if (!session.isCurrentContext(context) || this._unloaded) return false;
    this.setData(this.jobPatch(job, selected)); return true;
  },
  async saveEdits() { await this.act(async () => { if (await this.persist()) toast('题目校对已保存'); }, '', false); },
  async importSelected() {
    if (!this.data.selectedCount || this.data.busy) return;
    const context = session.context();
    await this.act(async () => {
      if (!await this.persist()) return;
      const ids = this.data.questions.filter(q => q.selected && !q.importedMistakeId).map(q => q.id);
      const result = await api.request('POST', '/teaching-capture/jobs/' + this.data.jobId + '/import', { expectedVersion: this.data.job.version, questionIds: ids });
      if (!session.isCurrentContext(context) || this._unloaded) return;
      const failed = result.results.filter(r => !r.success).map(r => r.id), job = await api.request('GET', '/teaching-capture/jobs/' + this.data.jobId);
      if (!session.isCurrentContext(context) || this._unloaded) return;
      this.setData(Object.assign(this.jobPatch(job, job.questions.map((q, i) => failed.includes(q.id) ? i : -1).filter(i => i >= 0)), { results: result.results }));
      toast(failed.length ? '部分题目未入库，可只重试失败项' : '所选题目已加入错题本');
    }, '', false);
  },
  async history() {
    this.setData({ historyOpen: !this.data.historyOpen });
    if (!this.data.historyOpen || this.data.busy || !this.data.studentIds.length) return;
    const context = session.context(), sid = this.data.studentIds[0];
    await this.act(async () => { const rows = await api.request('GET', '/teaching-capture/jobs', { studentId: sid }); if (session.isCurrentContext(context) && !this._unloaded) this.setData({ history: rows.map(row => Object.assign({}, row, { statusText: stateNames[row.status], photoUrl: api.fileUrl(row.sourcePhoto), dateText: String(row.createdAt).replace('T', ' ').slice(0, 16) })) }); }, '', false);
  },
  async openHistory(event) {
    if (this.data.busy || this.data.uploading) return;
    if (this.data.dirty && !await confirm('打开其他扫描记录？', '尚未保存的调整将放弃。')) return;
    this.setData({ jobId: Number(event.currentTarget.dataset.id), selectedIndices: [], results: [] }); await this.refresh();
  },
  viewBook() { wx.navigateTo({ url: '/pages/mistakes/index?studentId=' + this.data.studentIds[0] }); },
  manual() { wx.navigateTo({ url: '/pages/capture/index?studentId=' + (this.data.studentIds[0] || '') }); },
  async preview() {
    const photo = this.data.job && (this.data.job.pagePhoto || this.data.job.sourcePhoto); if (!photo) return;
    const context = session.context();
    await this.act(async () => { const file = await api.request('GET', '/files/' + photo.id + '/access'); if (session.isCurrentContext(context) && !this._unloaded) { const url = api.fileUrl(file); this.setData({ pageUrl: url }); wx.previewImage({ urls: [url], current: url }); } }, '', false);
  }
});
