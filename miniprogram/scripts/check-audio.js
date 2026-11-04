/* Minimal regression at real component/WXML + wx media boundaries.
 * Run: node miniprogram/scripts/check-audio.js
 * No network writes, no source changes, and no real credentials.
 * This checks UI wiring/lifecycle; it does not emulate WeChat's audio decoder.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const mp3 = { id: 5, name: 'oral.mp3', contentType: 'audio/mpeg', size: 19928, url: '/api/files/5?ticket=expired-fixture' };
const video = { id: 4, name: 'oral.mp4', contentType: 'video/mp4', size: 564565, url: '/api/files/4?ticket=expired-fixture' };
let definition;
let audioContexts = [];
let toasts = [];
let recorderListeners = {};
let recordingOptions;
let accessRequests = [];
let previews = [];
let pendingRequest;
let pendingUpload;
let audioOptions;
let pendingAudioOptions;
const app = { globalData: { session: { token: 'local-fixture', user: { role: 'PARENT' } }, contextVersion: 0 } };
function respond(options) {
  const id = Number(options.url.match(/\/files\/(\d+)\/access/)[1]);
  const file = [mp3.id, 6].includes(id) ? { ...mp3, id } : id === video.id ? video : { id, name: id + '.png', contentType: 'image/png', size: 200 };
  options.success({ statusCode: 200, data: { ...file, url: '/api/files/' + id + '?ticket=fresh-' + accessRequests.length, expiresAt: '2099-01-01T00:00:00' } });
}
const recorder = {
  onStart(fn) { recorderListeners.start = fn; },
  onStop(fn) { recorderListeners.stop = fn; },
  onError(fn) { recorderListeners.error = fn; },
  offStart() {}, offStop() {}, offError() {},
  start(options) { recordingOptions = options; recorderListeners.start(); },
  stop() { recorderListeners.stop({ tempFilePath: '/tmp/recorded.mp3', fileSize: mp3.size }); }
};
global.getApp = () => app;
global.Component = value => { definition = value; };
global.wx = {
  showToast(value) { toasts.push(value.title); },
  authorize(options) { options.success(); },
  getRecorderManager() { return recorder; },
  uploadFile(options) { if (pendingUpload) pendingUpload(options); else options.success({ statusCode: 200, data: JSON.stringify(mp3) }); },
  request(options) { accessRequests.push(options); if (pendingRequest) pendingRequest(options); else respond(options); },
  previewImage(options) { previews.push(options); },
  previewMedia(options) { previews.push(options); },
  setInnerAudioOption(options) {
    assert.ok(audioContexts.every(audio => audio.destroyed || !audio.playing), '切换输出选项前必须先暂停播放');
    if (pendingAudioOptions) return pendingAudioOptions(options);
    audioOptions = options;
    options.success();
  },
  createInnerAudioContext() {
    const handlers = {};
    const context = {
      src: '', currentTime: 0, obeyMuteSwitch: true, playing: false, destroyed: false, playCalls: 0, pauseCalls: 0,
      onCanplay(fn) { handlers.canplay = fn; }, seek(time) { this.currentTime = time; },
      onPlay(fn) { handlers.play = fn; }, onPause(fn) { handlers.pause = fn; },
      onStop(fn) { handlers.stop = fn; }, onEnded(fn) { handlers.ended = fn; }, onError(fn) { handlers.error = fn; },
      play() { assert.ok(!this.destroyed, 'cannot play destroyed audio'); this.playCalls++; this.audible = audioOptions.obeyMuteSwitch === false && audioOptions.speakerOn === true; },
      pause() { this.pauseCalls++; this.playing = false; handlers.pause?.(); },
      stop() { this.playing = false; handlers.stop?.(); },
      destroy() { this.destroyed = true; this.playing = false; },
      emit(name, event) { if (name === 'play') this.playing = true; if (name === 'ended' || name === 'error') this.playing = false; handlers[name]?.(event); }
    };
    audioContexts.push(context);
    return context;
  }
};
function load(component) {
  require(path.join(root, 'components', component, 'index.js'));
  return definition;
}
const filesDefinition = load('file-list');
const mediaDefinition = load('media-field');
function mount(def, props = {}) {
  const defaults = Object.fromEntries(Object.entries(def.properties || {}).map(([key, value]) => [key, value?.value ?? (value === Boolean ? false : undefined)]));
  const component = Object.assign({}, def.methods, {
    data: Object.assign(defaults, structuredClone(def.data), props), events: [],
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { this.events.push({ name, detail }); }
  });
  def.lifetimes?.attached?.call(component);
  if (def.observers?.files) def.observers.files.call(component, component.data.files || []);
  return component;
}
function setFiles(component, files) {
  component.data.files = files;
  filesDefinition.observers.files.call(component, files);
}
function tap(component, index = 0) { return component.open({ currentTarget: { dataset: { index } } }); }
function mountUploadedPlayer(media) {
  const template = fs.readFileSync(path.join(root, 'components/media-field/index.wxml'), 'utf8');
  const child = [...template.matchAll(/<file-list\b[^>]*>/g)].find(match => /files="{{\s*files\s*}}"/.test(match[0]));
  assert.ok(child, '录音上传成功后缺少可点击试听的 file-list（当前只有“已上传”和“移除”）');
  return mount(filesDefinition, { files: media.data.files, removable: true });
}
const cases = [
  ['recording preview configures current WeChat audio output before playback', async () => {
    const player = mount(filesDefinition, { files: [mp3] });
    await tap(player);
    const audio = audioContexts.at(-1);
    assert.equal(audio.audible, true, '现代微信忽略实例 obeyMuteSwitch；须先设置全局静音策略和扬声器输出');
    assert.equal(audio.volume, 1);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['failed audio output setup is visible and cannot pretend to play', async () => {
    pendingAudioOptions = options => options.fail({ errMsg: 'setInnerAudioOption:fail' });
    const player = mount(filesDefinition, { files: [mp3] });
    await tap(player);
    assert.equal(audioContexts.length, 0);
    assert.match(player.data.error, /音频输出.*重试/);
    assert.equal(player.data.playingIndex, -1);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['leaving while audio output is being configured cannot start late playback', async () => {
    let complete;
    pendingAudioOptions = options => { complete = options.success; };
    const player = mount(filesDefinition, { files: [mp3] });
    const pending = tap(player);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(typeof complete, 'function');
    filesDefinition.pageLifetimes.hide.call(player);
    complete();
    await pending;
    assert.equal(audioContexts.length, 0);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['switching recordings pauses playback before configuring the output route', async () => {
    const player = mount(filesDefinition, { files: [mp3, { ...mp3, id: 6 }] });
    await tap(player);
    const first = audioContexts.at(-1);
    first.emit('play');
    await tap(player, 1);
    assert.equal(first.pauseCalls, 1); assert.equal(first.destroyed, true);
    const next = audioContexts.at(-1);
    assert.notEqual(next, first); assert.equal(next.audible, true);
    next.emit('error', { errCode: 10004 });
    assert.match(player.data.error, /音频格式无法解码.*10004/);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['saved MP3 is classified and sent to audio context', async () => {
    const player = mount(filesDefinition, { files: [video, mp3] });
    assert.equal(player.data.rows[1].kind, 'audio');
    await tap(player, 1);
    const audio = audioContexts.at(-1);
    assert.equal(new URL(audio.src).pathname, '/api/files/5');
    assert.equal(audio.playCalls, 1);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['record -> upload -> immediate preview can start playback', async () => {
    const media = mount(mediaDefinition, { audioEnabled: true });
    media.record();
    assert.equal(recordingOptions.format, 'mp3');
    media.stopRecord();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(media.data.files[0].id, 5);
    assert.ok(media.events.some(event => event.name === 'change' && event.detail.ids.includes(5)));
    try {
      const player = mountUploadedPlayer(media);
      await tap(player);
      assert.equal(audioContexts.at(-1).playCalls, 1);
      filesDefinition.lifetimes.detached.call(player);
    } finally { mediaDefinition.lifetimes.detached.call(media); }
  }],
  ['upload preview keeps remove working and blocks actions during recording/upload', async () => {
    const media = mount(mediaDefinition, { files: [mp3] });
    const player = mountUploadedPlayer(media);
    const template = fs.readFileSync(path.join(root, 'components/media-field/index.wxml'), 'utf8');
    assert.match(template, /<file-list\b[^>]*\bremovable\b[^>]*bindremove="remove"/);
    const rowTemplate = fs.readFileSync(path.join(root, 'components/file-list/index.wxml'), 'utf8');
    assert.match(rowTemplate, /catchtap="remove"/, '移除操作必须阻止触发行播放');
    assert.equal(typeof player.remove, 'function', '共享预览应能移除上传附件');
    player.remove({ currentTarget: { dataset: { id: 5 } } });
    const event = player.events.find(event => event.name === 'remove');
    assert.equal(event?.detail.id, 5);
    media.remove({ detail: event.detail });
    assert.deepEqual(media.data.files, []);
    assert.deepEqual(media.events.at(-1), { name: 'change', detail: { ids: [] } });
    player.data.disabled = true;
    const eventCount = player.events.length;
    player.remove({ currentTarget: { dataset: { id: 5 } } });
    await tap(player);
    assert.equal(player.events.length, eventCount);
    assert.equal(audioContexts.length, 0);
    mediaDefinition.lifetimes.detached.call(media);
  }],
  ['playing state follows native onPlay; same-file tap pauses and resumes', async () => {
    const player = mount(filesDefinition, { files: [mp3] });
    await tap(player);
    const audio = audioContexts.at(-1);
    assert.equal(player.data.playingIndex, -1, '加载期间不能声称正在播放');
    assert.equal(audioOptions.obeyMuteSwitch, false, '试听应通过有效接口在静音开关开启时仍可听见');
    audio.emit('play');
    assert.equal(player.data.playingIndex, 0);
    await tap(player);
    assert.equal(audio.pauseCalls, 1, '再次点击应暂停，不能销毁并从头播放');
    assert.equal(player.data.playingIndex, -1);
    await tap(player);
    assert.equal(audioContexts.length, 1, '继续播放应保留当前进度，无需 seek');
    assert.equal(audio.playCalls, 2);
    assert.equal(player.data.playingIndex, -1);
    audio.emit('play');
    assert.equal(player.data.playingIndex, 0);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['file replacement or reorder destroys old playback and ignores stale callbacks', async () => {
    const player = mount(filesDefinition, { files: [video, mp3] });
    await tap(player, 1);
    const old = audioContexts.at(-1);
    old.emit('play');
    setFiles(player, [mp3, video]);
    assert.equal(old.destroyed, true, 'files 属性变化必须停止旧音频');
    assert.equal(player.data.playingIndex, -1, '重排后不能把视频误标成正在播放');
    old.emit('play');
    assert.equal(player.data.playingIndex, -1, '销毁后的迟到回调不能恢复播放状态');
    await tap(player, 0);
    assert.equal(new URL(audioContexts.at(-1).src).pathname, '/api/files/5');
    assert.equal(audioContexts.length, 2);
    setFiles(player, []);
    assert.equal(audioContexts.at(-1).destroyed, true, '移除或 resetKey 清空文件后不能继续播放');
    assert.deepEqual(player.data.rows, []);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['page hide and detach release the decoder; returning can play again', async () => {
    const player = mount(filesDefinition, { files: [mp3] });
    await tap(player);
    const old = audioContexts.at(-1);
    old.emit('play');
    filesDefinition.pageLifetimes.hide.call(player);
    assert.equal(old.destroyed, true, 'onHide 必须销毁音频而非仅 stop');
    assert.equal(player.data.playingIndex, -1);
    old.emit('play');
    old.emit('error', { errCode: 10004 });
    assert.equal(toasts.length, 0, '离页后旧错误回调不能再提示');
    assert.equal(player.data.playingIndex, -1);
    filesDefinition.pageLifetimes.show.call(player);
    await tap(player);
    const current = audioContexts.at(-1);
    assert.notEqual(current, old);
    current.emit('play');
    filesDefinition.lifetimes.detached.call(player);
    assert.equal(current.destroyed, true);
    current.emit('play');
    assert.notEqual(player.audio, current);
  }],
  ['native playback error gives actionable retry and a fresh playback attempt', async () => {
    const player = mount(filesDefinition, { files: [mp3] });
    await tap(player);
    const failed = audioContexts.at(-1);
    failed.emit('error', { errCode: 10004 });
    assert.equal(player.data.playingIndex, -1);
    assert.equal(failed.destroyed, true, '失败的解码器必须释放，下一次点击能重新加载');
    assert.ok(toasts.some(title => /音频.*重试/.test(title)), '应提示点击重试，不能一律归因会话链接过期');
    await tap(player);
    const retried = audioContexts.at(-1);
    assert.notEqual(retried, failed);
    retried.emit('play');
    assert.equal(player.data.playingIndex, 0);
    failed.emit('error', { errCode: 10004 });
    assert.equal(player.data.playingIndex, 0, '旧错误不能影响重试后的播放');
    retried.emit('ended');
    assert.equal(player.data.playingIndex, -1);
    await tap(player);
    assert.ok(audioContexts.at(-1).playCalls >= 1);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['starting another recording/upload stops any active preview', async () => {
    const player = mount(filesDefinition, { files: [mp3] });
    await tap(player);
    const audio = audioContexts.at(-1);
    audio.emit('play');
    player.data.disabled = true;
    filesDefinition.observers.disabled?.call(player, true);
    assert.equal(audio.destroyed, true, '录音或上传开始时应停止试听，避免录入扬声器声音');
    assert.equal(player.data.playingIndex, -1);
    await tap(player);
    assert.equal(audioContexts.length, 1);
    player.data.disabled = false;
    filesDefinition.observers.disabled?.call(player, false);
    await tap(player);
    assert.equal(audioContexts.length, 2);
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['new previews refresh short tickets with Bearer auth; images refresh the entire gallery', async () => {
    const image = { id: 7, name: 'work.png', contentType: 'image/png', size: 200, url: '/api/files/7?ticket=expired' };
    const player = mount(filesDefinition, { files: [image, { ...image, id: 8 }] });
    await tap(player);
    assert.equal(accessRequests.length, 2);
    assert.equal(accessRequests[0].header.Authorization, 'Bearer local-fixture');
    assert.equal(previews.length, 1);
    assert.ok(previews[0].urls.every(url => /[?]ticket=fresh-/.test(url) && !/[?&]token=/.test(url)));
    const videoPlayer = mount(filesDefinition, { files: [video] });
    await tap(videoPlayer);
    assert.match(previews.at(-1).sources[0].url, /ticket=fresh-/);
    filesDefinition.lifetimes.detached.call(player);
    filesDefinition.lifetimes.detached.call(videoPlayer);
  }],
  ['expired paused audio refreshes its ticket and restores its listening position', async () => {
    const player = mount(filesDefinition, { files: [mp3] });
    await tap(player);
    const old = audioContexts.at(-1);
    old.currentTime = 23;
    old.emit('play');
    await tap(player);
    player._audioAccessAt -= 121000;
    await tap(player);
    const refreshed = audioContexts.at(-1);
    assert.notEqual(refreshed, old);
    assert.equal(old.destroyed, true);
    assert.match(refreshed.src, /ticket=fresh-/);
    refreshed.emit('canplay');
    assert.equal(refreshed.currentTime, 23);
    assert.equal(refreshed.playCalls, 1);
    refreshed.emit('canplay');
    assert.equal(refreshed.playCalls, 1, '反复 canplay 不应重置播放进度');
    filesDefinition.lifetimes.detached.call(player);
  }],
  ['late access responses cannot open or refill replaced, hidden, detached or changed-context files', async () => {
    for (const invalidate of [
      player => setFiles(player, [video]),
      player => filesDefinition.pageLifetimes.hide.call(player),
      player => filesDefinition.lifetimes.detached.call(player),
      () => { app.globalData.contextVersion++; }
    ]) {
      const player = mount(filesDefinition, { files: [mp3] });
      let request;
      pendingRequest = options => { request = options; };
      const pending = tap(player);
      await new Promise(resolve => setImmediate(resolve));
      invalidate(player);
      respond(request);
      await pending;
      assert.equal(audioContexts.length, 0);
      assert.equal(toasts.length, 0);
      assert.ok(player.data.rows.every(row => !/ticket=fresh-/.test(row.url)), '迟到响应不能回填旧附件');
      filesDefinition.lifetimes.detached.call(player);
    }
    pendingRequest = null;
  }],
  ['resetKey while uploading rejects the late upload result', async () => {
    const media = mount(mediaDefinition);
    let upload;
    pendingUpload = options => { upload = options; };
    const pending = media.upload([{ tempFilePath: '/tmp/recorded.mp3', size: mp3.size }]);
    mediaDefinition.observers.resetKey.call(media);
    upload.success({ statusCode: 200, data: JSON.stringify(mp3) });
    await pending;
    assert.deepEqual(media.data.files, []);
    assert.equal(media.data.busy, false);
    assert.ok(!media.events.some(event => event.name === 'change'), '旧上传不能进入新表单');
    pendingUpload = null;
    mediaDefinition.lifetimes.detached.call(media);
  }]
];
(async () => {
  let failures = 0;
  for (const [name, run] of cases) {
    audioContexts = [];
    toasts = [];
    accessRequests = []; previews = []; pendingRequest = null; pendingUpload = null;
    audioOptions = { obeyMuteSwitch: true, speakerOn: false }; pendingAudioOptions = null;
    try { await run(); console.log('PASS ' + name); }
    catch (error) { failures++; console.log('FAIL ' + name + ': ' + String(error.message).replace(/token=[^&\s]+/g, 'token=<REDACTED>')); }
  }
  console.log(`${cases.length - failures}/${cases.length} passed; ${failures} failed`);
  process.exitCode = failures ? 1 : 0;
})();
