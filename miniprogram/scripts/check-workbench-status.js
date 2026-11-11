const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
let definition;
const date='2026-11-11';
let payload={classId:1,classes:[{id:1,name:'验收班级',campusName:'验收校区'}],students:[{id:1,attendanceStatus:'PRESENT'},{id:2,attendanceStatus:'NOT_ARRIVED'},{id:3,attendanceStatus:'ON_LEAVE'},{id:4,attendanceStatus:'LEFT'}],homework:[{studentId:1,taskId:1,status:'COMPLETED'},{studentId:1,taskId:2,status:'PENDING_REVIEW'},{studentId:1,taskId:3,status:'CORRECTION'},{studentId:2,taskId:1,status:'PENDING_RECHECK'},{studentId:4,taskId:1,status:'COMPLETED'}],concerns:[{studentId:1,status:'OPEN'}],messages:[{studentId:1,status:'OPEN',lastActorRole:'PARENT'}],summaries:[],readOnly:false};
vm.runInNewContext(fs.readFileSync(require.resolve('../pages/workbench/index'),'utf8'),{require(name){
 if(name.endsWith('/api'))return {workbench:async()=>structuredClone(payload),fileUrl:()=>''};
 if(name.endsWith('/growth'))return {draft:()=>({}),clock:()=>'',primary:()=>'',status:s=>s,decorate:s=>s,businessText:()=>''};
 if(name.endsWith('/format'))return {today:()=>date,decorate:s=>s,label:s=>s};
 if(name.endsWith('/page'))return {definePage:v=>definition=v};
 return {};
}});
const p={...definition,data:structuredClone(definition.data),setData(v){Object.assign(this.data,v)}};
p.data.date=date;p.data.studentIds=[];
const event=index=>({currentTarget:{dataset:{index}}});
(async()=>{
 Object.assign(p.data,await p.loadData());
 assert.equal(p.data.roster[0].homeworkPercent,33);assert.equal(p.data.roster[2].homeworkPercent,0);assert.equal(p.data.roster[3].homeworkPercent,100);
 assert.equal(p.data.statusTiles.map(t=>t.count).join(','),'1,1,1,1');
 p.chooseStatus(event(2));assert.equal(p.data.students.map(s=>s.id).join(','),'1');
 p.chooseStatus(event(3));assert.equal(p.data.students.map(s=>s.id).join(','),'1','同一孩子可同时待批改和待订正');
 p.data.studentIds=[1];p.chooseStatusGroup(event(1));assert.equal(p.data.filterIndex,0);assert.equal(p.data.selectedCount,1);p.chooseStatus(event(7));assert.equal(p.data.students[0].id,3);
 p.chooseStatusGroup(event(2));assert.equal(p.data.statusTiles.map(t=>t.index).join(','),'5,8,9');assert.equal(p.data.statusTiles[0].count,1);
 p.data.busy=true;p.chooseStatusGroup(event(0));assert.equal(p.data.statusGroup,2);p.data.busy=false;p.chooseStatus(event(100));assert.equal(p.data.filterIndex,0);
 payload={...payload,students:[],homework:[],concerns:[],messages:[]};Object.assign(p.data,await p.loadData());assert.equal(p.data.students.length,0);assert.ok(p.data.statusTiles.every(t=>t.count===0));
 console.log('PASS grouped statuses: original filters, overlapping statuses, real completion ratio, no-task/empty state, group reset, batch selection and busy lock');
})().catch(e=>{console.error(e);process.exitCode=1});
