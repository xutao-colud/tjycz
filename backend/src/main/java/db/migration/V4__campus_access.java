package db.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive migration: keep every historical student, balance, attachment and session. */
public class V4__campus_access extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection c=context.getConnection();
        boolean mysql=c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        List<String> roleChecks=new ArrayList<>();
        String checks="SELECT tc.constraint_name,cc.check_clause FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE LOWER(tc.table_name)='app_user' AND tc.constraint_type='CHECK' AND tc.constraint_schema="+(mysql?"DATABASE()":"SCHEMA()");
        try(var s=c.createStatement();var r=s.executeQuery(checks)) {
            while(r.next()) if(r.getString(2).toLowerCase(Locale.ROOT).contains("role")) roleChecks.add(r.getString(1));
        }
        try(var s=c.createStatement()) {
            // H2 stores the generated name in upper case and only matches a double-quoted identifier; MySQL needs backticks.
            for(String name:roleChecks) s.execute("ALTER TABLE app_user DROP CONSTRAINT "+(mysql?"`"+name.replace("`","``")+"`":"\""+name+"\""));
            s.execute("ALTER TABLE app_user ADD CONSTRAINT ck_user_role CHECK (role IN ('SUPER_ADMIN','ADMIN','TEACHER','PARENT'))");
            s.executeUpdate("UPDATE app_user SET role='SUPER_ADMIN' WHERE role='ADMIN'");
            s.execute("CREATE TABLE campus (id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(80) NOT NULL,address VARCHAR(300) NOT NULL DEFAULT '',enabled BOOLEAN NOT NULL DEFAULT TRUE)");
            s.executeUpdate("INSERT INTO campus(id,name,address,enabled) VALUES(1,'主校区','',TRUE)");
            s.execute("CREATE TABLE user_campus (user_id BIGINT NOT NULL,campus_id BIGINT NOT NULL,PRIMARY KEY(user_id,campus_id),FOREIGN KEY(user_id) REFERENCES app_user(id),FOREIGN KEY(campus_id) REFERENCES campus(id))");
            for(String table:List.of("school_class","course","stored_file")) {
                s.execute("ALTER TABLE "+table+" ADD COLUMN campus_id BIGINT NOT NULL DEFAULT 1");
                s.execute("ALTER TABLE "+table+" ADD CONSTRAINT fk_"+table+"_campus FOREIGN KEY(campus_id) REFERENCES campus(id)");
                s.execute("CREATE INDEX idx_"+table+"_campus ON "+table+"(campus_id)");
            }
            s.execute("ALTER TABLE audit_log ADD COLUMN actor_id BIGINT");
            s.execute("ALTER TABLE audit_log ADD COLUMN campus_id BIGINT DEFAULT 1");
            s.execute("ALTER TABLE audit_log ADD CONSTRAINT fk_audit_campus FOREIGN KEY(campus_id) REFERENCES campus(id)");
            s.execute("CREATE INDEX idx_audit_campus ON audit_log(campus_id,id)");
        }
    }
}
