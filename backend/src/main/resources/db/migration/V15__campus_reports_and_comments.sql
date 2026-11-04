CREATE TABLE campus_daily_report (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, campus_id BIGINT NOT NULL, business_date DATE NOT NULL,
 content TEXT NOT NULL, follow_up TEXT NOT NULL, next_plan TEXT NOT NULL,
 created_by BIGINT NOT NULL, created_name VARCHAR(80) NOT NULL, created_at TIMESTAMP NOT NULL,
 updated_by BIGINT NOT NULL, updated_name VARCHAR(80) NOT NULL, updated_at TIMESTAMP NOT NULL,
 version INT NOT NULL DEFAULT 1,
 UNIQUE(campus_id,business_date),
 FOREIGN KEY(campus_id) REFERENCES campus(id),
 FOREIGN KEY(created_by) REFERENCES app_user(id), FOREIGN KEY(updated_by) REFERENCES app_user(id)
);
CREATE TABLE class_report_comment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, class_id BIGINT NOT NULL, business_date DATE NOT NULL,
 text TEXT NOT NULL, actor_id BIGINT NOT NULL, actor_name VARCHAR(80) NOT NULL, actor_role VARCHAR(20) NOT NULL,
 created_at TIMESTAMP NOT NULL, request_key VARCHAR(120) NOT NULL UNIQUE,
 FOREIGN KEY(class_id) REFERENCES school_class(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_class_comment_day ON class_report_comment(class_id,business_date,id);
