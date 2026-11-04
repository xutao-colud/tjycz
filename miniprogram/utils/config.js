// B 机统一入口；正式发布时改为已配置微信合法域名的 HTTPS 地址。
module.exports = {
  apiBase: 'http://8.163.74.71/api',
  requestTimeout: 20000,
  uploadTimeout: 90000,
  maxFileSize: 20 * 1024 * 1024
};
