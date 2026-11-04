<script setup lang="ts">
import { onBeforeUnmount,ref } from 'vue'
const props=defineProps<{disabled?:boolean}>()
const emit=defineEmits<{file:[file:File];busy:[busy:boolean]}>()
const recording=ref(false),requesting=ref(false),error=ref('')
let recorder:MediaRecorder|undefined,stream:MediaStream|undefined,timer:ReturnType<typeof setTimeout>|undefined,alive=true
const supported=!!navigator.mediaDevices?.getUserMedia&&typeof MediaRecorder!=='undefined'
function release(){stream?.getTracks().forEach(t=>t.stop());stream=undefined;clearTimeout(timer)}
function stop(){if(recorder?.state==='recording')recorder.stop()}
async function start(){
  if(props.disabled||recording.value||requesting.value)return
  error.value='';requesting.value=true;emit('busy',true)
  try{
    const current=await navigator.mediaDevices.getUserMedia({audio:true})
    if(!alive){current.getTracks().forEach(t=>t.stop());return}
    stream=current
    const mime=['audio/mp4','audio/webm;codecs=opus','audio/webm'].find(t=>MediaRecorder.isTypeSupported(t))
    recorder=new MediaRecorder(stream,mime?{mimeType:mime}:undefined)
    const chunks:Blob[]=[];let failed=false
    recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)}
    recorder.onerror=()=>{failed=true;error.value='录音中断，请重试或上传已有录音。';release();recording.value=false;emit('busy',false)}
    recorder.onstop=()=>{release();recording.value=false;emit('busy',false);if(!alive||failed)return;const type=recorder?.mimeType||chunks[0]?.type||'audio/webm';const blob=new Blob(chunks,{type});if(blob.size)emit('file',new File([blob],`口头作业-${Date.now()}.${type.includes('mp4')?'m4a':'webm'}`,{type}));else error.value='未录到声音，请重新录制。'}
    recorder.start();recording.value=true;timer=setTimeout(stop,120000)
  }catch{release();emit('busy',false);error.value='无法使用麦克风，请检查授权，或上传已有录音。'}
  finally{requesting.value=false}
}
function visibility(){if(document.hidden)stop()}
document.addEventListener('visibilitychange',visibility)
onBeforeUnmount(()=>{alive=false;stop();release();document.removeEventListener('visibilitychange',visibility)})
</script>
<template><div class="voice-recorder"><button v-if="supported" type="button" class="btn secondary" :disabled="disabled||requesting" @click="recording?stop():start()">{{requesting?'等待麦克风授权…':recording?'结束并上传录音':'录制口头作业'}}</button><small>{{recording?'正在录音，最长2分钟。切到后台将停止录音。':!supported?'当前环境不支持现场录音，请上传已有音频。':''}}</small><p v-if="error" role="alert">{{error}}</p></div></template>
