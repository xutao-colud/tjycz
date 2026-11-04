const api = require('../../utils/api');
const session = require('../../utils/session');
const config = require('../../utils/config');
const { message } = require('../../utils/page');
Page({
  data: { username: '', password: '', roleIndex: 0, roles: ['教师', '家长', '管理员'], busy: false, error: '', health: '', apiBase: config.apiBase, hasSession: false },
  onShow() { this.setData({ hasSession: !!session.current() }); this.checkHealth(); },
  async checkHealth() {
    this.setData({ health: '正在连接后端…' });
    try {
      const value = await api.health();
      this.setData({ health: value.status === 'UP' ? '后端可连接' : '后端健康状态异常' });
    } catch (error) { this.setData({ health: '暂时无法连接，请核对服务地址和端口' }); }
    wx.stopPullDownRefresh();
  },
  onPullDownRefresh() { this.checkHealth(); },
  field(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }); },
  role(event) { this.setData({ roleIndex: Number(event.detail.value) }); },
  async submit(event) {
    if (this.data.busy) return;
    const demo = event.currentTarget.dataset.mode === 'demo';
    if (!demo && (!this.data.username.trim() || !this.data.password)) {
      this.setData({ error: '请输入账号和密码' }); return;
    }
    this.setData({ busy: true, error: '' });
    try {
      const value = demo ? await api.demo(['TEACHER', 'PARENT', 'ADMIN'][this.data.roleIndex]) : await api.login({ username: this.data.username.trim(), password: this.data.password });
      if (!value.token || !value.user) throw new Error('登录返回缺少有效会话，请检查后端');
      getApp().setSession(value);
      this.setData({ password: '' });
      wx.reLaunch({ url: '/pages/dashboard/index' });
    } catch (error) { this.setData({ error: message(error) }); }
    finally { this.setData({ busy: false }); }
  },
  resume() { wx.reLaunch({ url: '/pages/dashboard/index' }); },
  logout() { getApp().clearSession(); this.setData({ hasSession: false }); }
});
