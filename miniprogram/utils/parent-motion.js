const KEY = 'wanfu.parent.reduceMotion';
function reduced() {
  try { return wx.getStorageSync(KEY) === true; } catch (_) { return false; }
}
function save(value) { wx.setStorageSync(KEY, !!value); }
module.exports = { reduced, save };
