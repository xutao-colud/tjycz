const api = require('../../utils/api');
Component({
  properties: { files: { type: Array, value: [] } },
  methods: {
    open(event) {
      const index = Number(event.currentTarget.dataset.index);
      const file = this.data.files[index];
      const url = api.fileUrl(file);
      if ((file.contentType || '').startsWith('image/')) {
        const urls = this.data.files.filter(item => (item.contentType || '').startsWith('image/')).map(api.fileUrl);
        wx.previewImage({ current: url, urls, fail: () => this.error() });
      } else if ((file.contentType || '').startsWith('video/')) {
        wx.previewMedia({ sources: [{ url, type: 'video' }], fail: () => this.error() });
      } else if ((file.contentType || '').startsWith('audio/')) {
        if (this.audio) this.audio.destroy();
        this.audio = wx.createInnerAudioContext();
        this.audio.src = url;
        this.audio.onError(() => this.error());
        this.audio.play();
        wx.showToast({ title: '开始播放音频', icon: 'none' });
      } else wx.showToast({ title: '暂不支持此文件预览', icon: 'none' });
    },
    error() { wx.showToast({ title: '文件读取失败，请刷新页面更新会话链接', icon: 'none' }); }
  },
  pageLifetimes: { hide() { if (this.audio) this.audio.stop(); } },
  lifetimes: { detached() { if (this.audio) this.audio.destroy(); } }
});
