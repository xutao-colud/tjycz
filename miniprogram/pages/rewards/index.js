const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
const { choosePhoto, requestId } = require('../../utils/teaching');
definePage({
  data: { students: [], child: null, prizes: [], presets: [], allPresets: [], presetNames: ['选择固定奖励'], presetIndex: 0, records: [], receipt: null, receiptUrl: '', requestId: '', fingerprint: '', showPrize: false, editId: 0, editVersion: 0, configCampusId: 0, prizeName: '', pointsCost: '10', stock: '0', enabled: true, cover: null, coverUrl: '', showPreset: false, editPresetId: 0, presetVersion: 0, presetName: '', presetPoints: '2', presetEnabled: true },
  async loadData() {
    const selected = await selection(this, false), child = selected.students.find(s => Number(s.id) === Number(selected.studentIds[0]));
    const [prizes, allPresets, records] = await Promise.all([api.request('GET', '/reward-prizes'), api.request('GET', '/point-presets'), api.request('GET', '/prize-redemptions', { studentId: selected.studentIds[0] })]);
    const presets = allPresets.filter(p => child && Number(p.campusId) === Number(child.campusId) && p.enabled);
    return Object.assign({}, selected, { child: child || null, allPresets, presets, presetNames: ['选择固定奖励'].concat(presets.map(p => p.name + ' +' + p.points + '分')), presetIndex: this.data.presetIndex <= presets.length ? this.data.presetIndex : 0, prizes: prizes.map(p => Object.assign({}, p, { coverUrl: p.image ? api.fileUrl(p.image) : '', canRedeem: !!child && Number(p.campusId) === Number(child.campusId) && p.enabled && p.stock > 0 && child.points >= p.pointsCost })), records: records.map(r => Object.assign({}, r, { createdText: format.time(r.createdAt) })) });
  },
  pickStudents(event) { if (this.data.busy) return; this.setData({ studentIds: event.detail.ids, presetIndex: 0, receipt: null, receiptUrl: '', requestId: '', showPrize: false, showPreset: false }); this.refresh(); },
  selectPreset(event) { this.setData({ presetIndex: Number(event.detail.value) }); },
  key(body) { const fingerprint = JSON.stringify(body); if (!this.data.requestId || this.data.fingerprint !== fingerprint) this.setData({ fingerprint, requestId: requestId() }); return this.data.requestId; },
  async award() {
    const rule = this.data.presets[this.data.presetIndex - 1], child = this.data.child;
    if (!child || !rule) { toast('请选择学生和固定奖励'); return; }
    if (!await confirm('确认积分奖励', child.name + ' · ' + rule.name + ' +' + rule.points + '分')) return;
    const body = { studentId: child.id, presetId: rule.id };
    await this.act(async () => { await api.request('POST', '/point-awards', Object.assign({}, body, { requestId: this.key(body) })); this.setData({ requestId: '' }); }, '积分已发放');
  },
  async receiptPhoto(event) {
    const id = Number(event.currentTarget.dataset.id || 0), record = this.data.records.find(r => r.id === id), child = record ? this.data.students.find(s => s.id === record.studentId) : this.data.child;
    if (!child) { toast('请先选择孩子'); return; }
    await this.act(async () => { const file = await choosePhoto(this, child.campusId); if (record) await api.request('POST', '/prize-redemptions/' + record.id + '/photos', { attachmentIds: [file.id] }); else this.setData({ receipt: file, receiptUrl: api.fileUrl(file) }); }, record ? '兑换照片已保存' : '照片已上传');
  },
  async redeem(event) {
    const prize = this.data.prizes.find(p => p.id === Number(event.currentTarget.dataset.id)), child = this.data.child;
    if (!child || !prize) return;
    if (!await confirm('确认奖品已实际发放', child.name + '兑换「' + prize.name + '」，扣除' + prize.pointsCost + '积分，库存减少1件。')) return;
    const body = { studentId: child.id, prizeId: prize.id, expectedVersion: prize.version, attachmentIds: this.data.receipt ? [this.data.receipt.id] : [] };
    await this.act(async () => { await api.request('POST', '/prize-redemptions', Object.assign({}, body, { requestId: this.key(body) })); this.setData({ requestId: '', receipt: null, receiptUrl: '' }); }, '兑换已记录，积分与库存已更新');
  },
  editPrize(event) { const p = this.data.prizes.find(p => p.id === Number(event.currentTarget.dataset.id)); this.setData({ showPrize: true, editId: p ? p.id : 0, editVersion: p ? p.version : 0, configCampusId: p ? p.campusId : this.data.child ? this.data.child.campusId : 0, prizeName: p ? p.name : '', pointsCost: String(p ? p.pointsCost : 10), stock: String(p ? p.stock : 0), enabled: p ? p.enabled : true, cover: p ? p.image : null, coverUrl: p && p.image ? api.fileUrl(p.image) : '' }); },
  closePrize() { this.setData({ showPrize: false }); },
  toggleEnabled() { this.setData({ enabled: !this.data.enabled }); },
  async coverPhoto() { if (!this.data.configCampusId) return; await this.act(async () => { const file = await choosePhoto(this, this.data.configCampusId); this.setData({ cover: file, coverUrl: api.fileUrl(file) }); }, '奖品图片已上传'); },
  async savePrize() {
    const d = this.data, points = Number(d.pointsCost), stock = Number(d.stock);
    if (!d.configCampusId || !d.prizeName.trim() || !Number.isInteger(points) || points < 1 || !Number.isInteger(stock) || stock < 0) { toast('请选择校区内学生，填写奖品名称、整数积分和库存'); return; }
    await this.act(async () => { await api.request(d.editId ? 'PUT' : 'POST', '/reward-prizes' + (d.editId ? '/' + d.editId : ''), { campusId: d.configCampusId, name: d.prizeName.trim(), pointsCost: points, stock, enabled: d.enabled, imageFileId: d.cover ? d.cover.id : null, expectedVersion: d.editVersion }); this.setData({ showPrize: false }); }, '奖品已保存');
  },
  editPreset(event) { const p = this.data.allPresets.find(p => p.id === Number(event.currentTarget.dataset.id)); this.setData({ showPreset: true, editPresetId: p ? p.id : 0, presetVersion: p ? p.version : 0, configCampusId: p ? p.campusId : this.data.child ? this.data.child.campusId : 0, presetName: p ? p.name : '', presetPoints: String(p ? p.points : 2), presetEnabled: p ? p.enabled : true }); },
  closePreset() { this.setData({ showPreset: false }); },
  togglePreset() { this.setData({ presetEnabled: !this.data.presetEnabled }); },
  async savePreset() { const d = this.data, points = Number(d.presetPoints); if (!d.configCampusId || !d.presetName.trim() || !Number.isInteger(points) || points < 1 || points > 100) { toast('奖励名称必填，积分须为1至100整数'); return; } await this.act(async () => { await api.request(d.editPresetId ? 'PUT' : 'POST', '/point-presets' + (d.editPresetId ? '/' + d.editPresetId : ''), { campusId: d.configCampusId, name: d.presetName.trim(), points, enabled: d.presetEnabled, expectedVersion: d.presetVersion }); this.setData({ showPreset: false }); }, '固定奖励已保存'); }
});
