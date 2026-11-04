const api = require('../../utils/api');
const session = require('../../utils/session');

function displayFile(file) {
  const contentType = (file.contentType || '').toLowerCase();
  const name = file.name || file.url || '';
  let kind = 'file';
  if (contentType.startsWith('image/')) kind = 'image';
  else if (contentType.startsWith('audio/')) kind = 'audio';
  else if (contentType.startsWith('video/')) kind = 'video';
  else if (/\.(jpe?g|png|gif|webp|bmp|heic|heif)(?:$|[?#])/i.test(name)) kind = 'image';
  else if (/\.(mp3|m4a|aac|wav|ogg|amr)(?:$|[?#])/i.test(name)) kind = 'audio';
  else if (/\.(mp4|mov|m4v|webm|avi)(?:$|[?#])/i.test(name)) kind = 'video';
  const kindLabel = { image: '照片', audio: '音频', video: '视频', file: '文件' }[kind];
  return Object.assign({}, file, {
    url: api.fileUrl(file),
    kind,
    kindLabel,
    displayName: file.name || kindLabel
  });
}

Component({
  properties: {
    files: { type: Array, value: [] },
    removable: Boolean,
    disabled: Boolean
  },
  data: {
    rows: [],
    playingIndex: -1,
    error: ''
  },
  observers: {
    files(files) {
      this.invalidate();
      this._imageRetried = {};
      this._context = session.context();
      this.setData({ rows: (files || []).map(displayFile), playingIndex: -1, error: '' });
    },
    disabled(disabled) {
      if (!disabled) return;
      this.invalidate();
      this.setData({ playingIndex: -1 });
    }
  },
  methods: {
    invalidate() {
      this._generation = (this._generation || 0) + 1;
      this._openSequence = (this._openSequence || 0) + 1;
      this.destroyAudio();
    },
    current(generation, sequence) {
      return this._alive && this._visible && !this.data.disabled && session.isCurrentContext(this._context) && generation === this._generation && (sequence === undefined || sequence === this._openSequence);
    },
    async access(source, generation, sequence) {
      const value = await api.request('GET', '/files/' + source.id + '/access');
      if (!this.current(generation, sequence)) return null;
      const file = displayFile(Object.assign({}, source, value));
      this.setData({ rows: this.data.rows.map(row => row.id === file.id ? file : row) });
      return file;
    },
    async imageError(event) {
      const source = this.data.rows[Number(event.currentTarget.dataset.index)];
      if (!source || this._imageRetried[source.id] || !this.current(this._generation)) return;
      this._imageRetried[source.id] = true;
      const generation = this._generation;
      try { await this.access(source, generation); } catch (error) { /* 点击预览时再次校验并显示错误。 */ }
    },
    destroyAudio() {
      const audio = this.audio;
      this.audio = null;
      this._audioIndex = -1;
      if (audio) audio.destroy();
    },
    remove(event) {
      if (this.data.disabled || !this.data.removable) return;
      this.triggerEvent('remove', { id: event.currentTarget.dataset.id });
    },
    async open(event) {
      if (!this.current(this._generation)) return;
      const index = Number(event.currentTarget.dataset.index);
      const source = this.data.rows[index];
      if (!source) return;
      if (source.kind === 'file') {
        wx.showToast({ title: '暂不支持此文件预览', icon: 'none' });
        return;
      }
      if (source.kind === 'audio' && this.audio && this._audioIndex === index) {
        if (this.data.playingIndex === index) { this.audio.pause(); return; }
      }
      const generation = this._generation;
      const sequence = this._openSequence = (this._openSequence || 0) + 1;
      const position = source.kind === 'audio' && this._audioIndex === index ? this.audio?.currentTime || 0 : 0;
      try {
        this.setData({ error: '' });
        if (source.kind === 'audio') {
          if (this.audio && this.data.playingIndex >= 0) this.audio.pause();
          // Modern WeChat ignores InnerAudioContext.obeyMuteSwitch; configure output after recording too.
          await new Promise((resolve, reject) => wx.setInnerAudioOption({
            obeyMuteSwitch: false, speakerOn: true, mixWithOther: false,
            success: resolve, fail: () => reject(new Error('音频输出设置失败，请点击重试或重新进入小程序'))
          }));
          if (!this.current(generation, sequence)) return;
          if (this.audio && this._audioIndex === index && Date.now() - this._audioAccessAt < 100000) {
            this.audio.volume = 1; this.audio.play(); return;
          }
        }
        let file;
        if (source.kind === 'image') {
          const images = await Promise.all(this.data.rows.filter(row => row.kind === 'image').map(row => this.access(row, generation, sequence)));
          if (!this.current(generation, sequence)) return;
          file = images.find(row => row && row.id === source.id);
          wx.previewImage({ current: file.url, urls: images.map(row => row.url), fail: () => { if (this.current(generation)) this.error(); } });
          return;
        }
        file = await this.access(source, generation, sequence);
        if (!file || !this.current(generation, sequence)) return;
        const url = file.url;
        if (file.kind === 'video') {
          wx.previewMedia({ sources: [{ url, type: 'video' }], fail: () => { if (this.current(generation)) this.error(); } });
          return;
        }
        this.destroyAudio();
        const audio = wx.createInnerAudioContext();
        this.audio = audio;
        this._audioIndex = index;
        this._audioAccessAt = Date.now();
        audio.volume = 1;
        audio.onPlay(() => {
          if (this.audio === audio) this.setData({ playingIndex: index });
        });
        audio.onPause(() => {
          if (this.audio === audio) this.setData({ playingIndex: -1 });
        });
        audio.onStop(() => {
          if (this.audio === audio) this.setData({ playingIndex: -1 });
        });
        audio.onError(error => {
          if (this.audio !== audio) return;
          this.destroyAudio();
          this.setData({ playingIndex: -1 });
          const code = Number(error && error.errCode);
          const reason = { 10001: '系统音频不可用', 10002: '音频网络读取失败', 10003: '音频文件不存在', 10004: '音频格式无法解码' }[code] || '音频播放失败';
          this.error(new Error(reason + (Number.isFinite(code) ? '（' + code + '）' : '') + '，请点击重试'));
        });
        audio.onEnded(() => {
          if (this.audio === audio) this.setData({ playingIndex: -1 });
        });
        this.setData({ playingIndex: -1 });
        let restored = false;
        if (position > 0) audio.onCanplay(() => {
          if (this.audio === audio && !restored) { restored = true; audio.seek(position); audio.play(); }
        });
        audio.src = url;
        if (!position) audio.play();
      } catch (error) {
        if (this.current(generation, sequence)) this.error(error);
      }
    },
    error(error) {
      const message = error && error.message || '附件暂时无法读取，请点击重试；登录过期时请重新登录';
      this.setData({ error: message });
      wx.showToast({ title: message, icon: 'none' });
    }
  },
  pageLifetimes: {
    show() { this._visible = true; },
    hide() {
      this._visible = false;
      this.invalidate();
      this.setData({ playingIndex: -1 });
    }
  },
  lifetimes: {
    attached() { this._alive = true; this._visible = true; this._context = session.context(); this._imageRetried = {}; },
    detached() {
      this._alive = false;
      this.invalidate();
    }
  }
});
