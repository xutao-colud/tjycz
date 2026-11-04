package cn.wanfu;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RecoveryAcceptanceTest {
    @TempDir Path directory;
    String url(String name) { return "jdbc:h2:file:"+directory.resolve(name)+";MODE=MySQL;DATABASE_TO_LOWER=TRUE"; }
    String literal(Path path) { return "'"+path.toAbsolutePath().toString().replace("'","''")+"'"; }
    @Test void isolatedDatabaseAndPrivateAttachmentSurviveBackupRestoreAndReopen() throws Exception {
        Flyway.configure().dataSource(url("source"),"sa","").load().migrate();
        byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aUe8AAAAASUVORK5CYII=");
        Path uploads=Files.createDirectory(directory.resolve("source-uploads"));
        Files.write(uploads.resolve("fixture-private-key"),image);
        Path backup=Files.createDirectory(directory.resolve("backup"));
        try(var connection=DriverManager.getConnection(url("source"),"sa",""); var sql=connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user(id,username,password_hash,name,role) VALUES(1,'recovery-parent','test-only-hash','虚构家长','PARENT'),(2,'recovery-teacher','test-only-hash','虚构老师','TEACHER')");
            sql.executeUpdate("INSERT INTO school_class(id,name,teacher_name,campus_id) VALUES(1,'恢复演练班','虚构老师',1)");
            sql.executeUpdate("INSERT INTO student(id,name,gender,grade,class_id,parent_name,parent_phone,points) VALUES(1,'虚构恢复学生','女','三年级',1,'虚构家长','00000000000',2)");
            sql.executeUpdate("INSERT INTO parent_student VALUES(1,1)");
            sql.executeUpdate("INSERT INTO teacher_class VALUES(2,1)");
            sql.executeUpdate("INSERT INTO course(id,name,type,unit,price,warning_threshold,enabled,campus_id) VALUES(1,'晚辅恢复演练','EVENING','次',100,2,TRUE,1)");
            sql.executeUpdate("INSERT INTO lesson_account(id,student_id,course_id,balance,purchased,gifted,consumed) VALUES(1,1,1,10,10,2,2)");
            sql.executeUpdate("INSERT INTO lesson_ledger(account_id,type,amount,balance_before,balance_after,reason,event_key,created_at,purchased_delta,gifted_delta,consumed_delta) VALUES(1,'RENEWAL',12,0,12,'虚构续费','recovery-renewal',CURRENT_TIMESTAMP,10,2,0),(1,'CONSUMPTION',-2,12,10,'虚构消课','recovery-consumption',CURRENT_TIMESTAMP,0,0,2)");
            sql.executeUpdate("INSERT INTO point_ledger(student_id,amount,balance,type,reason,event_key,created_at) VALUES(1,2,2,'FITNESS','虚构体能','recovery-fitness',CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO fitness(student_id,date,project,count,duration,confirmed) VALUES(1,'2020-05-17','跳绳',100,60,TRUE)");
            sql.executeUpdate("INSERT INTO daily_summary(student_id,date,status,content,published_content,published_comment,version,published_at) VALUES(1,'2020-05-17','PUBLISHED','恢复演练快照','恢复演练快照','认真练习',1,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO stored_file(id,owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(1,1,'虚构附件.png','fixture-private-key','image/png',"+image.length+",CURRENT_TIMESTAMP,1)");
            sql.executeUpdate("INSERT INTO file_link(file_id,kind,target_id,student_id) VALUES(1,'SUMMARY',1,1)");
            sql.executeUpdate("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(2,'虚构老师',1,'RECOVERY_FIXTURE','隔离恢复演练',CURRENT_TIMESTAMP)");
            sql.execute("SCRIPT TO "+literal(backup.resolve("database.sql")));
            Files.copy(uploads.resolve("fixture-private-key"),backup.resolve("fixture-private-key"));
            sql.executeUpdate("UPDATE student SET name='备份后的新名字' WHERE id=1");
        }
        Path restoredUploads=Files.createDirectory(directory.resolve("restored-uploads"));
        Files.copy(backup.resolve("fixture-private-key"),restoredUploads.resolve("fixture-private-key"));
        try(var connection=DriverManager.getConnection(url("restored"),"sa",""); var sql=connection.createStatement()) {
            sql.execute("RUNSCRIPT FROM "+literal(backup.resolve("database.sql")));
        }
        try(var connection=DriverManager.getConnection(url("restored"),"sa",""); var sql=connection.createStatement()) {
            for(String query:List.of(
                "SELECT COUNT(*) FROM student WHERE id=1 AND name='虚构恢复学生' AND points=(SELECT SUM(amount) FROM point_ledger WHERE student_id=1)",
                "SELECT COUNT(*) FROM parent_student p JOIN teacher_class t ON t.class_id=1 WHERE p.user_id=1 AND p.student_id=1 AND t.user_id=2",
                "SELECT COUNT(*) FROM lesson_account WHERE id=1 AND balance=10 AND balance=purchased+gifted-consumed AND balance=(SELECT SUM(amount) FROM lesson_ledger WHERE account_id=1)",
                "SELECT COUNT(*) FROM daily_summary WHERE status='PUBLISHED' AND published_content='恢复演练快照' AND published_comment='认真练习'",
                "SELECT COUNT(*) FROM stored_file f JOIN file_link l ON l.file_id=f.id WHERE f.storage_key='fixture-private-key' AND f.campus_id=1 AND l.student_id=1",
                "SELECT COUNT(*) FROM fitness WHERE confirmed=TRUE AND duration=60 AND student_id=1",
                "SELECT COUNT(*) FROM audit_log WHERE action='RECOVERY_FIXTURE' AND actor_id=2 AND campus_id=1")) {
                try(var result=sql.executeQuery(query)) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).as(query).isEqualTo(1); }
            }
        }
        assertThat(Files.readAllBytes(restoredUploads.resolve("fixture-private-key"))).isEqualTo(image);
        assertThat(Flyway.configure().dataSource(url("restored"),"sa","").load().info().pending()).isEmpty();
    }
}
