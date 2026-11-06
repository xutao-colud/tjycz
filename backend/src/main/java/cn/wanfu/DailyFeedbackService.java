package cn.wanfu;

import java.time.LocalDate;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyFeedbackService {
    final Db db; final Security sec; final FinanceService finance;
    public DailyFeedbackService(Db db,Security sec,FinanceService finance) { this.db=db;this.sec=sec;this.finance=finance; }
    List<Map<String,Object>> list(LocalDate date,Long student) {
        if(!sec.actor().role().equals("PARENT")) sec.admin();
        if(student!=null) sec.student(student);
        String sql="SELECT s.id student_id,s.name student_name,s.class_id,cl.name class_name,cl.campus_id,u.id parent_id,u.name parent_name,? date,f.id,COALESCE(f.satisfaction,'SATISFIED') feedback_satisfaction,COALESCE(f.feedback_text,'') feedback_text,f.updated_at feedback_submitted_at FROM student s JOIN school_class cl ON cl.id=s.class_id JOIN parent_student ps ON ps.student_id=s.id JOIN app_user u ON u.id=ps.user_id AND u.role='PARENT' LEFT JOIN daily_parent_feedback f ON f.student_id=s.id AND f.parent_id=u.id AND f.date=? WHERE s.privacy_erased_at IS NULL AND "+sec.scope("s");
        List<Object> args=new ArrayList<>(List.of(date,date));
        if(sec.actor().role().equals("PARENT")) { sql+=" AND u.id=?";args.add(sec.actor().id()); }
        if(student!=null) { sql+=" AND s.id=?";args.add(student); }
        var rows=db.list(sql+" ORDER BY cl.id,s.id,u.id",args.toArray());
        for(var row:rows) { row.put("feedbackSubmitted",row.remove("id")!=null);row.put("canFeedback",date.equals(sec.today())&&sec.actor().role().equals("PARENT")); }
        return rows;
    }
    @Transactional public Map<String,Object> save(long student,LocalDate date,Map<String,Object> body) {
        sec.parent();sec.student(student);
        if(!date.equals(sec.today())) throw ApiException.bad("仅可评价今天的满意度");
        if(!Set.of("satisfaction","text","date").containsAll(body.keySet())) throw ApiException.bad("请选择满意或不满意，不再提交数字评分");
        String satisfaction=Db.required(body,"satisfaction",20),text=Db.text(body,"text");Db.choice(satisfaction,"SATISFIED","DISSATISFIED");
        if(text.length()>2000||satisfaction.equals("DISSATISFIED")&&text.isBlank()) throw ApiException.bad("不满意时请填写原因（最多2000字）");
        finance.lockStudent(student);
        var saved=db.optional("SELECT id FROM daily_parent_feedback WHERE student_id=? AND parent_id=? AND date=?",student,sec.actor().id(),date);
        if(saved==null) db.update("INSERT INTO daily_parent_feedback(student_id,parent_id,date,satisfaction,feedback_text,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",student,sec.actor().id(),date,satisfaction,text,sec.now(),sec.now());
        else db.update("UPDATE daily_parent_feedback SET satisfaction=?,feedback_text=?,updated_at=? WHERE id=?",satisfaction,text,sec.now(),saved.get("id"));
        sec.audit("DAILY_PARENT_FEEDBACK","student="+student+", date="+date);
        return list(date,student).get(0);
    }
    List<Map<String,Object>> classes(LocalDate date,Long classroom) {
        sec.admin();if(classroom!=null) sec.schoolClass(classroom);
        if(date.isAfter(sec.today())) throw ApiException.bad("未来日期尚无满意度统计");
        String sql="SELECT cl.id class_id,cl.name class_name,cl.campus_id,cp.name campus_name,COUNT(u.id) parent_count,COUNT(f.id) submitted_count,SUM(CASE WHEN f.satisfaction='DISSATISFIED' THEN 1 ELSE 0 END) dissatisfied_count FROM school_class cl JOIN campus cp ON cp.id=cl.campus_id LEFT JOIN student s ON s.class_id=cl.id AND s.privacy_erased_at IS NULL LEFT JOIN parent_student ps ON ps.student_id=s.id LEFT JOIN app_user u ON u.id=ps.user_id AND u.role='PARENT' LEFT JOIN daily_parent_feedback f ON f.student_id=s.id AND f.parent_id=u.id AND f.date=? WHERE "+sec.classScope("cl");
        var rows=classroom==null?db.list(sql+" GROUP BY cl.id,cl.name,cl.campus_id,cp.name ORDER BY cl.id",date):db.list(sql+" AND cl.id=? GROUP BY cl.id,cl.name,cl.campus_id,cp.name",date,classroom);
        for(var row:rows) {
            long total=Db.id(row,"parentCount"),submitted=Db.id(row,"submittedCount"),dissatisfied=Db.id(row,"dissatisfiedCount");
            row.put("date",date);row.put("defaultCount",total-submitted);row.put("satisfiedCount",total-dissatisfied);
            row.put("satisfactionPercent",total==0?null:BigDecimal.valueOf(total-dissatisfied).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),1,RoundingMode.HALF_UP));
        }
        return rows;
    }
}
