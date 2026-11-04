package cn.wanfu;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class GrowthController {
    final Db db; final Security sec; final GrowthService growth; final FollowUpService follow; final QueryService query; final LearningService learn;
    public GrowthController(Db db,Security sec,GrowthService growth,FollowUpService follow,QueryService query,LearningService learn) { this.db=db; this.sec=sec; this.growth=growth; this.follow=follow; this.query=query; this.learn=learn; }
    @GetMapping("/growth") public Object growth(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId,@RequestParam(defaultValue="false") boolean compact) { return query.growth(sec.date(date),studentId,compact); }
    @GetMapping("/workbench") public Object workbench(@RequestParam(required=false) String date,@RequestParam(required=false) Long classId) { return query.workbench(sec.date(date),classId); }
    @GetMapping("/homework/{id}") public Object homework(@PathVariable long id) { return learn.homework(id); }
    @PostMapping("/growth/notes") public Object note(@RequestBody Map<String,Object> b) { return growth.note(b); }
    @GetMapping("/growth/comparison-works") public Object comparisonWorks(@RequestParam long studentId) { return growth.comparisonWorks(studentId); }
    @PostMapping("/growth/comparisons") public Object compareWorks(@RequestBody Map<String,Object> b) { return growth.compareWorks(b); }
    @PostMapping("/growth/fitness-pause") public Object pause(@RequestBody Map<String,Object> b) { return growth.pause(b); }
    @GetMapping("/follow-ups") public Object list(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId,@RequestParam(defaultValue="ISSUE") String kind) { Db.choice(kind,"ISSUE","MESSAGE"); return growth.concerns(sec.date(date),studentId,kind); }
    @PostMapping("/follow-ups") public Object create(@RequestBody Map<String,Object> b) { return follow.create(b); }
    @GetMapping("/follow-ups/{id}") public Object detail(@PathVariable long id) { return follow.detail(id); }
    @PostMapping("/follow-ups/{id}/reply") public Object reply(@PathVariable long id,@RequestBody Map<String,Object> b) { return follow.reply(id,b); }
    @PostMapping("/follow-ups/{id}/read") public Object read(@PathVariable long id,@RequestBody Map<String,Object> b) { return follow.read(id,b); }
    @PutMapping("/follow-ups/{id}/status") public Object status(@PathVariable long id,@RequestBody Map<String,Object> b) { return follow.update(id,b); }
    @PutMapping("/follow-ups/{id}/owner") public Object owner(@PathVariable long id,@RequestBody Map<String,Object> b) { return follow.reassign(id,b); }
    @GetMapping("/growth/owners") public Object owners(@RequestParam long studentId) { return follow.owners(studentId); }
    @PostMapping("/homework/batch-review") public Object batchReview(@RequestBody Map<String,Object> b) {
        sec.staff(); if(!(b.get("items") instanceof List<?> list)||list.isEmpty()||list.size()>200) throw ApiException.bad("请选择1至200份作业");
        long task=Db.number(b,"taskId"); String request=Db.required(b,"requestId",70); List<Map<String,Object>> items=new ArrayList<>(); Set<Long> ids=new HashSet<>();
        for(Object value:list) {
            if(!(value instanceof Map<?,?> values)||!Set.of("id","expectedVersion","accuracy","comment").containsAll(values.keySet())) throw ApiException.bad("批改条目格式错误");
            Map<String,Object> item=new LinkedHashMap<>(); values.forEach((k,v)->item.put(k.toString(),v)); long id=Db.number(item,"id");
            if(!ids.add(id)||!item.containsKey("expectedVersion")) throw ApiException.bad("作业重复或缺少版本");
            var h=db.one("SELECT student_id,task_id FROM homework WHERE id=?",id); sec.student(Db.id(h,"studentId"));
            if(Db.id(h,"taskId")!=task) throw ApiException.bad("每批只能处理同一项作业任务");
            Map<String,Object> body=new LinkedHashMap<>(b); body.remove("items"); body.putAll(item); body.put("requestId",request+":"+id); items.add(body);
        }
        List<Object> results=new ArrayList<>();
        for(var item:items) {
            long id=Db.number(item,"id");
            try { results.add(Map.of("id",id,"success",true,"homework",learn.reviewHomework(id,item))); }
            catch(ApiException e) { results.add(Map.of("id",id,"success",false,"message",e.getMessage(),"status",e.status)); }
            catch(IllegalArgumentException|ArithmeticException e) { results.add(Map.of("id",id,"success",false,"message","本份作业参数格式错误，正确率和版本须为整数","status",400)); }
            catch(org.springframework.dao.DataAccessException e) { results.add(Map.of("id",id,"success",false,"message","数据正在处理，请重试")); }
        }
        return Map.of("results",results);
    }
    @PostMapping("/summaries/batch-publish") public Object publish(@RequestBody Map<String,Object> b) {
        sec.staff(); var ids=Db.ids(b,"summaryIds"); Set<Long> confirmed=new HashSet<>(Db.optionalIds(b,"confirmedConcernStudentIds"));
        for(long id:ids) { var d=learn.summary(id); growth.pastOrToday((java.time.LocalDate)d.get("date")); }
        List<Object> results=new ArrayList<>();
        for(long id:ids) {
            try {
                var d=learn.summary(id); long sid=Db.id(d,"studentId");
                if(!d.get("status").equals("PUBLISHED")&&growth.concerns((java.time.LocalDate)d.get("date"),sid,"ISSUE").stream().anyMatch(c->List.of("OPEN","IN_PROGRESS").contains(c.get("status")))&&!confirmed.contains(sid)) throw ApiException.bad("该学生有待跟进事项，请逐份核对原因和措施后确认");
                results.add(Map.of("id",id,"success",true,"summary",learn.publish(id,confirmed.contains(sid))));
            } catch(ApiException e) { results.add(Map.of("id",id,"success",false,"message",e.getMessage())); }
        }
        return Map.of("results",results);
    }
}
