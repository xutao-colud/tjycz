const api = require('../../utils/api');
const session = require('../../utils/session');
const { message } = require('../../utils/page');
const INTRO_KEY = 'wanfu.login.intro.v1';
const ACCOUNT_KEY = 'wanfu.login.account.v1';
const ROLE_KEY = 'wanfu.login.role.v1';
const identities = [
  { code: 'PARENT', userRole: 'PARENT', name: '家长', title: '我是家长', image: 'parent', tone: 'parent', lines: ['查看孩子学习情况', '接收每日小结', '参与家校互动'] },
  { code: 'TEACHER', userRole: 'TEACHER', name: '老师', title: '我是老师', image: 'teacher', tone: 'teacher', lines: ['作业管理', '学生打卡', '日小结与点评'] },
  { code: 'CAMPUS_ADMIN', userRole: 'ADMIN', name: '校长', title: '我是校长', image: 'principal', tone: 'principal', lines: ['查看授权校区运营', '班级情况与教师点评', '校区总结与师生考勤'] },
  { code: 'ADMIN', userRole: 'SUPER_ADMIN', name: '管理员', title: '我是管理员', image: 'admin', tone: 'admin', lines: ['老板与总管理员工作台', '查看各校区整体运营', '管理校区与账号权限'] }
];
Page({
  data: {
    stage: 'roles', loginMode: 'account', identities, selectedRole: 'PARENT', selectedRoleName: '家长',
    username: '', password: '', phone: '', code: '', newPassword: '', confirmPassword: '',
    showPassword: false, showNewPassword: false, showConfirmPassword: false, remember: false, agreed: false,
    busy: false, error: '', health: '', connected: false, demoAvailable: false, hasSession: false,
    resultName: '', resultRole: '', successLeaving: false,
    infoOpen: false, infoTitle: '', infoText: '', legalOpen: false, legalTitle: '', legalText: ''
  },
  onLoad(query) {
    const remembered = wx.getStorageSync(ACCOUNT_KEY);
    const current = session.current();
    const role = identities.find(item => item.code === (query || {}).identity)
      || identities.find(item => current && item.userRole === current.user.role)
      || identities.find(item => item.code === wx.getStorageSync(ROLE_KEY));
    const stages = ['welcome', 'roles', 'login', 'forgot', 'reset', 'wechat', 'unbound'];
    const requested = (query || {}).stage;
    const stage = stages.includes(requested) ? requested : (wx.getStorageSync(INTRO_KEY) || current ? 'login' : 'roles');
    wx.setStorageSync(INTRO_KEY, true);
    this.setData({ stage, loginMode: 'account', username: typeof remembered === 'string' ? remembered : '', remember: !!remembered });
    this.setIdentity(role || identities.find(item => item.code === this.data.selectedRole));
  },
  onShow() {
    this._visible = true;
    this._disposed = false;
    this.setData({ hasSession: !!session.current() });
    if (this.data.stage === 'success') this.scheduleSuccess();
    else this.checkHealth();
  },
  onHide() { this._visible = false; this.clearSuccessTimers(); },
  onUnload() { this._disposed = true; this._visible = false; this.clearSuccessTimers(); },
  clearSuccessTimers() {
    clearTimeout(this._successTimer);
    clearTimeout(this._redirectTimer);
    this._successTimer = null;
    this._redirectTimer = null;
  },
  scheduleSuccess() {
    this.clearSuccessTimers();
    if (this._disposed || this._visible === false || this._navigating) return;
    const remaining = Math.max(0, (this._successAt || Date.now()) + 1500 - Date.now());
    this.setData({ successLeaving: false });
    this._successTimer = setTimeout(() => {
      if (this._disposed || this._visible === false || this.data.stage !== 'success') return;
      this.setData({ successLeaving: true });
      this._redirectTimer = setTimeout(() => {
        if (this._disposed || this._visible === false || this.data.stage !== 'success' || this._navigating) return;
        if (!session.current()) { this.useAccount(); return; }
        this._navigating = true;
        wx.reLaunch({
          url: '/pages/' + session.homePage() + '/index?fromLogin=1',
          fail: () => {
            this._navigating = false;
            if (this._disposed) return;
            this.setData({ successLeaving: false, stage: 'login', error: '首页暂时无法打开，请重试', hasSession: !!session.current() });
          }
        });
      }, 1200);
    }, remaining);
  },
  async checkHealth() {
    if (this._disposed) return;
    this.setData({ health: '正在连接服务…' });
    try {
      const value = await api.health();
      if (this._disposed) return;
      const patch = { health: value.status === 'UP' ? '服务连接正常' : '服务暂未就绪', connected: value.status === 'UP', demoAvailable: value.demo === true };
      if (value.demo === true && !this.data.username) patch.username = 'admin';
      this.setData(patch);
    } catch (error) { if (!this._disposed) this.setData({ health: '连接暂不可用，请稍后重试', connected: false, demoAvailable: false }); }
    wx.stopPullDownRefresh();
  },
  onPullDownRefresh() { this.checkHealth(); },
  field(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value, error: '' }); },
  start() { this.setData({ stage: 'roles', error: '' }); },
  setIdentity(identity) {
    wx.setStorageSync(ROLE_KEY, identity.code);
    this.setData({ selectedRole: identity.code, selectedRoleName: identity.name });
  },
  selectIdentity(event) {
    const identity = identities.find(item => item.code === event.currentTarget.dataset.role);
    if (!identity) return;
    this.setIdentity(identity);
    this.setData({ stage: 'login', error: '' });
  },
  skipIdentity() { this.setData({ stage: 'login', error: '' }); },
  changeIdentity() { if (!this.data.busy) this.setData({ stage: 'roles', error: '' }); },
  switchMode() { if (!this.data.busy) this.setData({ loginMode: 'account', error: '', password: '', code: '' }); },
  back() {
    if (this.data.busy) return;
    const stage = this.data.stage === 'roles' ? 'welcome' : this.data.stage === 'login' ? 'roles' : this.data.stage === 'reset' ? 'forgot' : 'login';
    this.setData({ stage, error: '', password: '', newPassword: '', confirmPassword: '' });
  },
  togglePassword(event) {
    const field = event.currentTarget.dataset.target || 'showPassword';
    if (['showPassword', 'showNewPassword', 'showConfirmPassword'].includes(field)) this.setData({ [field]: !this.data[field] });
  },
  toggleRemember() { this.setData({ remember: !this.data.remember }); },
  toggleAgreement() { this.setData({ agreed: !this.data.agreed, error: '' }); },
  clearPhone() { this.setData({ phone: '', code: '', error: '' }); },
  unavailable(title, text) { this.setData({ infoOpen: true, infoTitle: title, infoText: text }); },
  closeInfo() { this.setData({ infoOpen: false, legalOpen: false }); },
  noop() {},
  requestCode() {
    if (!/^1\d{10}$/.test(this.data.phone.replace(/\s/g, ''))) { this.setData({ error: '请输入正确的 11 位手机号' }); return; }
    this.unavailable('验证码登录暂未开通', '机构暂未开通短信验证服务，当前不会发送验证码。请使用账号密码登录，或联系机构管理员。');
  },
  forgot() { if (!this.data.busy) this.setData({ stage: 'forgot', error: '' }); },
  openReset() { this.setData({ stage: 'reset', error: '', newPassword: '', confirmPassword: '' }); },
  resetPassword() { this.unavailable('暂不支持自助重置', '机构暂未开通短信验证与密码重置服务。请联系机构管理员处理，当前密码未作更改。'); },
  wechat() { if (this.data.busy) return; this.setData({ stage: 'wechat', error: '' }); },
  authorizeWechat() { this.unavailable('微信登录暂未开通', '当前机构尚未接入微信身份登录和账号绑定。请返回使用机构账号密码登录。'); },
  useAccount() { this.clearSuccessTimers(); this.setData({ stage: 'login', loginMode: 'account', error: '', successLeaving: false }); },
  usePhone() { this.useAccount(); },
  contact() { this.unavailable('联系机构管理员', '请通过机构提供的联系电话或线下服务台，办理账号开通、身份绑定或密码重置。'); },
  showLegal(event) {
    const privacy = event.currentTarget.dataset.kind === 'privacy';
    this.setData({ legalOpen: true, legalTitle: privacy ? '隐私政策' : '用户协议', legalText: privacy
      ? '当前为本地体验版本说明，正式隐私政策请向机构获取。账号仅用于验证登录身份；登录会话保存在本机，用于访问当前账号有权查看的学习记录。记住账号仅保存账号名称，不保存密码。当前页面不会通过微信获取头像、昵称或手机号。'
      : '当前为本地体验版本说明，正式用户协议请向机构获取。请使用机构分配给本人的账号。页面选择的身份仅用于引导，本地体验凭据可按所选身份体验虚构数据，正式账号的访问权限以后台分配角色为准。' });
  },
  closeLegal() { this.setData({ legalOpen: false }); },
  async submit(event) {
    if (this.data.busy) return;
    if (!this.data.agreed) { this.setData({ error: '请阅读并同意用户协议与隐私政策' }); return; }
    if (this.data.loginMode === 'phone') { this.requestCode(); return; }
    if (!this.data.username.trim() || !this.data.password) { this.setData({ error: '请输入账号和密码' }); return; }
    this.setData({ busy: true, error: '' });
    try {
      const value = await api.login({ username: this.data.username.trim(), password: this.data.password, role: this.data.selectedRole });
      if (!session.valid(value)) throw new Error('登录返回缺少有效会话，请重试');
      if (this._disposed) return;
      getApp().setSession(value);
      this.setIdentity(identities.find(item => item.userRole === value.user.role));
      if (this.data.remember) wx.setStorageSync(ACCOUNT_KEY, this.data.username.trim());
      else wx.removeStorageSync(ACCOUNT_KEY);
      this._successAt = Date.now();
      this.setData({ stage: 'success', successLeaving: false, hasSession: true, password: '', code: '', resultName: value.user.name || '', resultRole: { SUPER_ADMIN: '老板 / 总管理员', ADMIN: '校区校长', TEACHER: '老师', PARENT: '家长' }[value.user.role] });
      this.scheduleSuccess();
    } catch (error) { if (!this._disposed) this.setData({ error: message(error) }); }
    finally { if (!this._disposed) this.setData({ busy: false }); }
  },
  resume() { this.clearSuccessTimers(); if (session.current()) wx.reLaunch({ url: '/pages/' + session.homePage() + '/index' }); else this.useAccount(); },
  async logout() {
    if (this.data.busy) return;
    this.clearSuccessTimers(); this.setData({ busy: true, error: '' });
    try { await getApp().logout(); this.setData({ hasSession: false, stage: 'login', password: '', successLeaving: false }); }
    catch (error) { this.setData({ error: error.message || '退出未成功，请重试' }); }
    finally { this.setData({ busy: false }); }
  }
});
