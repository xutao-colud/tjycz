// 本机微信开发者工具可使用 localhost；手机预览需改为电脑的局域网 IP。
// 正式环境改为已配置微信 request/uploadFile/downloadFile 合法域名的 HTTPS 地址。
module.exports = {
  apiBase: 'http://localhost:18080/api',
  requestTimeout: 20000,
  uploadTimeout: 90000,
  maxFileSize: 20 * 1024 * 1024
};
