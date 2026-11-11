const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const finance = require('../utils/course-finance');
assert.equal(finance.money('680.05'), 68005);
assert.equal(finance.priceTotal(6800,'10'),68000);assert.equal(finance.priceTotal(101,'0.5'),51);assert.throws(()=>finance.priceTotal(Number.MAX_SAFE_INTEGER,'10'));assert.throws(()=>finance.priceTotal(-1,'1'));
for (const value of ['-1','0.001','abc','Infinity','']) assert.throws(() => finance.money(value));
assert.throws(() => finance.units('0'));assert.equal(finance.units('0',true),0);
const group=finance.groups([{courseId:1,courseName:'成长营',unit:'节',balance:8,availableBalance:6},{courseId:2,parentCourseId:1,parentCourseName:'成长营',unit:'节',balance:2},{courseId:3,parentCourseId:1,parentCourseName:'成长营',unit:'小时',balance:1.5}]);
assert.equal(group.length,1);assert.equal(group[0].balanceText,'8 节 / 1.5 小时');
let def, calls=[], fail=true;
vm.runInNewContext(fs.readFileSync(require.resolve('../pages/lessons/index'),'utf8'),{require(name){
 if(name.endsWith('/course-finance'))return finance;
 if(name.endsWith('/api'))return {request:async(method,path,body)=>{calls.push({method,path,body});if(path==='/courses/catalog-options')return {hierarchySupported:true};if(path==='/enrollments')return {accountId:9};if(fail)throw Error('network');return {id:1};}};
 if(name.endsWith('/page'))return {definePage:v=>def=v,toast(){},confirm:async()=>true};
 if(name.endsWith('/teaching'))return {requestId:()=> 'stable-request'};
 return {};
}});
const p={...def,data:{...def.data,admin:true,studentIds:[1],courses:[{id:2,name:'书法',price:6800}],courseIndex:1,amountYuan:'680.05',paidUnits:'10',giftUnits:'2',renewalNote:'',renewals:[{id:1,courseName:'书法',status:'PENDING',money:'680.05',lessons:10,giftLessons:2}]},setData(v){Object.assign(this.data,v)},async act(fn){if(this.data.busy)return;this.data.busy=true;try{await fn()}catch(e){}finally{this.data.busy=false}}};
(async()=>{
 p.renewalUnits({detail:{value:'10'}});assert.equal(p.data.amountYuan,'680.00');p.setData({amountYuan:'680.05'});
 await p.toggleCatalog();assert.equal(p.data.catalogSupported,true);assert.equal(p.data.catalogOpen,true);calls=[];
 await p.saveRenewal();const first=calls.find(c=>c.path==='/renewals');assert.equal(first.body.amount,68005);assert.equal(first.body.accountId,9);assert.equal(p.data.renewalRequest,'stable-request');
 fail=false;calls=[];await p.saveRenewal();assert.equal(calls[1].body.requestId,first.body.requestId);assert.equal(p.data.renewalRequest,'');
 calls=[];await p.confirmRenewal({currentTarget:{dataset:{id:1}}});assert.equal(calls[0].path,'/renewals/1/confirm');
 p.data.admin=false;calls=[];await p.saveRenewal();await p.confirmRenewal({currentTarget:{dataset:{id:1}}});assert.equal(calls.length,0);
 p.data.admin=true;p.data.busy=true;await p.saveRenewal();assert.equal(calls.length,0);
 console.log('PASS course finance: exact cents, invalid values, unit-separated groups, enrollment/renewal, retry identity, confirmation, staff permissions and busy lock');
})().catch(e=>{console.error(e);process.exitCode=1});
