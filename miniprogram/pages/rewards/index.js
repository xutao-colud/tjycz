const api = require('../../utils/api');
const format = require('../../utils/format');
const session = require('../../utils/session');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
const { choosePhoto, requestId } = require('../../utils/teaching');
definePage({
  data: { superAdmin: false, rulesOpen: false, automaticRules: [], learningExperience: '10', fitnessExperience: '6', students: [], child: null, studentLevel: null, prizes: [], presets: [], allPresets: [], presetNames: ['选择固定奖励'], presetIndex: 0, records: [], receipt: null, receiptUrl: '', requestId: '', fingerprint: '', showPrize: false, editId: 0, editVersion: 0, configCampusId: 0, prizeName: '', pointsCost: '10', requiredLevel: '1', stock: '0', enabled: true, cover: null, coverUrl: '', showPreset: false, editPresetId: 0, presetVersion: 0, presetName: '', presetPoints: '2', presetExperience: '6', presetEnabled: true },
  async loadData() {
    const superAdmin = session.current().user.role === 'SUPER_ADMIN';
    const selected = await selection(this, false), child = selected.students.find(s => Number(s.id) === Number(selected.studentIds[0]));
    const [prizes, allPresets, records] = await Promise.all([api.request('GET', '/reward-prizes', { studentId: selected.studentIds[0] }), api.request('GET', '/point-presets'), api.request('GET', '/prize-redemptions', { studentId: selected.studentIds[0] })]);
    const presets = allPresets.filter(p => child && Number(p.campusId) === Number(child.campusId) && p.enabled);
    const settings = superAdmin ? await api.settings() : null;
    const automatic = settings ? { automaticRules: settings.pointsRules.map(rule => ({ event: rule.event, name: format.label(rule.event), points: String(rule.points) })), learningExperience: String(settings.experienceRules.find(rule => rule.category === 'LEARNING').experience), fitnessExperience: String(settings.experienceRules.find(rule => rule.category === 'FITNESS').experience) } : {};
    return Object.assign({}, selected, automatic, { superAdmin, child: child || null, studentLevel: prizes.length ? prizes[0].studentLevel : null, allPresets, presets, presetNames: ['选择固定奖励'].concat(presets.map(p => p.name + ' · ' + p.points + '积分 / ' + p.experience + '经验')), presetIndex: this.data.presetIndex <= presets.length ? this.data.presetIndex : 0, prizes: prizes.map(p => Object.assign({}, p, { coverUrl: p.image ? api.fileUrl(p.image) : '' })), records: records.map(r => Object.assign({}, r, { createdText: format.time(r.createdAt) })) });
  },
  pickStudents(event) { if (this.data.busy) return; this.setData({ studentIds: event.detail.ids, presetIndex: 0, receipt: null, receiptUrl: '', requestId: '', showPrize: false, showPreset: false }); this.refresh(); },
  selectPreset(event) { this.setData({ presetIndex: Number(event.detail.value) }); },
  key(body) { const fingerprint = JSON.stringify(body); if (!this.data.requestId || this.data.fingerprint !== fingerprint) this.setData({ fingerprint, requestId: requestId() }); return this.data.requestId; },
  async award() {
    const rule = this.data.presets[this.data.presetIndex - 1], child = this.data.child;
    if (!child || !rule) { toast('请选择学生和固定奖励'); return; }
    if (!await confirm('确认成长奖励', child.name + ' · ' + rule.name + '，奖励 ' + rule.points + '积分 / ' + rule.experience + '经验。努力类经验每天奖励一次，积分按每次确认发放。')) return;
    const body = { studentId: child.id, presetId: rule.id };
    await this.act(async () => { await api.request('POST', '/point-awards', Object.assign({}, body, { requestId: this.key(body) })); this.setData({ requestId: '' }); }, '奖励已按规则结算');
  },
  async receiptPhoto(event) {
    const id = Number(event.currentTarget.dataset.id || 0), record = this.data.records.find(r => r.id === id), child = record ? this.data.students.find(s => s.id === record.studentId) : this.data.child;
    if (!child) { toast('请先选择孩子'); return; }
    await this.act(async () => { const file = await choosePhoto(this, child.campusId); if (record) await api.request('POST', '/prize-redemptions/' + record.id + '/photos', { attachmentIds: [file.id] }); else this.setData({ receipt: file, receiptUrl: api.fileUrl(file) }); }, record ? '兑换照片已保存' : '照片已上传');
  },
  async redeem(event) {
    const prize = this.data.prizes.find(p => p.id === Number(event.currentTarget.dataset.id)), child = this.data.child;
    if (!child || !prize || !prize.canRedeem) return;
    if (!await confirm('确认奖品已实际发放', child.name + '兑换「' + prize.name + '」，扣除' + prize.pointsCost + '积分，库存减少1件。经验与等级保留。')) return;
    const body = { studentId: child.id, prizeId: prize.id, expectedVersion: prize.version, attachmentIds: this.data.receipt ? [this.data.receipt.id] : [] };
    await this.act(async () => { await api.request('POST', '/prize-redemptions', Object.assign({}, body, { requestId: this.key(body) })); this.setData({ requestId: '', receipt: null, receiptUrl: '' }); }, '兑换已记录，积分与库存已更新');
  },
  editPrize(event) { const p = this.data.prizes.find(p => p.id === Number(event.currentTarget.dataset.id)); this.setData({ showPrize: true, editId: p ? p.id : 0, editVersion: p ? p.version : 0, configCampusId: p ? p.campusId : this.data.child ? this.data.child.campusId : 0, prizeName: p ? p.name : '', pointsCost: String(p ? p.pointsCost : 10), requiredLevel: String(p ? p.requiredLevel : 1), stock: String(p ? p.stock : 0), enabled: p ? p.enabled : true, cover: p ? p.image : null, coverUrl: p && p.image ? api.fileUrl(p.image) : '' }); },
  closePrize() { this.setData({ showPrize: false }); },
  toggleEnabled() { this.setData({ enabled: !this.data.enabled }); },
  async coverPhoto() { if (!this.data.configCampusId) return; await this.act(async () => { const file = await choosePhoto(this, this.data.configCampusId); this.setData({ cover: file, coverUrl: api.fileUrl(file) }); }, '奖品图片已上传'); },
  async savePrize() {
    const d = this.data, points = Number(d.pointsCost), stock = Number(d.stock), requiredLevel = d.requiredLevel.trim() ? Number(d.requiredLevel) : NaN;
    if (!d.configCampusId || !d.prizeName.trim() || !Number.isInteger(points) || points < 1 || !Number.isInteger(stock) || stock < 0 || !Number.isInteger(requiredLevel) || requiredLevel < 1 || requiredLevel > 1000) { toast('请填写奖品、积分、库存与1至1000的兑换等级'); return; }
    await this.act(async () => { await api.request(d.editId ? 'PUT' : 'POST', '/reward-prizes' + (d.editId ? '/' + d.editId : ''), { campusId: d.configCampusId, name: d.prizeName.trim(), pointsCost: points, requiredLevel, stock, enabled: d.enabled, imageFileId: d.cover ? d.cover.id : null, expectedVersion: d.editVersion }); this.setData({ showPrize: false }); }, '奖品已保存');
  },
  editPreset(event) { const p = this.data.allPresets.find(p => p.id === Number(event.currentTarget.dataset.id)); this.setData({ showPreset: true, editPresetId: p ? p.id : 0, presetVersion: p ? p.version : 0, configCampusId: p ? p.campusId : this.data.child ? this.data.child.campusId : 0, presetName: p ? p.name : '', presetPoints: String(p ? p.points : 2), presetExperience: String(p ? p.experience : 6), presetEnabled: p ? p.enabled : true }); },
  closePreset() { this.setData({ showPreset: false }); },
  togglePreset() { this.setData({ presetEnabled: !this.data.presetEnabled }); },
  toggleRules() { this.setData({ rulesOpen: !this.data.rulesOpen }); },
  rulePoints(event) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isInteger(index) || !this.data.automaticRules[index]) return;
    this.setData({ automaticRules: this.data.automaticRules.map((rule, i) => i === index ? Object.assign({}, rule, { points: event.detail.value }) : rule) });
  },
  async saveRules() {
    if (!this.data.superAdmin) return;
    const parse = value => String(value).trim() ? Number(value) : NaN;
    const pointsRules = this.data.automaticRules.map(rule => ({ event: rule.event, points: parse(rule.points) }));
    const experienceRules = [{ category: 'LEARNING', experience: parse(this.data.learningExperience) }, { category: 'FITNESS', experience: parse(this.data.fitnessExperience) }];
    if (pointsRules.some(rule => !Number.isInteger(rule.points) || rule.points < 0 || rule.points > 100) || experienceRules.some(rule => !Number.isInteger(rule.experience) || rule.experience < 0 || rule.experience > 100)) { toast('奖励积分与经验均须为0至100整数'); return; }
    await this.act(async () => { await api.request('PATCH', '/settings', { pointsRules, experienceRules }); this.setData({ rulesOpen: false }); }, '全局奖励规则已保存');
  },
  async savePreset() { const d = this.data, points = Number(d.presetPoints), experience = d.presetExperience.trim() ? Number(d.presetExperience) : NaN; if (!d.configCampusId || !d.presetName.trim() || !Number.isInteger(points) || points < 1 || points > 100 || !Number.isInteger(experience) || experience < 0 || experience > 100) { toast('名称必填，积分1至100，经验0至100，均为整数'); return; } await this.act(async () => { await api.request(d.editPresetId ? 'PUT' : 'POST', '/point-presets' + (d.editPresetId ? '/' + d.editPresetId : ''), { campusId: d.configCampusId, name: d.presetName.trim(), points, experience, enabled: d.presetEnabled, expectedVersion: d.presetVersion }); this.setData({ showPreset: false }); }, '固定奖励已保存'); }
});
