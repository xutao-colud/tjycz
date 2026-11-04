CREATE TABLE class_daily_report (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 class_id BIGINT NOT NULL,
 business_date DATE NOT NULL,
 content TEXT NOT NULL,
 follow_up TEXT NOT NULL,
 next_plan TEXT NOT NULL,
 created_by BIGINT NOT NULL,
 created_name VARCHAR(80) NOT NULL,
 created_at TIMESTAMP NOT NULL,
 updated_by BIGINT NOT NULL,
 updated_name VARCHAR(80) NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 version INT NOT NULL DEFAULT 1,
 UNIQUE(class_id,business_date),
 FOREIGN KEY(class_id) REFERENCES school_class(id),
 FOREIGN KEY(created_by) REFERENCES app_user(id),
 FOREIGN KEY(updated_by) REFERENCES app_user(id)
);
