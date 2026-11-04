const api = require('../../utils/api');
const ui = require('../../utils/growth');
const format = require('../../utils/format');
const session = require('../../utils/session');
const { definePage, toast } = require('../../utils/page');
const filters = ['', 'HOMEWORK', 'ATTENDANCE', 'FITNESS', 'COMMENT', 'FOLLOWUP', 'SAFETY'];
definePage({
  data: { today: format.today(), compareOpen: false, works: [], workLabels: [], beforeFile: null, afterFile: null, beforeIndex: -1, afterIndex: -1, comparisonText: '', reminder: null,  historyPage: false, recent: [], actions: [], corrections: 0, studentId: '', student: null, students: [], childNames: [], childIndex: 0, dateItems: [], timeline: [], allEvents: [], filters: ['全部', '作业', '到离校', '体能', '点评', '关注与沟通', '请假与接送'], filterIndex: 0, homework: [], points: [], fitness: [], concerns: [], openConcernCount: 0, summary: null, fitnessPause: null, readOnly: false, teachersText: '', metrics: [], pickupSummary: null, applications: [{ page: 'homework', name: '学习作业', icon: 'homework' }, { page: 'mistakes', name: '错题成长', icon: 'mistakes' }, { page: 'fitness', name: '体能记录', icon: 'fitness' }, { page: 'safety', name: '接送管理', icon: 'attendance' }] },
  onLoad(query) { this.setData({ studentId: query.studentId || '', historyPage: query.history === '1' }); },
  async openComparison() {
    const context = session.context();
    await this.act(async () => {
      const works = await api.request('GET', '/growth/comparison-works', { studentId: this.data.studentId });
      if (this._unloaded || !session.isCurrentContext(context)) return;
      this.setData({ compareOpen: true, works, workLabels: works.map(work => work.workDate + ' · ' + work.title), beforeIndex: -1, afterIndex: -1, beforeFile: null, afterFile: null });
    }, '', false);
  },
  closeComparison() { this.setData({ compareOpen: false, comparisonText: '', works: [], workLabels: [], beforeFile: null, afterFile: null, beforeIndex: -1, afterIndex: -1 }); },
  selectWork(event) {
    const side = event.currentTarget.dataset.side, index = Number(event.detail.value), work = this.data.works[index];
    if (!work || !['before', 'after'].includes(side)) return;
    const context = session.context();
    this.act(async () => {
      const file = await api.request('GET', '/files/' + work.id + '/access');
      if (this._unloaded || !session.isCurrentContext(context)) return;
      this.setData({ [side + 'Index']: index, [side + 'File']: file });
    }, '', false);
  },
  async saveComparison() {
    const { beforeFile, afterFile, studentId } = this.data;
    const text = (this.data.comparisonText || '').trim();
    if (!beforeFile || !afterFile || beforeFile.id === afterFile.id || !text) { toast('请选择两张不同作品图并填写一句说明'); return; }
    const context = session.context();
    await this.act(async () => {
      await api.request('POST', '/growth/comparisons', ui.request(this, 'comparison', { studentId, beforeId: beforeFile.id, afterId: afterFile.id, text }));
      if (!this._unloaded && session.isCurrentContext(context)) this.closeComparison();
    }, '成长对比已保存');
  },
  async loadData() {
    const historyPage = this.query.history === '1';
    const g = await api.growth({ date: this.data.date, studentId: this.data.studentId, compact: !historyPage });
    const dateItems = ui.dates(this.data.date);
    if (!g.student) return { student: null, students: [], dateItems, timeline: [], metrics: [], historyPage };
    const lowBalances = (g.accounts || []).filter(a => Number(a.availableBalance === undefined ? a.balance : a.availableBalance) <= Number(a.warningThreshold));
    const s = g.student, homework = (g.homework || []).map(h => Object.assign(ui.decorate(h), { later: !!h.updatedAt && h.updatedAt.slice(0, 10) > h.date }));
    const seconds = (g.fitness || []).filter(f => f.confirmed).reduce((sum, f) => sum + Number(f.duration || 0), 0);
    const pickup = g.pickup || {};
    const attendanceAction = s.attendanceStatus === 'LEFT' ? 'CHECK_OUT' : 'CHECK_IN';
    const record = (g.timeline || []).find(row => row.eventType === 'ATTENDANCE' && row.businessDate === this.data.date && row.action === attendanceAction && String(row.detail || '').startsWith('老师接送拍照'));
    const pickupSummary = { label: s.attendanceStatus === 'LEFT' ? '已离校' : s.attendanceStatus === 'PRESENT' ? '已到校' : '待签到',
      actor: record && record.actorName, alertCount: Number(pickup.openAlertCount || 0) };
    const allEvents = (g.timeline || []).map(e => Object.assign(ui.decorate(e), { icon: e.eventType === 'ATTENDANCE' ? 'attendance' : e.eventType === 'HOMEWORK' ? 'homework' : e.eventType === 'FITNESS' ? 'fitness' : 'summaries', later: !!e.businessDate && !!e.occurredAt && e.businessDate !== e.occurredAt.slice(0, 10) }));
    const seen = new Set();
    const latest = allEvents.filter(row => row.occurredAt).reverse().concat(allEvents.filter(row => !row.occurredAt));
    const recent = latest.filter(row => {
      if (!['ATTENDANCE', 'HOMEWORK', 'FITNESS', 'COMMENT'].includes(row.eventType)) return false;
      const key = row.eventType === 'HOMEWORK' ? 'homework:' + row.sourceId : row.key;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    }).slice(0, 3);
    const active = row => ['OPEN', 'IN_PROGRESS'].includes(row.status);
    const actions = (g.concerns || []).filter(row => active(row) && row.needsParent && !row.parentCompleted)
      .concat((g.messages || []).filter(row => active(row) && row.type !== 'REMINDER' && row.lastActorRole && row.lastActorRole !== 'PARENT'));
    const reminder = this.data.date === format.today() && (g.messages || []).find(row => row.type === 'REMINDER' && active(row) && row.lastActorRole !== 'PARENT');
    return Object.assign({}, g, { historyPage, today: format.today(), recent, actions, reminder: reminder || null, corrections: homework.filter(row => row.status === 'CORRECTION').length, studentId: s.id, lowBalances, student: Object.assign(format.decorate(s), { avatarUrl: s.avatar ? api.fileUrl(s.avatar) : '/assets/icons/students.png', checkIn: ui.clock(s.checkInTime), checkOut: s.attendanceStatus === 'ON_LEAVE' ? '整日请假不消课' : s.checkOutTime ? ui.clock(s.checkOutTime) : s.attendanceStatus === 'LEFT' ? '时间未记录' : '尚未离校' }),
      childNames: g.students.map(c => c.name + ' · ' + c.campusName), childIndex: g.students.findIndex(c => c.id === s.id), dateItems, homework, allEvents, pickupSummary, timeline: this.filtered(allEvents),
      teachersText: (g.teachers || []).map(t => t.name).join(' / ') || '尚未分配', concerns: (g.concerns || []).map(ui.decorate),
      metrics: [
        { id: 'homework', name: '作业进度', value: homework.filter(h => h.status === 'COMPLETED').length + '/' + homework.length, note: homework.some(h => h.later) ? '含后续处理' : '已批改完成', icon: 'homework', tone: 'blue' },
        { id: 'points', name: '当日积分', value: (Number(g.pointsDelta) > 0 ? '+' : '') + Number(g.pointsDelta || 0), note: '实际入账净额', icon: 'points', tone: 'orange' },
        { id: 'fitness', name: '体能活动', value: seconds ? Math.round(seconds / 60 * 10) / 10 + '分钟' : g.fitnessPause ? '因故暂停' : '未记录', note: g.fitnessPause ? g.fitnessPause.reason : '已确认时长', icon: 'fitness', tone: 'green' },
        { id: 'attendance', name: '到校 / 离校', value: s.checkInTime ? ui.clock(s.checkInTime) : s.attendanceStatus === 'ON_LEAVE' ? '已请假' : s.attendanceStatus !== 'NOT_ARRIVED' ? '时间未记录' : '未到校', note: s.attendanceStatus === 'ON_LEAVE' ? '整日请假不消课' : s.checkOutTime ? ui.clock(s.checkOutTime) : s.attendanceStatus === 'LEFT' ? '时间未记录' : '尚未离校', icon: 'attendance', tone: 'purple' }
      ] });
  },
  toggleHistory() { wx.navigateTo({ url: '/pages/growth/index?history=1&date=' + this.data.date + '&studentId=' + this.data.studentId }); },
  filtered(rows) { return rows.filter(e => !filters[this.data.filterIndex] || e.eventType === filters[this.data.filterIndex]); },
  chooseDate(event) { this.changeDate({ detail: { value: event.currentTarget.dataset.date } }); },
  changeDate(event) { if (this.data.busy) return; this.closeComparison(); this.setData({ date: event.detail.value, student: null, timeline: [] }); this.refresh(); },
  chooseChild(event) { if (this.data.busy) return; const s = this.data.students[Number(event.detail.value)]; if (!s) return; this.closeComparison(); this.setData({ studentId: s.id, student: null, timeline: [] }); this.refresh(); },
  filter(event) { this.setData({ filterIndex: Number(event.detail.value) }); this.setData({ timeline: this.filtered(this.data.allEvents) }); },
  openHomework(event) { wx.navigateTo({ url: '/pages/homework-detail/index?id=' + event.currentTarget.dataset.id }); },
  event(event) {
    const row = this.data.allEvents.find(e => e.key === event.currentTarget.dataset.key);
    if (!row) return;
    if (row.eventType === 'SAFETY') return wx.navigateTo({ url: '/pages/safety/index?date=' + this.data.date + '&studentId=' + this.data.studentId });
    if (row.sourceType === 'HOMEWORK') return this.openHomework({ currentTarget: { dataset: { id: row.sourceId } } });
    if (row.sourceType === 'FOLLOWUP') return wx.navigateTo({ url: '/pages/follow-ups/index?id=' + row.sourceId });
    if (row.sourceType === 'SUMMARY') return wx.navigateTo({ url: '/pages/summaries/index?date=' + this.data.date + '&studentId=' + this.data.studentId });
    toast(row.title + ' · ' + (row.actorName || '历史记录'));
  },
  pickup() { wx.navigateTo({ url: '/pages/safety/index?date=' + this.data.date + '&studentId=' + this.data.studentId }); },
  family() { if (this.data.student) wx.navigateTo({ url: '/pages/family-services/index?studentId=' + this.data.student.id }); },
  application(event) { const page = event.currentTarget.dataset.page; if (page === 'safety') return this.pickup(); if (this.data.applications.some(item => item.page === page)) wx.navigateTo({ url: '/pages/' + page + '/index?date=' + this.data.date + '&studentId=' + this.data.studentId }); },
  follow() { wx.navigateTo({ url: '/pages/follow-ups/index?date=' + this.data.date + '&studentId=' + this.data.studentId }); },
  message() { wx.navigateTo({ url: '/pages/follow-ups/index?kind=MESSAGE&compose=1&date=' + this.data.date + '&studentId=' + this.data.studentId }); }
});
