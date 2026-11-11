const assert = require('node:assert/strict');
const session = require('../utils/session');
let component, app={globalData:{selectedCampusId:null}};
global.getApp=()=>app;global.getCurrentPages=()=>[{route:'pages/dashboard/index'}];global.Component=x=>component=x;
global.wx={getStorageSync(){},showToast(){},reLaunch(){}};
require('../components/app-nav/index');
const expected={PARENT:['今日','学习','消息'],TEACHER:['工作台','学生','消息'],ADMIN:['概览','待办','校务'],SUPER_ADMIN:['运营','课时费用','校区']};
const first={PARENT:['growth','homework','summaries','notifications','safety'],TEACHER:['workbench','students','attendance','homework','homework-drafts','scan','summaries'],ADMIN:['students','attendance','teacher-attendance','safety','summaries','follow-ups'],SUPER_ADMIN:['principal','campuses','students','teacher-attendance']};
for(const role of Object.keys(expected)){
 app.globalData.session={token:'role-fixture',user:{id:1,name:'角色测试',role,campusIds:[1],campuses:[{id:1,name:'主校区'}]}};
 const nav=Object.assign({},component.methods,{data:structuredClone(component.data),setData(p){Object.assign(this.data,p);}});nav.sync();
 assert.deepEqual(nav.data.tabs.map(x=>x.name),expected[role]);assert.deepEqual(nav.data.groups[0].items.map(x=>x.page),first[role]);
 const pages=nav.data.groups.flatMap(g=>g.items.map(i=>i.page));assert.equal(pages.length,new Set(pages).size,'同一功能不重复堆入口');
 assert.equal(session.homePage(),{PARENT:'growth',TEACHER:'workbench',ADMIN:'principal',SUPER_ADMIN:'dashboard'}[role]);
 if(role==='PARENT'){assert.ok(!pages.some(x=>['scan','workbench','campuses','class-reports','teacher-attendance'].includes(x)));}
 if(role==='TEACHER'){assert.ok(!pages.some(x=>['campuses','campus-reports','refunds','privacy','complaints'].includes(x)));}
 assert.equal(pages.includes('complaints'),role!=='TEACHER','投诉入口仅向家长及管理角色开放');
 assert.ok(pages.includes('family-services'));assert.ok(pages.includes('growth'));
 nav.navigate({currentTarget:{dataset:{page:'not-a-real-page'}}});
}
console.log('PASS role navigation: four distinct tab sets and priorities, existing home routes, no duplicate entries, parent/teacher restrictions.');

async function logoutChecks(){
 app.globalData.session={token:'fixture',user:{id:1,role:'SUPER_ADMIN',name:'测试'}};
 const nav=Object.assign({},component.methods,{data:structuredClone(component.data),setData(p){Object.assign(this.data,p);}});nav.sync();assert.equal(nav.data.owner,true);nav.toggle();assert.equal(nav.data.open,true);
 let calls=0,release;app.logout=()=>{calls++;return new Promise(resolve=>release=resolve);};
 const first=nav.signOut();await nav.signOut();assert.equal(calls,1);assert.equal(nav.data.accountBusy,true);release();await first;assert.equal(nav.data.open,false);assert.equal(nav.data.accountBusy,false);
 nav.toggle();app.logout=async()=>{throw new Error('网络中断');};await nav.signOut();assert.equal(nav.data.open,true,'退出失败保留页面与会话');assert.equal(nav.data.accountBusy,false);
 console.log('PASS management: owner layout, duplicate logout prevention, failed logout retains account view.');
}
logoutChecks().catch(error=>{console.error(error);process.exitCode=1;});
