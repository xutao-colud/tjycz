const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const character = require('../utils/child-character');
for (const gender of ['男','MALE','male',' male ']) assert.match(character(gender), /boy\.png$/);
for (const gender of ['女','FEMALE','female',' female ']) assert.match(character(gender), /girl\.png$/);
for (const gender of [undefined,null,'','OTHER','unknown',0,{},'王小明']) assert.match(character(gender), /neutral\.png$/);
const storage = new Map(); let app, definition, component, navigations = [], pendingNavigation, page, toast;
global.wx = { getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),stopPullDownRefresh(){},showToast(v){toast=v;},navigateTo(v){navigations.push(v.url);pendingNavigation=v;},reLaunch(v){navigations.push(v.url);} };
global.App = v=>app=v; global.Page = v=>definition=v; global.Component=v=>component=v;
global.getApp=()=>app; global.getCurrentPages=()=>page?[page]:[];
require('../app');app.onLaunch();
app.setSession({token:'parent-fixture',user:{id:1,role:'PARENT',name:'测试家长'}});
const api=require('../utils/api'); api.me=async()=>app.globalData.session.user;
const date=require('../utils/format').today();
const children=[{id:1,name:'一个用于验证长文本换行的孩子姓名',gender:'男',className:'长名称班级',campusName:'主校区',attendanceStatus:'PRESENT'}, {id:2,name:'女孩',gender:'FEMALE',attendanceStatus:'NOT_ARRIVED'},{id:3,name:'未知',gender:'OTHER',attendanceStatus:'ON_LEAVE'}];
const payload=id=>({date,student:children.find(c=>c.id===Number(id))||children[0],students:children,homework:[{id:1,status:'COMPLETED'},{id:2,status:'CORRECTION'}],timeline:[],concerns:[],fitness:[],accounts:[{id:1,availableBalance:9,warningThreshold:1,lowBalance:true},{id:2,availableBalance:1,warningThreshold:3,lowBalance:false}],pointsDelta:-2,pickup:{openAlertCount:1}});
function instance(def){return Object.assign({},def,def.methods,{data:structuredClone(def.data),setData(p,cb){Object.assign(this.data,p);if(cb)cb();}});}
function screen(){delete require.cache[require.resolve('../pages/growth/index')];require('../pages/growth/index');page=instance(definition);page.route="pages/growth/index";page.onLoad({});return page;}
async function main(){
 api.growth=async p=>payload(p.studentId);let p=screen();await p.onShow();
 assert.match(p.data.student.characterSrc,/boy/);assert.equal(p.data.homeworkPercent,50);assert.equal(p.data.metrics[1].value,'-2');assert.equal(p.data.pickupSummary.alertCount,1);assert.deepEqual(p.data.lowBalances.map(a=>a.id),[1],'服务端两周预警优先于旧固定阈值，包括明确的不预警');
 const queue=[];api.growth=p=>new Promise((resolve,reject)=>queue.push({resolve,reject,p}));
 const one=p.chooseChild({detail:{value:1}});assert.equal(p.data.student,null);assert.equal(p.data.loading,true);
 const two=p.chooseChild({detail:{value:2}});queue[1].resolve(payload(3));await two;queue[0].resolve(payload(2));await one;
 assert.equal(p.data.student.id,3);assert.match(p.data.student.characterSrc,/neutral/,'过期请求不能把女孩资料带回来');
 const failure=p.chooseChild({detail:{value:1}});queue[2].reject(new Error('网络中断，请重试'));await failure;assert.equal(p.data.student,null);assert.match(p.data.error,/网络中断/);
 api.growth=async p=>payload(p.studentId);await p.retry();assert.equal(p.data.student.id,2);assert.match(p.data.student.characterSrc,/girl/);
 navigations=[];p.application({currentTarget:{dataset:{page:'homework'}}});p.application({currentTarget:{dataset:{page:'homework'}}});assert.equal(navigations.length,1);assert.match(navigations[0],/studentId=2/);
 pendingNavigation.fail();p.toggleHistory();assert.equal(navigations.length,2);await p.onShow();p.pickup();assert.equal(navigations.length,3,'返回后可以再次跳转');
 const before=navigations.length;p.onUnload();p.toggleHistory();assert.equal(navigations.length,before,'卸载后不跳转');
 p=screen();await p.onShow();let release;let calls=0;const action=()=>{calls++;return new Promise(r=>release=r);};const first=p.act(action,'已保存',false);await p.act(action,'已保存',false);assert.equal(calls,1);release();await first;assert.equal(toast.icon,'success');
 require('../components/app-nav/index');const nav=instance(component);nav.sync();nav.toggleMotion({detail:{value:true}});assert.equal(p.data.reducedMotion,true);assert.equal(require('../utils/parent-motion').reduced(),true);
 const next=screen();await next.onShow();assert.equal(next.data.reducedMotion,true);next.toggleInsights();assert.equal(next.data.insightsOpen,true);nav.toggleMotion({detail:{value:false}});assert.equal(next.data.reducedMotion,false);
 api.growth=async()=>({student:null,students:[]});await next.refresh();assert.equal(next.data.student,null);assert.equal(next.data.loading,false);
 api.growth=async()=>{throw new Error('403 无权访问');};await next.refresh();assert.match(next.data.error,/403/);assert.equal(next.data.student,null);
 api.growth=async p=>payload(p.studentId);const hidden=screen();const deferred=[];api.growth=()=>new Promise(r=>deferred.push(r));const loading=hidden.refresh();hidden.onUnload();deferred[0](payload(1));await loading;assert.equal(hidden.data.student,null,'卸载后不写回孩子资料');
 for(const role of ['TEACHER','ADMIN','SUPER_ADMIN']){app.setSession({token:'fixture-'+role,user:{id:2,role,name:role}});nav.sync();const saved=require('../utils/parent-motion').reduced();nav.toggleMotion({detail:{value:!saved}});assert.equal(require('../utils/parent-motion').reduced(),saved,'非家长不修改此偏好');assert.equal(nav.data.parent,false);}
 console.log('PASS parent UI: gender fallback, 50% real progress, rapid child switching, stale responses, failures/retry, empty/permission states, navigation debounce/return, duplicate submissions, motion preference and staff isolation.');
}
main().catch(e=>{console.error(e);process.exitCode=1;});
