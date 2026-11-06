package cn.wanfu;

import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
    final Db db; final Security sec; final LearningService learn; final FinanceService finance; final FileService files; final GrowthService growth;
    public QueryService(Db db,Security sec,LearningService learn,FinanceService finance,FileService files,GrowthService growth) { this.db=db; this.sec=sec; this.learn=learn; this.finance=finance; this.files=files; this.growth=growth; }
    String activeScope() { return sec.scope("s")+" AND s.privacy_erased_at IS NULL"; }
    String studentQuery(LocalDate date) {
        // Dates are parsed LocalDate values, never untrusted SQL fragments.
        String day="'"+date+"'";
        return "SELECT s.id,s.name,s.avatar_file_id,s.gender,s.grade,s.class_id,cl.name class_name,cl.campus_id,(SELECT name FROM campus WHERE id=cl.campus_id) campus_name,s.parent_name,s.parent_phone,CASE WHEN a.status IS NOT NULL THEN a.status WHEN EXISTS(SELECT 1 FROM leave_request lr WHERE lr.student_id=s.id AND lr.leave_date="+day+" AND lr.status='APPROVED') THEN 'ON_LEAVE' ELSE 'NOT_ARRIVED' END attendance_status,a.check_in_time,a.check_out_time,s.points,"+
            "(SELECT COUNT(*) FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=s.id AND t.date="+day+") homework_total,"+
            "(SELECT COUNT(*) FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=s.id AND t.date="+day+" AND h.status='COMPLETED') homework_completed,"+
            "COALESCE((SELECT ac.balance FROM lesson_account ac JOIN course c ON c.id=ac.course_id WHERE ac.student_id=s.id AND c.type='EVENING' ORDER BY ac.id LIMIT 1),0) lesson_balance "+
            "FROM student s JOIN school_class cl ON cl.id=s.class_id LEFT JOIN attendance a ON a.student_id=s.id AND a.date="+day;
    }
    List<Map<String,Object>> students(Long classId,String keyword,LocalDate date) {
        List<Object> args=new ArrayList<>(); String sql=studentQuery(date)+" WHERE "+activeScope();
        if(classId!=null) { if(!sec.actor().role().equals("PARENT")) sec.schoolClass(classId); sql+=" AND s.class_id=?"; args.add(classId); }
        if(keyword!=null&&!keyword.isBlank()) { sql+=" AND (s.name LIKE ? OR s.parent_name LIKE ? OR s.parent_phone LIKE ?)"; for(int i=0;i<3;i++) args.add("%"+keyword+"%"); }
        return db.list(sql+" ORDER BY s.id",args.toArray()).stream().map(this::decorateStudent).toList();
    }
    Map<String,Object> decorateStudent(Map<String,Object> row) { Object id=row.get("avatarFileId"); row.put("avatar",id!=null&&files.canRead(((Number)id).longValue())?files.access(((Number)id).longValue()):null); if(row.get("avatar")==null) row.put("avatarFileId",null); return row; }
    Map<String,Object> student(long id) { sec.student(id); return decorateStudent(db.one(studentQuery(sec.today())+" WHERE s.id=?",id)); }
    List<Map<String,Object>> classes() {
        return db.list("SELECT cl.id,cl.name,cl.teacher_name,cl.campus_id,(SELECT name FROM campus WHERE id=cl.campus_id) campus_name,(SELECT COUNT(*) FROM student s WHERE s.class_id=cl.id AND "+activeScope()+") student_count FROM school_class cl WHERE "+sec.classScope("cl")+" ORDER BY cl.id");
    }
    List<Map<String,Object>> attendance(LocalDate date,Long classId,Long studentId) {
        if(classId!=null&&!sec.actor().role().equals("PARENT")) sec.schoolClass(classId);
        if(studentId!=null) sec.student(studentId);
        String day="'"+date+"'";
        String query="SELECT a.id,s.id student_id,s.name student_name,cl.name class_name,cl.campus_id,(SELECT name FROM campus WHERE id=cl.campus_id) campus_name,? date,CASE WHEN a.status IS NOT NULL THEN a.status WHEN EXISTS(SELECT 1 FROM leave_request lr WHERE lr.student_id=s.id AND lr.leave_date="+day+" AND lr.status='APPROVED') THEN 'ON_LEAVE' ELSE 'NOT_ARRIVED' END status,a.check_in_time,a.check_out_time,COALESCE(a.source,'MANUAL') source FROM student s JOIN school_class cl ON cl.id=s.class_id LEFT JOIN attendance a ON a.student_id=s.id AND a.date=? WHERE "+activeScope();
        List<Object> args=new ArrayList<>(List.of(date,date));
        if(classId!=null) { query+=" AND s.class_id=?"; args.add(classId); }
        if(studentId!=null) { query+=" AND s.id=?"; args.add(studentId); }
        var rows=db.list(query+" ORDER BY s.id",args.toArray());
        for(var row:rows){Object id=row.get("id");row.put("checkInPhotos",id==null?List.of():files.linked("ATTENDANCE_IN",((Number)id).longValue()));row.put("checkOutPhotos",id==null?List.of():files.linked("ATTENDANCE_OUT",((Number)id).longValue()));}return rows;
    }
    List<Map<String,Object>> teacherAttendance(LocalDate date) {
        sec.staff(); String scope=sec.campusScope("cp.id");
        if(!sec.isAdmin()) scope+=" AND u.id="+sec.actor().id();
        return db.list("SELECT u.id teacher_id,u.name teacher_name,cp.id campus_id,cp.name campus_name,? date,a.check_in_time,CASE WHEN a.id IS NULL THEN 'NOT_CHECKED_IN' ELSE 'CHECKED_IN' END status FROM app_user u JOIN campus cp ON EXISTS (SELECT 1 FROM teacher_class tc JOIN school_class cl ON cl.id=tc.class_id WHERE tc.user_id=u.id AND cl.campus_id=cp.id) LEFT JOIN teacher_attendance a ON a.user_id=u.id AND a.campus_id=cp.id AND a.date=? WHERE u.role='TEACHER' AND "+scope+" ORDER BY cp.id,u.id",date,date);
    }
    List<Map<String,Object>> homework(LocalDate date,Long student,String subject,String status) {
        return homework(date,student,subject,status,true);
    }
    List<Map<String,Object>> homework(LocalDate date,Long student,String subject,String status,boolean attachments) {
        if(student!=null) sec.student(student); List<Object> args=new ArrayList<>(List.of(date));
        String sql=learn.homeworkQuery()+" WHERE t.date=? AND "+activeScope();
        if(student!=null) { sql+=" AND h.student_id=?"; args.add(student); }
        if(subject!=null&&!subject.isBlank()) { sql+=" AND t.subject=?"; args.add(subject); }
        if(status!=null&&!status.isBlank()) { sql+=" AND h.status=?"; args.add(status); }
        var rows=db.list(sql+" ORDER BY h.id",args.toArray()); if(attachments) for(var h:rows) h.put("attachments",files.linked("HOMEWORK",Db.id(h,"id"))); return rows;
    }
    List<Map<String,Object>> mistakes(Long student,String subject,String status) {
        if(student!=null) sec.student(student); List<Object> args=new ArrayList<>(); String sql=learn.mistakeQuery()+" WHERE "+activeScope();
        if(student!=null) { sql+=" AND m.student_id=?"; args.add(student); }
        if(subject!=null&&!subject.isBlank()) { sql+=" AND m.subject=?"; args.add(subject); }
        if(status!=null&&!status.isBlank()) { sql+=" AND m.status=?"; args.add(status); }
        return db.list(sql+" ORDER BY m.id DESC",args.toArray()).stream().map(learn::decorateMistake).toList();
    }
    List<Map<String,Object>> fitness(LocalDate date,Long student) {
        if(student!=null) sec.student(student);
        String q="SELECT f.id,f.student_id,s.name student_name,f.date,f.project,f.count,f.duration,f.confirmed FROM fitness f JOIN student s ON s.id=f.student_id WHERE f.date=? AND "+activeScope();
        return student==null?db.list(q+" ORDER BY f.id DESC",date):db.list(q+" AND f.student_id=? ORDER BY f.id DESC",date,student);
    }
    Map<String,Object> homeworkTrend(long student,int days,LocalDate end) {
        sec.parent();sec.student(student);
        if(days!=7&&days!=30) throw ApiException.bad("请选择7天或30天");
        if(end.isAfter(sec.today())) throw ApiException.bad("不能查询未来的作业趋势");
        LocalDate start=end.minusDays(days-1);
        var graded=db.list("SELECT t.date,AVG(h.accuracy) accuracy,COUNT(*) graded_count FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=? AND t.date>=? AND t.date<=? AND h.accuracy IS NOT NULL GROUP BY t.date ORDER BY t.date",student,start,end);
        List<Map<String,Object>> points=new ArrayList<>();
        for(LocalDate day=start;!day.isAfter(end);day=day.plusDays(1)) {
            LocalDate current=day;var record=graded.stream().filter(r->current.equals(r.get("date"))).findFirst().orElse(null);
            Map<String,Object> point=new LinkedHashMap<>();point.put("date",day);point.put("gradedCount",record==null?0:record.get("gradedCount"));
            BigDecimal accuracy=record==null?null:new BigDecimal(record.get("accuracy").toString()).setScale(1,java.math.RoundingMode.HALF_UP);
            point.put("accuracy",accuracy);point.put("errorRate",accuracy==null?null:BigDecimal.valueOf(100).subtract(accuracy));points.add(point);
        }
        return Map.of("studentId",student,"days",days,"startDate",start,"endDate",end,"points",points);
    }
    List<Map<String,Object>> scoped(String sql,Long student,String studentColumn,String order) {
        if(student!=null) sec.student(student); sql+=" WHERE "+sec.scope("s");
        return student==null?db.list(sql+order):db.list(sql+" AND "+studentColumn+"=?"+order,student);
    }
    List<Map<String,Object>> courses() {
        String scope=sec.campusScope("c.campus_id");
        if(sec.actor().role().equals("PARENT")) scope+=" AND EXISTS (SELECT 1 FROM enrollment e JOIN student s ON s.id=e.student_id WHERE e.course_id=c.id AND "+sec.scope("s")+")";
        return db.list("SELECT c.*,cp.name campus_name FROM course c JOIN campus cp ON cp.id=c.campus_id WHERE "+scope+" ORDER BY c.id");
    }
    List<Map<String,Object>> accounts(Long student) { return scoped(finance.accountQuery(),student,"a.student_id"," ORDER BY a.id"); }
    List<Map<String,Object>> renewals(Long student) { return scoped(finance.renewalQuery(),student,"a.student_id"," ORDER BY r.id DESC"); }
    List<Map<String,Object>> ledger(Long student,Long account) {
        if(student!=null) sec.student(student); if(account!=null) sec.student(Db.id(db.one("SELECT student_id FROM lesson_account WHERE id=?",account),"studentId"));
        List<Object> args=new ArrayList<>(); String q=finance.ledgerQuery()+" WHERE "+sec.scope("s");
        if(student!=null) { q+=" AND a.student_id=?"; args.add(student); } if(account!=null) { q+=" AND l.account_id=?"; args.add(account); } return db.list(q+" ORDER BY l.id DESC",args.toArray());
    }
    List<Map<String,Object>> points(Long student) { return scoped("SELECT p.id,p.student_id,s.name student_name,p.amount,p.balance,p.type,p.reason,p.created_at FROM point_ledger p JOIN student s ON s.id=p.student_id AND s.privacy_erased_at IS NULL",student,"p.student_id"," ORDER BY p.id DESC"); }
    List<Map<String,Object>> enrollments(Long student) { return scoped("SELECT e.id,e.student_id,s.name student_name,e.course_id,c.name course_name,e.account_id FROM enrollment e JOIN student s ON s.id=e.student_id JOIN course c ON c.id=e.course_id AND s.privacy_erased_at IS NULL",student,"e.student_id"," ORDER BY e.id"); }
    List<Map<String,Object>> settlements() { sec.staff(); return db.list("SELECT t.id,t.student_id,s.name student_name,c.name course_name,t.date,t.status,t.reason FROM settlement t JOIN student s ON s.id=t.student_id JOIN lesson_account a ON a.id=t.account_id JOIN course c ON c.id=a.course_id WHERE "+sec.scope("s")+" ORDER BY t.id DESC"); }
    List<Map<String,Object>> schedules(LocalDate date) {
        String scope=sec.classScope("cl");
        if(sec.actor().role().equals("PARENT")) scope+=" AND EXISTS (SELECT 1 FROM enrollment e JOIN student s ON s.id=e.student_id WHERE e.course_id=sc.course_id AND s.class_id=sc.class_id AND "+sec.scope("s")+")";
        return db.list("SELECT sc.id,sc.course_id,c.name course_name,sc.class_id,cl.name class_name,cl.campus_id,(SELECT name FROM campus WHERE id=cl.campus_id) campus_name,sc.date,sc.start_time,sc.end_time,sc.units,sc.enabled FROM schedule sc JOIN course c ON c.id=sc.course_id JOIN school_class cl ON cl.id=sc.class_id WHERE sc.date=? AND "+scope+" ORDER BY sc.start_time,sc.id",date);
    }
    List<Map<String,Object>> summaries(LocalDate date,Long student) {
        if(student!=null) sec.student(student); String q=learn.summaryQuery()+" WHERE d.date=? AND "+activeScope();
        if(sec.actor().role().equals("PARENT")) q+=" AND d.status='PUBLISHED'";
        var rows=student==null?db.list(q+" ORDER BY d.id",date):db.list(q+" AND d.student_id=? ORDER BY d.id",date,student);
        return rows.stream().map(learn::decorateSummary).toList();
    }
    Map<String,Object> dashboard(LocalDate date) {
        Map<String,Object> out=new LinkedHashMap<>(); var students=students(null,null,date); String scope=activeScope();
        out.put("date",date); out.put("students",students); out.put("studentCount",students.size());
        out.put("leaveCount",students.stream().filter(s->s.get("attendanceStatus").equals("ON_LEAVE")).count());
        out.put("presentCount",students.stream().filter(s->List.of("PRESENT","LEFT").contains(s.get("attendanceStatus"))).count());
        if(sec.isAdmin()) {
            var teachers=teacherAttendance(date); out.put("teacherCount",teachers.size());
            out.put("checkedInTeacherCount",teachers.stream().filter(t->t.get("checkInTime")!=null).count());
        }
        var homework=homework(date,null,null,null); out.put("homeworkCount",homework.size()); out.put("completedHomeworkCount",homework.stream().filter(h->h.get("status").equals("COMPLETED")).count());
        out.put("pendingCorrectionCount",homework.stream().filter(h->h.get("status").equals("CORRECTION")).count());
        out.put("pendingRecheckCount",homework.stream().filter(h->h.get("status").equals("PENDING_RECHECK")).count());
        out.put("pendingSummaryCount",db.count("SELECT COUNT(*) FROM student s WHERE "+scope+" AND NOT EXISTS(SELECT 1 FROM daily_summary d WHERE d.student_id=s.id AND d.date=? AND d.status='PUBLISHED')",date));
        out.put("lowBalanceCount",db.count("SELECT COUNT(*) FROM student s WHERE "+scope+" AND EXISTS(SELECT 1 FROM lesson_account a JOIN course c ON c.id=a.course_id WHERE a.student_id=s.id AND (a.balance-COALESCE((SELECT SUM(r.lessons+r.gift_lessons) FROM refund_request r WHERE r.account_id=a.id AND r.status='APPROVED'),0))<=c.warning_threshold)"));
        out.put("todayConsumed",db.one("SELECT COALESCE(SUM(sc.units),0) amount FROM settlement t JOIN student s ON s.id=t.student_id JOIN schedule sc ON sc.id=t.schedule_id WHERE t.date=? AND t.status='SETTLED' AND "+scope,date).get("amount"));
        List<Map<String,Object>> progress=new ArrayList<>();
        for(String subject:List.of("语文","数学","英语")) progress.add(Map.of("subject",subject,"total",homework.stream().filter(h->h.get("subject").equals(subject)).count(),"completed",homework.stream().filter(h->h.get("subject").equals(subject)&&h.get("status").equals("COMPLETED")).count()));
        out.put("subjectProgress",progress); List<Map<String,Object>> weekly=new ArrayList<>();
        for(int i=6;i>=0;i--) { LocalDate d=date.minusDays(i); long present=db.count("SELECT COUNT(*) FROM attendance a JOIN student s ON s.id=a.student_id WHERE a.date=? AND "+scope,d); weekly.add(Map.of("date",d,"label",List.of("周一","周二","周三","周四","周五","周六","周日").get(d.getDayOfWeek().getValue()-1),"present",present,"total",students.size())); }
        out.put("weeklyAttendance",weekly);
        out.put("activities",db.list("SELECT a.id,s.name title,CASE WHEN a.status='LEFT' THEN '已离校' ELSE '已签到' END detail,COALESCE(a.check_out_time,a.check_in_time) time,'ATTENDANCE' type FROM attendance a JOIN student s ON s.id=a.student_id WHERE a.date=? AND "+scope+" ORDER BY time DESC LIMIT 20",date));
        return out;
    }
    Map<String,Object> growth(LocalDate date,Long studentId,boolean compact) {
        var children=students(null,null,date); Map<String,Object> out=new LinkedHashMap<>();
        out.put("date",date); out.put("students",children); out.put("readOnly",date.isAfter(sec.today()));
        if(studentId==null&&!children.isEmpty()) studentId=Db.id(children.get(0),"id");
        if(studentId==null) { out.put("student",null); return out; }
        long sid=studentId; sec.student(sid);
        var student=children.stream().filter(s->Db.id(s,"id")==sid).findFirst().orElseThrow(ApiException::forbidden);
        out.put("student",student); out.put("accounts",accounts(sid)); out.put("homework",homework(date,sid,null,null,!compact));
        Map<String,Object> pickup=new LinkedHashMap<>();
        pickup.put("defaultContact",db.optional("SELECT id,name,relationship FROM pickup_contact WHERE student_id=? AND deleted_at IS NULL ORDER BY is_default DESC,id LIMIT 1",sid));
        pickup.put("planned",db.optional("SELECT p.id,p.pickup_name,p.relationship,p.end_time,p.mode FROM pickup_authorization p WHERE p.student_id=? AND p.status='ACTIVE' AND p.start_date<=? AND p.end_date>=? AND EXISTS(SELECT 1 FROM parent_student ps WHERE ps.user_id=p.guardian_id AND ps.student_id=p.student_id) AND (p.contact_id IS NULL OR EXISTS(SELECT 1 FROM pickup_contact pc WHERE pc.id=p.contact_id AND pc.deleted_at IS NULL AND pc.identity_version=p.contact_version)) ORDER BY p.id DESC LIMIT 1",sid,date,date));
        pickup.put("openAlertCount",db.count("SELECT COUNT(*) FROM pickup_alert WHERE student_id=? AND business_date=? AND status IN ('OPEN','HOLD')",sid,date));
        pickup.put("expectedCheckout",Objects.toString(db.one("SELECT MAX(sc.end_time) end_time FROM schedule sc JOIN enrollment e ON e.course_id=sc.course_id AND e.student_id=? JOIN course c ON c.id=sc.course_id WHERE sc.class_id=? AND sc.date=? AND sc.enabled=TRUE AND c.enabled=TRUE",sid,student.get("classId"),date).get("endTime"),""));
        out.put("pickup",pickup);
        out.put("teachers",db.list("SELECT u.id,u.name FROM app_user u JOIN teacher_class tc ON tc.user_id=u.id WHERE tc.class_id=? ORDER BY u.id",student.get("classId")));
        out.put("timeline",date.isAfter(sec.today())?List.of():growth.feed(sid,date,!compact));
        var concerns=growth.concerns(date,sid,"ISSUE"); out.put("concerns",concerns);
        out.put("openConcernCount",concerns.stream().filter(c->List.of("OPEN","IN_PROGRESS").contains(c.get("status"))).count());
        out.put("messages",growth.concerns(date,sid,"MESSAGE"));
        out.put("fitness",fitness(date,sid)); out.put("fitnessPause",db.optional("SELECT p.*,u.name actor_name FROM fitness_pause p JOIN app_user u ON u.id=p.actor_id WHERE p.student_id=? AND p.date=?",sid,date));
        out.put("pointsDelta",db.one("SELECT COALESCE(SUM(amount),0) amount FROM point_ledger WHERE student_id=? AND created_at>=? AND created_at<?",sid,date.atStartOfDay(),date.plusDays(1).atStartOfDay()).get("amount"));
        if(!compact) out.put("points",db.list("SELECT id,amount,reason,type,created_at FROM point_ledger WHERE student_id=? AND created_at>=? AND created_at<? ORDER BY id",sid,date.atStartOfDay(),date.plusDays(1).atStartOfDay()));
        out.put("summary",db.optional("SELECT id,published_comment teacher_comment,published_at,version FROM daily_summary WHERE student_id=? AND date=? AND status='PUBLISHED'",sid,date));
        return out;
    }
    Map<String,Object> workbench(LocalDate date,Long classId) {
        sec.staff(); var classrooms=classes(); Map<String,Object> out=new LinkedHashMap<>(); out.put("date",date); out.put("classes",classrooms); out.put("readOnly",date.isAfter(sec.today()));
        if(classId==null&&!classrooms.isEmpty()) classId=Db.id(classrooms.get(0),"id");
        if(classId==null) { out.put("students",List.of()); out.put("homework",List.of()); out.put("concerns",List.of()); out.put("messages",List.of()); out.put("summaries",List.of()); out.put("scheduled",false); return out; }
        sec.schoolClass(classId); final long cl=classId; out.put("classId",cl);
        boolean scheduled=db.count("SELECT COUNT(*) FROM schedule sc JOIN course c ON c.id=sc.course_id WHERE sc.class_id=? AND sc.date=? AND sc.enabled=TRUE AND c.enabled=TRUE",cl,date)>0;
        var roster=students(cl,null,date);
        if(scheduled) roster=roster.stream().filter(s->db.count("SELECT COUNT(*) FROM enrollment e JOIN schedule sc ON sc.course_id=e.course_id JOIN course c ON c.id=sc.course_id WHERE e.student_id=? AND sc.class_id=? AND sc.date=? AND sc.enabled=TRUE AND c.enabled=TRUE",s.get("id"),cl,date)>0).toList();
        Set<Long> ids=new HashSet<>(); for(var s:roster) ids.add(Db.id(s,"id"));
        var tasks=homework(date,null,null,null,false).stream().filter(h->ids.contains(Db.id(h,"studentId"))).toList();
        var concerns=growth.concerns(date,null,"ISSUE").stream().filter(c->Db.id(c,"classId")==cl).toList();
        var messages=growth.concerns(date,null,"MESSAGE").stream().filter(c->Db.id(c,"classId")==cl).toList();
        out.put("pickupAlerts",db.list("SELECT a.id,a.student_id,s.name student_name,a.status,a.version,a.pickup_name,a.reason,a.created_at,s.parent_phone FROM pickup_alert a JOIN student s ON s.id=a.student_id WHERE s.class_id=? AND a.business_date=? AND a.status IN ('OPEN','HOLD') AND s.privacy_erased_at IS NULL ORDER BY a.id DESC",cl,date));
        out.put("scheduled",scheduled); out.put("students",roster); out.put("homework",tasks); out.put("concerns",concerns); out.put("messages",messages);
        out.put("summaries",summaries(date,null).stream().filter(s->ids.contains(Db.id(s,"studentId"))).toList());
        return out;
    }
}
