const session = require('./session');
function currentLocation() {
  const context = session.context();
  return new Promise((resolve, reject) => wx.getLocation({
    type: 'wgs84', isHighAccuracy: true, highAccuracyExpireTime: 8000,
    success(point) {
      if (!session.isCurrentContext(context)) { reject(new Error('账号或校区已切换，请重新操作')); return; }
      const { latitude, longitude, accuracy } = point;
      if (![latitude, longitude, accuracy].every(Number.isFinite)) { reject(new Error('定位结果无效，请重新获取')); return; }
      resolve({ latitude, longitude, accuracy, capturedAt: Date.now() });
    },
    fail() { reject(new Error('无法获取当前位置，请开启定位服务并允许精确定位后重试')); }
  }));
}
module.exports = { currentLocation };
