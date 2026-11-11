// Lucide v0.468.0 (ISC); regenerate from the project's existing Web dependency.
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../assets/icons');
const names={homework:'clipboard-check',mistakes:'book-open',students:'users',attendance:'calendar-check',fitness:'activity',points:'award',lessons:'calendar-days',summaries:'file-check',logo:'book-open',upload:'image-up',mic:'mic',empty:'inbox',home:'house',grid:'layout-grid',user:'user-round'};
for(const [name,icon] of Object.entries(names)){
 const source=fs.readFileSync(path.resolve(__dirname,'../../web/node_modules/lucide-vue-next/dist/esm/icons',icon+'.js'),'utf8');
 const match=source.match(/createLucideIcon\("[^"]+", ([\s\S]*?)\);/);if(!match)throw new Error(icon);
 const nodes=vm.runInNewContext('('+match[1]+')');
 for(const active of [false,true]){
  const body=nodes.map(([tag,attrs])=>'<'+tag+' '+Object.entries(attrs).filter(([k])=>k!=='key').map(([k,v])=>`${k}="${v}"`).join(' ')+'/>').join('');
  fs.writeFileSync(path.join(root,name+(active?'-active':'')+'.svg'),`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="${active?'#176b91':'#536168'}" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">${body}</svg>\n`);
 }
}
fs.copyFileSync(path.resolve(__dirname,'../../web/node_modules/lucide-vue-next/LICENSE'),path.join(root,'lucide-LICENSE'));
