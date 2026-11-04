// 局域网联调使用电脑当前 IP，手机与电脑需在同一网络；IP 变化后更新此处。
// 正式环境改为已配置微信 request/uploadFile/downloadFile 合法域名的 HTTPS 地址。
module.exports = {
  apiBase: 'http://192.168.1.169:18080/api',
  requestTimeout: 20000,
  uploadTimeout: 90000,
  maxFileSize: 20 * 1024 * 1024
};
