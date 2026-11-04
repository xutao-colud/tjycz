package cn.wanfu;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class Security {
    final Db db; final Clock clock; final Environment env;
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    final SecureRandom random=new SecureRandom();
    private final ThreadLocal<Actor> current=new ThreadLocal<>();
    private final ThreadLocal<Long> campusContext=new ThreadLocal<>();
    private final ThreadLocal<Set<Long>> auditCampuses=ThreadLocal.withInitial(LinkedHashSet::new);
    public record Actor(long id,String name,String role,boolean demo,String sessionHash) {}
    public Security(Db db,Clock clock,Environment env) { this.db=db; this.clock=clock; this.env=env; }
    boolean local() { return Arrays.asList(env.getActiveProfiles()).contains("local") || (env.getActiveProfiles().length==0 && Arrays.asList(env.getDefaultProfiles()).contains("local")); }
    LocalDate today() { return LocalDate.now(clock.withZone(Application.ZONE)); }
    LocalDateTime now() { return LocalDateTime.now(clock.withZone(Application.ZONE)); }
    public Actor actor() { Actor a=current.get(); if(a==null) throw new ApiException(401,"请登录"); return a; }
    boolean isAdmin() { return List.of("SUPER_ADMIN","ADMIN").contains(actor().role()); }
    boolean isSuper() { return actor().role().equals("SUPER_ADMIN"); }
    void staff() { if(!List.of("SUPER_ADMIN","ADMIN","TEACHER").contains(actor().role())) throw ApiException.forbidden(); }
    void admin() { if(!isAdmin()) throw ApiException.forbidden(); }
    void superAdmin() { if(!isSuper()) throw ApiException.forbidden(); }
    void parent() { if(!actor().role().equals("PARENT")) throw ApiException.forbidden(); }
    Long contextCampus() { return campusContext.get(); }
    String grantedCampuses(long user,String role) {
        return switch(role) {
            case "SUPER_ADMIN" -> "SELECT id FROM campus";
            case "ADMIN" -> "SELECT campus_id FROM user_campus WHERE user_id="+user;
            case "TEACHER" -> "SELECT cl.campus_id FROM school_class cl JOIN teacher_class tc ON tc.class_id=cl.id WHERE tc.user_id="+user;
            case "PARENT" -> "SELECT cl.campus_id FROM school_class cl JOIN student s ON s.class_id=cl.id JOIN parent_student ps ON ps.student_id=s.id WHERE ps.user_id="+user;
            default -> "SELECT id FROM campus WHERE 1=0";
        };
    }
    String campusScope(String column) {
        String q=isSuper()?"1=1":column+" IN ("+grantedCampuses(actor().id(),actor().role())+")";
        if(contextCampus()!=null) q="("+q+") AND "+column+"="+contextCampus();
        return "("+q+")";
    }
    void campus(long id) {
        if(db.count("SELECT COUNT(*) FROM campus WHERE id=? AND "+campusScope("id"),id)==0) throw ApiException.forbidden();
    }
    long writeCampus(Map<String,Object> b) {
        if(b.get("campusId")!=null) { long id=Db.number(b,"campusId"); campus(id); return id; }
        if(contextCampus()!=null) return contextCampus();
        var rows=db.list("SELECT id FROM campus WHERE "+campusScope("id"));
        if(rows.size()!=1) throw ApiException.bad("请选择具体校区 campusId");
        return Db.id(rows.get(0),"id");
    }
    String scope(String alias) {
        String original=switch(actor().role()) {
            case "SUPER_ADMIN","ADMIN" -> "1=1";
            case "TEACHER" -> alias+".class_id IN (SELECT class_id FROM teacher_class WHERE user_id="+actor().id()+")";
            case "PARENT" -> alias+".id IN (SELECT student_id FROM parent_student WHERE user_id="+actor().id()+")";
            default -> "1=0";
        };
        return "("+original+" AND "+alias+".class_id IN (SELECT id FROM school_class WHERE "+campusScope("campus_id")+")"+(isAdmin()?"":" AND "+alias+".privacy_erased_at IS NULL")+")";
    }
    String classScope(String alias) {
        String original=switch(actor().role()) {
            case "SUPER_ADMIN","ADMIN" -> "1=1";
            case "TEACHER" -> alias+".id IN (SELECT class_id FROM teacher_class WHERE user_id="+actor().id()+")";
            case "PARENT" -> alias+".id IN (SELECT s.class_id FROM student s JOIN parent_student p ON p.student_id=s.id WHERE p.user_id="+actor().id()+")";
            default -> "1=0";
        };
        return "("+original+" AND "+campusScope(alias+".campus_id")+")";
    }
    long studentCampus(long id) { return Db.id(db.one("SELECT cl.campus_id FROM student s JOIN school_class cl ON cl.id=s.class_id WHERE s.id=?",id),"campusId"); }
    void student(long id) {
        if(db.count("SELECT COUNT(*) FROM student s WHERE s.id=? AND "+scope("s"),id)==0) throw ApiException.forbidden();
        if(db.one("SELECT privacy_erased_at FROM student WHERE id=?",id).get("privacyErasedAt")!=null) throw new ApiException(409,"该学生资料已脱敏，不能继续处理");
        auditCampus(studentCampus(id));
    }
    void schoolClass(long id) {
        if(actor().role().equals("PARENT")||db.count("SELECT COUNT(*) FROM school_class cl WHERE cl.id=? AND "+classScope("cl"),id)==0) throw ApiException.forbidden();
        auditCampus(Db.id(db.one("SELECT campus_id FROM school_class WHERE id=?",id),"campusId"));
    }
    long course(long id) { var c=db.one("SELECT campus_id FROM course WHERE id=?",id); long campus=Db.id(c,"campusId"); campus(campus); return campus; }
    void auditCampus(long id) { auditCampuses.get().add(id); }
    String hash(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    Map<String,Object> identity(long id,String name,String role) {
        var campuses=db.list("SELECT id,name FROM campus WHERE id IN ("+grantedCampuses(id,role)+") ORDER BY id");
        Map<String,Object> out=new LinkedHashMap<>(); out.put("id",id); out.put("name",name); out.put("role",role); out.put("isSuperAdmin",role.equals("SUPER_ADMIN"));
        out.put("campuses",campuses); out.put("campusIds",campuses.stream().map(c->c.get("id")).toList());
        out.put("classIds",role.equals("TEACHER")?db.list("SELECT class_id FROM teacher_class WHERE user_id=? ORDER BY class_id",id).stream().map(c->c.get("classId")).toList():List.of());
        out.put("studentIds",role.equals("PARENT")?db.list("SELECT student_id FROM parent_student WHERE user_id=? ORDER BY student_id",id).stream().map(s->s.get("studentId")).toList():List.of());
        return out;
    }
    Map<String,Object> me() { var a=actor(); var out=identity(a.id(),a.name(),a.role()); out.put("demo",a.demo()); return out; }
    Map<String,Object> session(Map<String,Object> user,boolean demo) {
        byte[] b=new byte[32]; random.nextBytes(b); String token=Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        db.update("INSERT INTO app_session(token_hash,user_id,demo,expires_at) VALUES(?,?,?,?)",hash(token),Db.id(user,"id"),demo,now().plusHours(12));
        db.update("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,?,NULL,'LOGIN',?,?)",Db.id(user,"id"),user.get("name"),"demo="+demo,now());
        return Map.of("token",token,"user",identity(Db.id(user,"id"),user.get("name").toString(),user.get("role").toString()),"demo",demo);
    }
    Actor authenticate(String token) {
        if(token==null||token.length()>256) throw new ApiException(401,"会话无效或已过期");
        return authenticateHash(hash(token));
    }
    Actor authenticateHash(String sessionHash) {
        var u=db.optional("SELECT u.id,u.name,u.role,t.demo FROM app_session t JOIN app_user u ON u.id=t.user_id WHERE t.token_hash=? AND t.expires_at>?",sessionHash,now());
        if(u==null || (Boolean.TRUE.equals(u.get("demo"))&&!local())) throw new ApiException(401,"会话无效或已过期");
        return new Actor(Db.id(u,"id"),u.get("name").toString(),u.get("role").toString(),Boolean.TRUE.equals(u.get("demo")),sessionHash);
    }
    <T> T inSession(String hash,long campus,java.util.function.Supplier<T> action) {
        if(current.get()!=null) throw new IllegalStateException("后台身份上下文不可嵌套");
        try { current.set(authenticateHash(hash)); campus(campus); campusContext.set(campus); return action.get(); }
        finally { current.remove(); campusContext.remove(); auditCampuses.remove(); }
    }
    Map<String,Object> logout() {
        audit("LOGOUT","撤销当前会话及附件访问凭证");
        db.update("DELETE FROM file_access_ticket WHERE session_hash=?",actor().sessionHash());
        db.update("DELETE FROM app_session WHERE token_hash=?",actor().sessionHash());
        return Map.of("success",true);
    }
    void audit(String action,String detail) {
        Set<Long> campuses=new LinkedHashSet<>(auditCampuses.get()); if(campuses.isEmpty()&&contextCampus()!=null) campuses.add(contextCampus());
        if(campuses.isEmpty()) db.update("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,?,NULL,?,?,?)",actor().id(),actor().name(),action,detail,now());
        else for(long campus:campuses) db.update("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,?,?,?,?,?)",actor().id(),actor().name(),campus,action,detail,now());
    }
    void requestAudit(String path,String method,int status) {
        var actor=current.get();
        if(path.equals("/api/auth/login")&&status>=400 || status==401 || status==403) {
            db.update("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,?,?,?,?,?)",actor==null?null:actor.id(),actor==null?"未认证":actor.name(),contextCampus(),path.equals("/api/auth/login")?"LOGIN_FAILED":"ACCESS_DENIED",method+" "+path+" status="+status,now());
        } else if(actor!=null&&status<300&&method.equals("GET")&&path.matches("/api/(students|leaves|pickups|pickup-contacts|pickup-alerts|homework|mistakes|growth|principal-overview|follow-ups|summaries|fitness|points|attendance|accounts|lesson-ledger|renewals|family-services|renewal-intents|meal-orders|student-notifications|point-presets|reward-prizes|prize-redemptions|teaching-capture/jobs|privacy/(requests|consents)).*")) {
            if(auditCampuses.get().isEmpty()&&contextCampus()==null&&!isSuper()) for(var campus:db.list("SELECT id FROM campus WHERE id IN ("+grantedCampuses(actor.id(),actor.role())+")")) auditCampus(Db.id(campus,"id"));
            audit("SENSITIVE_READ",method+" "+path);
        }
    }
    LocalDate date(String value) { try { return value==null||value.isBlank()?today():LocalDate.parse(value); } catch(Exception e) { throw ApiException.bad("日期格式应为YYYY-MM-DD"); } }

    @Component
    static class SessionFilter extends OncePerRequestFilter {
        final Security security; final FileAccessTokenService tickets; final ObjectMapper json;
        SessionFilter(Security security,FileAccessTokenService tickets,ObjectMapper json) { this.security=security; this.tickets=tickets; this.json=json; }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            String path=request.getRequestURI();
            boolean anonymous=!path.startsWith("/api/") || path.equals("/api/auth/login") || (path.equals("/api/auth/challenge") && request.getMethod().equals("GET")) || path.equals("/api/auth/demo") || path.equals("/api/health") || request.getMethod().equals("OPTIONS");
            try {
              if(!anonymous) {
                String auth=request.getHeader("Authorization"); String token=auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null;
                if(token==null&&path.matches("/api/files/[0-9]+")&&request.getMethod().equals("GET")) security.current.set(tickets.authenticate(request.getParameter("ticket"),Long.parseLong(path.substring(path.lastIndexOf('/')+1))));
                else security.current.set(security.authenticate(token));
                String header=request.getHeader("X-Campus-Id");
                if(header!=null&&!path.equals("/api/auth/logout")) {
                    long campus;
                    try { campus=Long.parseLong(header); if(campus<=0) throw new NumberFormatException(); }
                    catch(NumberFormatException e) { throw ApiException.bad("X-Campus-Id须为有效校区ID"); }
                    security.campus(campus); security.campusContext.set(campus);
                }
              }
                chain.doFilter(request,response);
            } catch(ApiException e) { response.setStatus(e.status); response.setContentType("application/json;charset=UTF-8"); json.writeValue(response.getWriter(),Map.of("message",e.getMessage(),"code",e.code)); }
            finally {
                try { if(path.startsWith("/api/")) security.requestAudit(path,request.getMethod(),response.getStatus()); }
                finally { security.current.remove(); security.campusContext.remove(); security.auditCampuses.remove(); }
            }
        }
    }
}
