// 线上接口通过 Web 端口反向代理，后端 18080 不直接对外开放。
module.exports = {
  apiBase: 'http://8.163.74.71/api',
  requestTimeout: 20000,
  uploadTimeout: 90000,
  maxFileSize: 20 * 1024 * 1024
};
