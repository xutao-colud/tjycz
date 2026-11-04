import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createServer } from 'vite'
import { createRenderer,h,nextTick,ssrContextKey } from 'vue'
const server=await createServer({server:{middlewareMode:true,hmr:false},appType:'custom'})
try {
  const {validateServiceBase,serviceUrl,attachmentUrl}=await server.ssrLoadModule('/src/mobile/endpoints.ts')
  assert.equal(validateServiceBase('https://school.example/api/'),'https://school.example/api')
  assert.equal(validateServiceBase('http://192.168.1.10:18080/api',true),'http://192.168.1.10:18080/api')
  for(const url of ['','http://school.example/api','javascript:alert(1)','https://name:secret@school.example/api','https://school.example/api?token=secret','https://school.example/api#x'])assert.throws(()=>validateServiceBase(url))
  assert.equal(serviceUrl('/api','/health'),'/api/health')
  assert.equal(serviceUrl('https://school.example/api','/growth?date=2026-10-28'),'https://school.example/api/growth?date=2026-10-28')
  assert.throws(()=>serviceUrl('/api','//other.example'))
  assert.equal(attachmentUrl('https://school.example/api','/api/files/7?ticket=short'),'https://school.example/api/files/7?ticket=short')
  assert.equal(attachmentUrl('/api','/api/files/7?ticket=short'),'/api/files/7?ticket=short')
  for(const url of ['https://other.example/api/files/7','javascript:alert(1)','/api/../auth/logout','//other.example/file'])assert.throws(()=>attachmentUrl('https://school.example/api',url))
  console.log('PASS: HTTPS build boundary, credential rejection, API and private attachment URLs')

  const listeners=new Map()
  globalThis.document={hidden:false,addEventListener:(k,v)=>listeners.set(k,v),removeEventListener:k=>listeners.delete(k)}
  let getMedia,stops=0
  const stream=()=>({getTracks:()=>[{stop(){stops++}}]})
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:()=>getMedia()}}})
  globalThis.MediaRecorder=class {
    static isTypeSupported(type){return type==='audio/mp4'}
    constructor(){this.state='inactive';this.mimeType='audio/mp4'}
    start(){this.state='recording'}
    stop(){this.state='inactive';this.ondataavailable?.({data:new Blob(['fictional voice fixture'],{type:this.mimeType})});this.onstop?.()}
  }
  const {default:Recorder}=await server.ssrLoadModule('/src/mobile/VoiceRecorder.vue')
  const renderer=createRenderer({patchProp(){},insert(){},remove(){},createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null})
  const mount=()=>{
    let state;const files=[],busy=[]
    const component={...Recorder,setup(props,ctx){state=Recorder.setup(props,ctx);return state},render:()=>h('div')}
    const app=renderer.createApp({render:()=>h(component,{onFile:f=>files.push(f),onBusy:v=>busy.push(v)})})
    app.provide(ssrContextKey,{modules:new Set()});app.mount({});return {state,files,busy,app}
  }
  {
    getMedia=async()=>stream();const view=mount();await view.state.start();assert.equal(view.state.recording.value,true)
    view.state.stop();assert.equal(view.files.length,1);assert.equal(view.files[0].type,'audio/mp4');assert.match(view.files[0].name,/\.m4a$/);assert.ok(stops>0);assert.equal(view.busy.at(-1),false);view.app.unmount()
  }
  {
    getMedia=async()=>{throw new Error('permission denied fixture')};const view=mount();await view.state.start();assert.equal(view.files.length,0);assert.match(view.state.error.value,/授权/);assert.equal(view.busy.at(-1),false);view.app.unmount()
  }
  {
    let resolve;getMedia=()=>new Promise(r=>{resolve=r});const view=mount();const pending=view.state.start();view.app.unmount();const before=stops;resolve(stream());await pending;assert.ok(stops>before);assert.equal(view.files.length,0)
  }
  {
    getMedia=async()=>stream();const view=mount();await view.state.start();document.hidden=true;listeners.get('visibilitychange')();await nextTick();assert.equal(view.state.recording.value,false);assert.equal(view.files.length,1);view.app.unmount();document.hidden=false
  }
  console.log('PASS: voice creation, permission denial, unmount during permission request, background stop and track release')
  const android=await readFile('android/app/src/main/AndroidManifest.xml','utf8'),ios=await readFile('ios/App/App/Info.plist','utf8')
  assert.match(android,/android:usesCleartextTraffic="false"/);assert.match(android,/android:allowBackup="false"/)
  assert.doesNotMatch(ios,/NSAllowsArbitraryLoads/)
  assert.match(ios,/NSMicrophoneUsageDescription/)
  assert.match(android,/android.permission.ACCESS_FINE_LOCATION/);assert.match(android,/android.permission.ACCESS_COARSE_LOCATION/)
  assert.match(ios,/NSLocationWhenInUseUsageDescription/);assert.match(ios,/NSLocationAlwaysAndWhenInUseUsageDescription/)
  console.log('PASS: release transport restrictions and native permission declarations')
} finally {await server.close()}
