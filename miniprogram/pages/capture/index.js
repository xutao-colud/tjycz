const api = require('../../utils/api');
const { definePage, toast } = require('../../utils/page');
const { selection } = require('../../utils/students');
const { choosePhoto, requestId } = require('../../utils/teaching');
definePage({
  teacherOnly: true,
  data: { students: [], subjects: ['语文', '数学', '英语'], subjectIndex: 1, kinds: ['错题本', '作业记录'], kindIndex: 0, question: '', photo: null, photoUrl: '', note: '', saved: false, requestId: '', fingerprint: '' },
  onLoad(query) { this.setData({ kindIndex: query.kind === 'HOMEWORK' ? 1 : 0 }); },
  loadData() { return selection(this, false); },
  pickStudents(event) { if (this.data.busy) return; this.setData({ studentIds: event.detail.ids, photo: null, photoUrl: '', question: '', note: '', saved: false, requestId: '' }); },
  choose(event) { this.setData({ [event.currentTarget.dataset.field]: Number(event.detail.value), saved: false }); },
  async snap() {
    const student = this.data.students.find(s => Number(s.id) === Number(this.data.studentIds[0]));
    if (!student) { toast('请先选择学生'); return; }
    await this.act(async () => {
      const photo = await choosePhoto(this, student.campusId);
      const result = await api.request('POST', '/teaching-capture/recognize', { studentId: student.id, photoFileId: photo.id });
      if (this._unloaded) return;
      this.setData({ photo, photoUrl: api.fileUrl(photo), note: result.status === 'NOT_CONFIGURED' ? '照片已上传。此处可手工校对；整页分题请进入拍照识题。' : result.message, question: result.text || '', saved: false, requestId: '' });
    }, '', false);
  },
  async save() {
    if (!this.data.photo || !this.data.studentIds.length || this.data.saved) return;
    const body = { studentId: this.data.studentIds[0], photoFileId: this.data.photo.id, kind: this.data.kindIndex ? 'HOMEWORK' : 'MISTAKE', subject: this.data.subjects[this.data.subjectIndex], questionText: this.data.question.trim() };
    const fingerprint = JSON.stringify(body);
    if (!this.data.requestId || this.data.fingerprint !== fingerprint) this.setData({ requestId: requestId(), fingerprint });
    await this.act(async () => { await api.request('POST', '/teaching-capture/save', Object.assign({}, body, { requestId: this.data.requestId })); this.setData({ saved: true }); }, this.data.kindIndex ? '作业照片已提交，等待批改' : '题目已加入错题本', false);
  },
  next() { this.setData({ photo: null, photoUrl: '', question: '', note: '', saved: false, requestId: '' }); },
  view() { wx.navigateTo({ url: '/pages/' + (this.data.kindIndex ? 'homework' : 'mistakes') + '/index?studentId=' + this.data.studentIds[0] }); }
});
