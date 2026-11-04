import assert from 'node:assert/strict'
import {createServer} from 'vite'

const server=await createServer({server:{middlewareMode:true,hmr:false},appType:'custom'})
try {
  let getPosition
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{geolocation:{getCurrentPosition:(...args)=>getPosition(...args)}}})
  const {currentLocation}=await server.ssrLoadModule('/src/lib/location.ts')
  const {contextVersion}=await server.ssrLoadModule('/src/lib/api.ts')
  getPosition=(success,_fail,options)=>{assert.equal(options.maximumAge,0);assert.equal(options.enableHighAccuracy,true);success({coords:{latitude:31.23,longitude:121.47,accuracy:5},timestamp:Date.now()})}
  assert.equal((await currentLocation()).latitude,31.23)
  getPosition=(_success,fail)=>fail({code:1})
  await assert.rejects(currentLocation(),/未授权/)
  getPosition=(_success,fail)=>fail({code:3})
  await assert.rejects(currentLocation(),/无法获取/)
  getPosition=success=>{contextVersion.value++;success({coords:{latitude:31.23,longitude:121.47,accuracy:5},timestamp:Date.now()})}
  await assert.rejects(currentLocation(),error=>error.name==='AbortError')
  const controller=new AbortController()
  getPosition=success=>{controller.abort();success({coords:{latitude:31.23,longitude:121.47,accuracy:5},timestamp:Date.now()})}
  await assert.rejects(currentLocation(controller.signal),error=>error.name==='AbortError')
  console.log('PASS: App/Web fresh precise location, denial, timeout and context cancellation')
} finally {await server.close()}
