const api = require('../../utils/api');
const privacy = require('../../utils/privacy');
const session = require('../../utils/session');
const { definePage, toast, confirm } = require('../../utils/page');
const money = value => '¥' + (Number(value || 0) / 100).toFixed(2);
definePage({
  data: { studentId: '', students: [], childNames: [], childIndex: 0, family: null, avatarUrl: '', mediaBlocked: false, mediaCanConsent: false, accounts: [], lowCount: 0, mealPriceText: '', mealAmountText: '', intents: [], meals: [], mealCount: 0, intentNames: ['请选择续费意向', '有意向续费', '暂不续费'], campuses: [], campusNames: [], configIndex: 0, configCampusId: '', config: null, configPrice: '', configQr: null, configQrUrl: '', uploading: false },
  onLoad(query) { this.setData({ studentId: query.studentId || '' }); },
  async loadData() {
    const chosen = this.data.studentId, configChosen = this.data.configCampusId, date = this.data.date;
    const students = await api.students({}), staff = session.isTeacher();
    const selected = students.find(s => String(s.id) === String(chosen));
    const child = selected || (!staff ? students[0] : null), studentId = child ? child.id : '';
    const [family, intents, meals, campuses] = await Promise.all([
      studentId ? api.request('GET', '/family-services', { studentId }) : null,
      staff ? api.request('GET', '/renewal-intents', { studentId }) : [],
      staff ? api.request('GET', '/meal-orders', { date, studentId }) : [],
      session.isAdmin() ? api.campuses() : []
    ]);
    const current = getApp().globalData.selectedCampusId;
    const available = campuses.filter(c => !current || c.id === current);
    const campus = available.find(c => String(c.id) === String(configChosen)) || available[0];
    const config = campus ? await api.request('GET', '/campuses/' + campus.id + '/family-services') : null;
    return { students, studentId, childNames: (staff ? ['全部授权学生'] : []).concat(students.map(s => s.name + ' · ' + s.className)), childIndex: Math.max(0, students.findIndex(s => s.id === studentId) + (staff ? 1 : 0)), family,
      avatarUrl: family && family.student.avatar ? api.fileUrl(family.student.avatar) : '/assets/icons/students.png', mediaBlocked: false, mediaCanConsent: false,
      accounts: family ? family.accounts.map(a => Object.assign({}, a, { intentIndex: a.intention === 'INTERESTED' ? 1 : a.intention === 'NOT_NOW' ? 2 : 0, available: a.availableBalance === undefined ? a.balance : a.availableBalance })) : [],
      lowCount: family ? family.accounts.filter(a => a.lowBalance).length : 0,
      mealPriceText: family && family.settings.mealPrice !== null ? money(family.settings.mealPrice) : '', mealAmountText: family && family.meal ? money(family.meal.amount) : '',
      intents: intents.map(i => Object.assign({}, i, { intentionText: i.intention === 'INTERESTED' ? '有意向续费' : '暂不续费' })),
      meals: meals.map(m => Object.assign({}, m, { amountText: money(m.amount) })), mealCount: meals.filter(m => m.ordered).length,
      campuses: available, campusNames: available.map(c => c.name), configIndex: Math.max(0, available.findIndex(c => c.id === (campus && campus.id))), configCampusId: campus ? campus.id : '', config,
      configPrice: config && config.mealPrice !== null ? String(config.mealPrice / 100) : '', configQr: config && config.paymentQr, configQrUrl: config && config.paymentQr ? api.fileUrl(config.paymentQr) : '' };
  },
  changeChild(event) { if (this.data.busy || this.data.uploading) return; const index = Number(event.detail.value), child = this.data.students[index - (this.data.teacher ? 1 : 0)]; this.setData({ studentId: child ? child.id : '', childIndex: index, mediaBlocked: false, mediaCanConsent: false, actionError: '' }); this.refresh(); },
  changeConfigCampus(event) { if (this.data.busy || this.data.uploading) return; const index = Number(event.detail.value); this.setData({ configCampusId: this.data.campuses[index].id, configIndex: index }); this.refresh(); },
  async intention(event) {
    if (this.data.busy || this.data.uploading) return;
    const account = this.data.accounts.find(a => String(a.id) === String(event.currentTarget.dataset.id)), index = Number(event.detail.value);
    if (!account || !index) return;
    await this.act(() => api.request('PUT', '/accounts/' + account.id + '/renewal-intent', { intention: index === 1 ? 'INTERESTED' : 'NOT_NOW', expectedVersion: account.intentVersion }), '意向已保存，负责老师和校长可查看');
  },
  async orderMeal(event) {
    const page = this.data.family; if (!page || this.data.uploading) return;
    const ordered = event.currentTarget.dataset.ordered === 'yes';
    if (ordered && page.settings.mealPrice === null) { toast('请联系校区设置餐费'); return; }
    await this.act(() => api.request('PUT', '/students/' + page.student.id + '/meal-order', { ordered, expectedVersion: page.meal ? page.meal.version : 0 }), ordered ? '今日订餐已登记' : '今日已选择不订餐');
  },
  async choosePhoto(event) {
    if (this.data.busy || this.data.uploading) return;
    const qr = event.currentTarget.dataset.kind === 'qr', child = this.data.family && this.data.family.student;
    const campus = qr ? this.data.configCampusId : child && child.campusId;
    if (!campus) { toast('请先选择孩子或校区'); return; }
    const context = session.context(); this.setData({ uploading: true });
    try {
      if (!qr) {
        const consent = await privacy.consents(child.id);
        if (!session.isCurrentContext(context) || this._unloaded) return;
        this.setData({ mediaBlocked: !consent.effectiveMedia, mediaCanConsent: !!consent.canConsent, actionError: '' });
        if (!consent.effectiveMedia) {
          if (consent.canConsent) {
            if (await confirm('先确认孩子的媒体授权', child.name + '的服务或媒体授权尚未完成或已撤回。请进入“隐私与授权”，阅读说明并明确选择；完成后返回上传头像。')
              && session.isCurrentContext(context) && !this._unloaded) this.openMediaConsent();
          } else toast('请该孩子的绑定监护人在“隐私与授权”中确认服务与媒体授权');
          return;
        }
      }
      const path = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], success: r => resolve(r.tempFiles[0].tempFilePath), fail: e => /cancel/i.test(e.errMsg || '') ? resolve(null) : reject(new Error('暂时无法选择图片，请检查相册或相机权限')) }));
      if (!path || !session.isCurrentContext(context) || this._unloaded) return;
      await this.act(async () => { const file = await api.upload(path, campus); if (!session.isCurrentContext(context) || this._unloaded) return; if (qr) this.setData({ configQr: file, configQrUrl: api.fileUrl(file) }); else await api.request('PUT', '/students/' + child.id + '/avatar', { avatarFileId: file.id }); }, qr ? '图片已上传，请保存校区设置' : '孩子头像已更新', !qr);
    } catch (error) { if (session.isCurrentContext(context) && !this._unloaded) this.setData({ actionError: error.message }); }
    finally { if (session.isCurrentContext(context) && !this._unloaded) this.setData({ uploading: false }); }
  },
  openMediaConsent() {
    const child = this.data.family && this.data.family.student;
    if (child && this.data.mediaCanConsent && !this._unloaded) wx.navigateTo({ url: '/pages/privacy/index?studentId=' + child.id });
  },
  removeQr() { if (!this.data.busy && !this.data.uploading) this.setData({ configQr: null, configQrUrl: '' }); },
  async saveConfig() {
    if (!this.data.config || this.data.uploading) return;
    const price = this.data.configPrice.trim(); if (price && !/^\d+(?:\.\d{1,2})?$/.test(price)) { toast('餐费最多保留两位小数'); return; }
    await this.act(() => api.request('PUT', '/campuses/' + this.data.configCampusId + '/family-services', { mealPrice: price ? Math.round(Number(price) * 100) : null, paymentQrFileId: this.data.configQr ? this.data.configQr.id : null, expectedVersion: this.data.config.version }), '校区餐费与缴费二维码已保存');
  },
  async openQr() { const file = this.data.family && this.data.family.settings.paymentQr; if (!file) { toast('校区尚未配置缴费二维码'); return; } await this.act(async () => { const fresh = await api.request('GET', '/files/' + file.id + '/access'); if (this._unloaded) return; const url = api.fileUrl(fresh); wx.previewImage({ urls: [url], current: url }); }, '', false); }
});
