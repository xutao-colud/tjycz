import type { CapacitorConfig } from '@capacitor/cli'
const config: CapacitorConfig = {
  appId:'cn.tianjianyue.growth',
  appName:'天健悦成长',
  webDir:'dist-app',
  loggingBehavior:'debug',
  ios:{contentInset:'never',backgroundColor:'#f5f5f7'},
  android:{backgroundColor:'#f5f5f7'},
  plugins:{CapacitorHttp:{enabled:true}}
}
export default config
