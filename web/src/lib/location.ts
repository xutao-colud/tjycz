import { Geolocation } from '@capacitor/geolocation'
import { contextVersion, contextChanged } from './api'

export async function currentLocation(signal?:AbortSignal) {
  const version=contextVersion.value
  if(signal?.aborted) throw contextChanged()
  try {
    const position=await Geolocation.getCurrentPosition({enableHighAccuracy:true,timeout:15000,maximumAge:0})
    if(signal?.aborted||version!==contextVersion.value) throw contextChanged()
    const {latitude,longitude,accuracy}=position.coords
    if(![latitude,longitude,accuracy,position.timestamp].every(Number.isFinite)) throw new Error('定位结果无效')
    return {latitude,longitude,accuracy,capturedAt:position.timestamp}
  } catch(error) {
    if(signal?.aborted||version!==contextVersion.value) throw contextChanged()
    const code=(error as {code?:string|number}).code
    throw new Error(code===1||code==='OS-PLUG-GLOC-0003'?'定位未授权，请在系统设置中允许精确定位后重试。':'无法获取当前位置，请开启定位服务，并在室外或窗边重试。')
  }
}
