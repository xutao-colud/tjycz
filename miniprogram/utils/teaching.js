const api = require('./api');
const session = require('./session');
async function choosePhoto(page, campusId, sourceType = ['camera', 'album']) {
  const context = session.context();
  const current = () => !page._unloaded && session.isCurrentContext(context);
  const photo = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType, success: result => resolve(result.tempFiles[0]), fail: error => reject(new Error(/cancel/.test(error.errMsg || '') ? '已取消拍照' : '无法打开相机或相册，请检查权限')) }));
  if (!current()) throw session.contextError();
  const file = await api.upload(photo.tempFilePath, campusId);
  if (!current()) throw session.contextError();
  return file;
}
async function recordPickup(page, studentId, campusId, action) {
  const photo = await choosePhoto(page, campusId, ['camera']);
  return api.request('POST', '/attendance/photo', { studentId, photoFileId: photo.id, action, teacherPickup: true });
}
async function openBook(page, file) {
  const context = session.context();
  const ticket = await api.request('GET', '/files/' + file.id + '/access');
  if (page._unloaded || !session.isCurrentContext(context)) return;
  const result = await new Promise((resolve, reject) => wx.downloadFile({ url: api.fileUrl(ticket), success: response => response.statusCode === 200 ? resolve(response) : reject(new Error('打印本下载失败，请重试')), fail: () => reject(new Error('无法下载打印本，请检查网络及下载域名')) }));
  if (page._unloaded || !session.isCurrentContext(context)) return;
  await new Promise((resolve, reject) => wx.openDocument({ filePath: result.tempFilePath, fileType: 'pdf', showMenu: true, success: resolve, fail: () => reject(new Error('无法打开PDF，请重试或在后台下载打印')) }));
}
function requestId() { return 'teaching-' + Date.now() + '-' + Math.random().toString(36).slice(2); }
module.exports = { choosePhoto, recordPickup, openBook, requestId };
