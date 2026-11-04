import { Capacitor } from '@capacitor/core'
export const mobileApp = import.meta.env.VITE_APP_TARGET === 'native'
export const nativeApp = Capacitor.isNativePlatform()
export const serviceBase = import.meta.env.VITE_API_BASE_URL || '/api'
