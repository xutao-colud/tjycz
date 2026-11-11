const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
let definition,requests=[],messages=[],approve=async()=>true;
const today='2026-11-11';
vm.runInNewContext(fs.readFileSync(require.resolve('../pages/lessons/index'),'utf8'),{require(name){
 if(name.endsWith('/api'))return {request:async(method,path,body)=>{requests.push({method,path,body});return {status:'PENDING'}}};
 if(name.endsWith('/format'))return {today:()=>today};
 if(name.endsWith('/page'))return {definePage:v=>definition=v,toast:s=>messages.push(s),confirm:(...a)=>approve(...a)};
 return {};
}});
const page={...definition,data:{...definition.data,teacher:true,admin:false,date:today,studentIds:[1],lessonSessions:[{id:9,courseName:'书法',units:1.5,unit:'节',policyVersion:3,canConfirm:true}]},setData(v){Object.assign(this.data,v)},async act(f){if(this.data.busy)return;this.data.busy=true;try{await f()}finally{this.data.busy=false}}};
const event={currentTarget:{dataset:{id:9}}};
(async()=>{
 await page.confirmLesson(event);assert.equal(requests.length,1);assert.equal(requests[0].path,'/lesson-sessions/confirm');assert.equal(requests[0].body.expectedVersion,3);assert.equal(requests[0].body.studentId,1);assert.match(messages.at(-1),/待补扣/);assert.ok(!('units' in requests[0].body),'扣课量由服务端策略决定');
 requests=[];page.data.busy=true;await page.confirmLesson(event);assert.equal(requests.length,0);page.data.busy=false;
 page.data.lessonSessions[0].canConfirm=false;await page.confirmLesson(event);assert.equal(requests.length,0);page.data.lessonSessions[0].canConfirm=true;
 page.data.teacher=false;await page.confirmLesson(event);assert.equal(requests.length,0);page.data.teacher=true;
 approve=async()=>{page.data.studentIds=[2];return true};await page.confirmLesson(event);assert.equal(requests.length,0,'确认期间切换孩子不得给新孩子扣课');
 approve=async()=>true;page.data.studentIds=[1];page.data.date='2026-11-10';await page.confirmLesson(event);assert.equal(requests.length,0);
 console.log('PASS lesson confirmation: policy version, server-owned units, pending feedback, busy/role/state guards, child switch and historical reason');
})().catch(e=>{console.error(e);process.exitCode=1});
