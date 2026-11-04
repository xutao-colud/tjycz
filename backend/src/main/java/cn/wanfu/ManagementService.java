package cn.wanfu;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementService {
    final Db db; final Security sec; final FinanceService finance; final PasswordTransportService passwordTransport; final AttendanceLocationService locations;
    public ManagementService(Db db,Security sec,FinanceService finance,PasswordTransportService passwordTransport,AttendanceLocationService locations) { this.db=db; this.sec=sec; this.finance=finance; this.passwordTransport=passwordTransport; this.locations=locations; }
    @Transactional public Map<String,Object> createStudent(Map<String,Object> b) {
        sec.admin(); long cl=Db.number(b,"classId"); sec.schoolClass(cl);
        String gender=Db.required(b,"gender",16); Db.choice(gender,"男","女","MALE","FEMALE","OTHER");
        long sid=db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES(?,?,?,?,?,?)",Db.required(b,"name",80),gender,Db.required(b,"grade",40),cl,Db.required(b,"parentName",80),Db.required(b,"parentPhone",30));
        var course=db.optional("SELECT id FROM course WHERE type='EVENING' AND enabled=TRUE AND campus_id=? ORDER BY id LIMIT 1",sec.studentCampus(sid));
        if(course!=null) finance.enrollment(sid,Db.id(course,"id")); sec.audit("STUDENT_CREATE","student="+sid); return Map.of("id",sid);
    }
    @Transactional public Map<String,Object> createClass(Map<String,Object> b) {
        sec.admin(); long campus=sec.writeCampus(b); sec.auditCampus(campus);
        long id=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES(?,?,?)",Db.required(b,"name",80),Db.required(b,"teacherName",80),campus); sec.audit("CLASS_CREATE","class="+id);
        return db.one("SELECT cl.id,cl.name,cl.teacher_name,cl.campus_id,cp.name campus_name,0 student_count FROM school_class cl JOIN campus cp ON cp.id=cl.campus_id WHERE cl.id=?",id);
    }
    @Transactional public Map<String,Object> createCourse(Map<String,Object> b) {
        sec.admin(); long price=Db.number(b,"price"); BigDecimal warning=Db.decimal(b,"warningThreshold"); if(price<0||warning.signum()<0) throw ApiException.bad("价格、预警课时不能为负");
        String type=Db.required(b,"type",30); Db.choice(type,"EVENING","HOLIDAY","WEEKEND"); Db.choice(Db.required(b,"unit",20),"次","节","小时");
        long campus=sec.writeCampus(b); sec.auditCampus(campus);
        long id=db.insert("INSERT INTO course(name,type,unit,price,warning_threshold,enabled,campus_id) VALUES(?,?,?,?,?,TRUE,?)",Db.required(b,"name",80),type,Db.required(b,"unit",20),price,warning,campus); sec.audit("COURSE_CREATE","course="+id); return db.one("SELECT c.*,cp.name campus_name FROM course c JOIN campus cp ON cp.id=c.campus_id WHERE c.id=?",id);
    }
    @Transactional public Map<String,Object> createUser(Map<String,Object> b) {
        sec.admin(); String password=passwordTransport.decode(b,PasswordTransportService.Purpose.CREATE_USER),role=Db.required(b,"role",16); Db.choice(role,"ADMIN","TEACHER","PARENT"); validatePassword(password);
        if(role.equals("ADMIN")) sec.superAdmin();
        var classIds=Db.optionalIds(b,"classIds"); var studentIds=Db.optionalIds(b,"studentIds"); var campusIds=Db.optionalIds(b,"campusIds");
        validateAccess(role,campusIds,classIds,studentIds,true);
        long id=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",Db.required(b,"username",80),sec.passwords.encode(password),Db.required(b,"name",80),role);
        saveAccess(id,campusIds,classIds,studentIds);
        sec.audit("USER_CREATE","user="+id+", role="+role); return user(id);
    }
    static void validatePassword(String value) {
        if(value.length()<12||value.length()>72||!value.matches(".*[A-Z].*")||!value.matches(".*[a-z].*")||!value.matches(".*[0-9].*")||!value.matches(".*[^A-Za-z0-9].*")) throw ApiException.bad("密码须12至72位，包含大小写字母、数字和符号");
    }
    Map<String,Object> user(long id) {
        var u=db.one("SELECT id,name,username,role FROM app_user WHERE id=?",id);
        u.put("classIds",db.list("SELECT class_id FROM teacher_class WHERE user_id=? ORDER BY class_id",id).stream().map(x->x.get("classId")).toList());
        u.put("studentIds",db.list("SELECT student_id FROM parent_student WHERE user_id=? ORDER BY student_id",id).stream().map(x->x.get("studentId")).toList());
        var campuses=db.list("SELECT id,name FROM campus WHERE id IN ("+sec.grantedCampuses(id,u.get("role").toString())+") ORDER BY id");
        u.put("campusIds",campuses.stream().map(c->c.get("id")).toList()); u.put("campusNames",campuses.stream().map(c->c.get("name")).toList()); u.put("campuses",campuses); return u;
    }
    @SuppressWarnings("unchecked") List<Long> ids(Map<String,Object> u,String key) { return ((List<Object>)u.get(key)).stream().map(x->((Number)x).longValue()).toList(); }
    boolean manageable(Map<String,Object> u) {
        if(sec.isSuper()) return true;
        if(!List.of("TEACHER","PARENT").contains(u.get("role"))) return false;
        var campuses=ids(u,"campusIds");
        return !campuses.isEmpty()&&campuses.stream().allMatch(id->db.count("SELECT COUNT(*) FROM campus WHERE id=? AND "+sec.campusScope("id"),id)>0);
    }
    List<Map<String,Object>> users() {
        sec.admin(); return db.list("SELECT id FROM app_user ORDER BY id").stream().map(x->user(Db.id(x,"id")))
            .filter(this::manageable).filter(u->sec.contextCampus()==null||ids(u,"campusIds").contains(sec.contextCampus())).toList();
    }
    void validateAccess(String role,List<Long> campuses,List<Long> classes,List<Long> students,boolean creating) {
        if(role.equals("ADMIN")) {
            sec.superAdmin(); if(!classes.isEmpty()||!students.isEmpty()||creating&&campuses.isEmpty()) throw ApiException.bad("校长须绑定campusIds且不可绑定班级或孩子");
            for(long id:campuses) { sec.campus(id); sec.auditCampus(id); }
        } else {
            if(!campuses.isEmpty()) throw ApiException.bad("教师/家长校区由班级/孩子绑定决定");
            if(role.equals("TEACHER")&&(!students.isEmpty()||creating&&classes.isEmpty())||role.equals("PARENT")&&(!classes.isEmpty()||creating&&students.isEmpty())) throw ApiException.bad("教师须绑定classIds；家长须绑定studentIds");
            for(long id:classes) sec.schoolClass(id); for(long id:students) sec.student(id);
        }
    }
    void saveAccess(long id,List<Long> campuses,List<Long> classes,List<Long> students) {
        db.update("DELETE FROM user_campus WHERE user_id=?",id); db.update("DELETE FROM teacher_class WHERE user_id=?",id); db.update("DELETE FROM parent_student WHERE user_id=?",id);
        for(long campus:campuses) db.update("INSERT INTO user_campus VALUES(?,?)",id,campus);
        for(long cl:classes) db.update("INSERT INTO teacher_class VALUES(?,?)",id,cl);
        for(long sid:students) db.update("INSERT INTO parent_student VALUES(?,?)",id,sid);
    }
    @Transactional public Map<String,Object> access(long id,Map<String,Object> b) {
        sec.admin(); if(b.isEmpty()||!Set.of("campusIds","classIds","studentIds").containsAll(b.keySet())) throw ApiException.bad("仅可修改campusIds/classIds/studentIds");
        db.one("SELECT id FROM app_user WHERE id=? FOR UPDATE",id); var u=user(id);
        if(u.get("role").equals("SUPER_ADMIN")||!manageable(u)) throw ApiException.forbidden();
        String role=u.get("role").toString();
        if(role.equals("ADMIN")&&(b.containsKey("classIds")||b.containsKey("studentIds"))||role.equals("TEACHER")&&(b.containsKey("campusIds")||b.containsKey("studentIds"))||role.equals("PARENT")&&(b.containsKey("campusIds")||b.containsKey("classIds"))) throw ApiException.bad("授权字段与角色不匹配");
        var campuses=role.equals("ADMIN")?(b.containsKey("campusIds")?Db.optionalIds(b,"campusIds"):ids(u,"campusIds")):List.<Long>of();
        var classes=b.containsKey("classIds")?Db.optionalIds(b,"classIds"):ids(u,"classIds");
        var students=b.containsKey("studentIds")?Db.optionalIds(b,"studentIds"):ids(u,"studentIds");
        validateAccess(role,campuses,classes,students,false);
        for(long campus:ids(u,"campusIds")) sec.auditCampus(campus);
        saveAccess(id,campuses,classes,students); sec.audit("USER_ACCESS_UPDATE","user="+id+", role="+role); return user(id);
    }
    List<Map<String,Object>> campuses() {
        var a=sec.actor(); String grants=sec.grantedCampuses(a.id(),a.role());
        return db.list("SELECT cp.id,cp.name,cp.address,cp.enabled,cp.check_in_address,cp.check_in_latitude,cp.check_in_longitude,cp.check_in_radius,(SELECT COUNT(*) FROM student s JOIN school_class cl ON cl.id=s.class_id WHERE cl.campus_id=cp.id) student_count,(SELECT COUNT(DISTINCT tc.user_id) FROM teacher_class tc JOIN school_class cl ON cl.id=tc.class_id WHERE cl.campus_id=cp.id) teacher_count,(SELECT COUNT(*) FROM school_class cl WHERE cl.campus_id=cp.id) class_count FROM campus cp WHERE cp.id IN ("+grants+") ORDER BY cp.id");
    }
    @Transactional public Map<String,Object> createCampus(Map<String,Object> b) {
        sec.superAdmin(); String address=Db.text(b,"address"); if(address.length()>300) throw ApiException.bad("address最多300字");
        long id=db.insert("INSERT INTO campus(name,address) VALUES(?,?)",Db.required(b,"name",80),address); sec.auditCampus(id); sec.audit("CAMPUS_CREATE","campus="+id);
        return campuses().stream().filter(c->Db.id(c,"id")==id).findFirst().orElseThrow();
    }
    @Transactional public Map<String,Object> teacherCheckIn(Map<String,Object> b) {
        if(!sec.actor().role().equals("TEACHER")) throw ApiException.forbidden();
        if(!Set.of("campusId","location").containsAll(b.keySet())) throw ApiException.bad("仅可提交campusId和location，打卡人和签到时间由系统确定");
        long campus=Db.number(b,"campusId"),user=sec.actor().id();
        db.one("SELECT id FROM app_user WHERE id=? FOR UPDATE",user);
        sec.campus(campus);
        LocalDateTime now=sec.now(); LocalDate date=now.toLocalDate();
        var record=db.optional("SELECT * FROM teacher_attendance WHERE user_id=? AND campus_id=? AND date=?",user,campus,date);
        locations.verify(campus,b);
        if(record!=null) return record;
        long id=db.insert("INSERT INTO teacher_attendance(user_id,campus_id,date,check_in_time) VALUES(?,?,?,?)",user,campus,date,now);
        sec.auditCampus(campus); sec.audit("TEACHER_CHECK_IN","attendance="+id);
        return db.one("SELECT * FROM teacher_attendance WHERE id=?",id);
    }
    @Transactional public Map<String,Object> schedule(Map<String,Object> b) {
        sec.admin(); long cl=Db.number(b,"classId"),course=Db.number(b,"courseId"); sec.schoolClass(cl); long campus=sec.course(course); db.one("SELECT id FROM course WHERE id=? AND enabled=TRUE",course);
        if(Db.id(db.one("SELECT campus_id FROM school_class WHERE id=?",cl),"campusId")!=campus) throw ApiException.bad("课程和班级必须属于同一校区");
        LocalDate date=sec.date(Db.text(b,"date")); LocalTime start=LocalTime.parse(Db.required(b,"startTime",8)),end=LocalTime.parse(Db.required(b,"endTime",8));
        BigDecimal units=Db.decimal(b,"units"); if(!end.isAfter(start)||units.signum()<=0) throw ApiException.bad("结束时间须晚于开始时间，课时须大于0");
        db.one("SELECT id FROM school_class WHERE id=? FOR UPDATE",cl);
        if(db.count("SELECT COUNT(*) FROM attendance a JOIN student s ON s.id=a.student_id WHERE s.class_id=? AND a.date=? AND a.status='LEFT'",cl,date)>0) throw ApiException.bad("该班当天已有学生签退，请在签退前完成排课");
        if(db.count("SELECT COUNT(*) FROM schedule WHERE class_id=? AND date=? AND enabled=TRUE AND start_time<? AND end_time>?",cl,date,end.toString(),start.toString())>0) throw ApiException.bad("同班排课时间重叠");
        long id=db.insert("INSERT INTO schedule(course_id,class_id,date,start_time,end_time,units,enabled) VALUES(?,?,?,?,?,?,TRUE)",course,cl,date,start.toString(),end.toString(),units);
        sec.audit("SCHEDULE_CREATE","schedule="+id); return db.one("SELECT sc.id,sc.course_id,c.name course_name,sc.class_id,cl.name class_name,cl.campus_id,(SELECT name FROM campus WHERE id=cl.campus_id) campus_name,sc.date,sc.start_time,sc.end_time,sc.units,sc.enabled FROM schedule sc JOIN course c ON c.id=sc.course_id JOIN school_class cl ON cl.id=sc.class_id WHERE sc.id=?",id);
    }
    Map<String,Object> settings() {
        Map<String,Object> out=new LinkedHashMap<>();
        for(String k:List.of("institutionName","campusName","teacherName")) out.put(k,db.one("SELECT setting_value FROM app_setting WHERE setting_key=?",k).get("settingValue"));
        out.put("faceRecognitionEnabled",false); out.put("wechatConfigured",false); out.put("demo",sec.actor().demo());
        out.put("pointsRules",List.of("HOMEWORK_COMPLETED","MISTAKE_MASTERED","FITNESS","SUMMARY_PUBLISHED").stream().map(e->Map.of("event",e,"points",finance.rule(e))).toList()); return out;
    }
    @Transactional public Map<String,Object> settings(Map<String,Object> b) {
        sec.superAdmin(); for(String k:List.of("institutionName","campusName","teacherName")) if(b.containsKey(k)) db.update("UPDATE app_setting SET setting_value=? WHERE setting_key=?",Db.required(b,k,200),k);
        if(b.containsKey("pointsRules")) {
            if(!(b.get("pointsRules") instanceof List<?> rules)||rules.size()>4) throw ApiException.bad("积分规则无效");
            Set<String> seen=new HashSet<>(); for(Object v:rules) { if(!(v instanceof Map<?,?> m)) throw ApiException.bad("积分规则无效"); String event=Objects.toString(m.get("event"),""); Db.choice(event,"HOMEWORK_COMPLETED","MISTAKE_MASTERED","FITNESS","SUMMARY_PUBLISHED"); if(!seen.add(event)) throw ApiException.bad("积分规则事件重复"); int points=new BigDecimal(Objects.toString(m.get("points"),"-1")).intValueExact(); if(points<0||points>100) throw ApiException.bad("奖励积分须0至100"); db.update("UPDATE app_setting SET setting_value=? WHERE setting_key=?",Integer.toString(points),event); }
        }
        sec.audit("SETTINGS_UPDATE","更新机构设置及积分规则"); return settings();
    }
}
