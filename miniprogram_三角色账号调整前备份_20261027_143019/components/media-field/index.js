const api = require('../../utils/api');
const config = require('../../utils/config');
Component({
  properties: { disabled: Boolean, audioEnabled: Boolean, resetKey: { type: Number, value: 0 } },
  data: { files: [], busy: false, recording: false, seconds: 0, error: '', recordDenied: false },
  observers: { resetKey() { this.setData({ files: [], error: '' }); } },
  lifetimes: {
    attached() { this._alive = true; this._visible = true; },
    detached() {
      this._alive = false;
      this.stopForLeave();
      if (this.recorder) {
        this.recorder.offStop(this._onStop);
        this.recorder.offError(this._onError);
        this.recorder.offStart(this._onStart);
      }
    }
  },
  pageLifetimes: { show() { this._visible = true; }, hide() { this._visible = false; this.stopForLeave(); } },
  methods: {
    choose() {
      if (this.data.busy || this.data.disabled || this.data.recording) return;
      wx.chooseMedia({ count: 6 - this.data.files.length, mediaType: ['image', 'video'], sourceType: ['album', 'camera'], maxDuration: 60,
        success: result => this.upload(result.tempFiles),
        fail: error => { if (!/cancel/.test(error.errMsg || '')) this.setData({ error: '无法打开相机或相册，请检查微信权限' }); }
      });
    },
    ensureRecorder() {
      if (this.recorder) return;
      this.recorder = wx.getRecorderManager();
      this._onStart = () => {
        if (!this._alive || !this._visible) { this._discardRecording = true; this.recorder.stop(); return; }
        this.setData({ recording: true, seconds: 0, error: '', recordDenied: false });
        this._timer = setInterval(() => { if (this._alive) this.setData({ seconds: this.data.seconds + 1 }); }, 1000);
      };
      this._onStop = result => {
        clearInterval(this._timer);
        if (!this._alive) return;
        this.setData({ recording: false, busy: false });
        this.triggerEvent('uploading', { busy: false });
        if (!this._discardRecording && result.tempFilePath) this.upload([{ tempFilePath: result.tempFilePath, size: result.fileSize }]);
      };
      this._onError = () => {
        clearInterval(this._timer);
        if (!this._alive) return;
        this.setData({ recording: false, busy: false, error: '录音失败，请重试并检查麦克风权限' });
        this.triggerEvent('uploading', { busy: false });
      };
      this.recorder.onStart(this._onStart);
      this.recorder.onStop(this._onStop);
      this.recorder.onError(this._onError);
    },
    record() {
      if (this.data.disabled || this.data.busy || this.data.files.length >= 6) return;
      this.setData({ busy: true, error: '' });
      this.triggerEvent('uploading', { busy: true });
      wx.authorize({ scope: 'scope.record',
        success: () => {
          if (!this._alive || !this._visible) {
            if (this._alive) this.setData({ busy: false });
            this.triggerEvent('uploading', { busy: false });
            return;
          }
          this._discardRecording = false;
          this.ensureRecorder();
          this.recorder.start({ duration: 60000, format: 'mp3', sampleRate: 16000, numberOfChannels: 1, encodeBitRate: 48000 });
        },
        fail: () => {
          if (!this._alive) return;
          this.setData({ busy: false, recordDenied: true, error: '麦克风授权未开启，可重试或前往设置开启后录音' });
          this.triggerEvent('uploading', { busy: false });
        }
      });
    },
    stopRecord() { if (this.recorder) this.recorder.stop(); },
    stopForLeave() {
      clearInterval(this._timer);
      this._discardRecording = true;
      if (this.recorder && this.data.recording) this.recorder.stop();
    },
    settings() {
      wx.openSetting({ success: result => {
        const allowed = !!result.authSetting['scope.record'];
        this.setData({ recordDenied: !allowed, error: allowed ? '' : '请允许使用麦克风后重试' });
      } });
    },
    async upload(files) {
      this.setData({ busy: true, error: '' });
      this.triggerEvent('uploading', { busy: true });
      let list = this.data.files.slice();
      try {
        for (const file of files) {
          if (file.size > config.maxFileSize) throw new Error('单个文件不能超过 20MB，请选择较小的图片或视频');
          const uploaded = await api.upload(file.tempFilePath);
          list = list.concat(uploaded);
          if (!this._alive) return;
          this.setData({ files: list });
          this.triggerEvent('change', { ids: list.map(item => item.id) });
        }
      } catch (error) { if (this._alive) this.setData({ error: error.message }); }
      finally { if (this._alive) { this.setData({ busy: false }); this.triggerEvent('uploading', { busy: false }); } }
    },
    remove(event) {
      if (this.data.disabled || this.data.busy) return;
      const files = this.data.files.filter(file => String(file.id) !== String(event.currentTarget.dataset.id));
      this.setData({ files });
      this.triggerEvent('change', { ids: files.map(file => file.id) });
    }
  }
});
