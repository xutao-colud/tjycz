CREATE TABLE daily_parent_feedback (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 student_id BIGINT NOT NULL, parent_id BIGINT NOT NULL, date DATE NOT NULL,
 satisfaction VARCHAR(20) NOT NULL, feedback_text VARCHAR(2000) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 UNIQUE(student_id,parent_id,date),
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(parent_id) REFERENCES app_user(id),
 CHECK(satisfaction IN ('SATISFIED','DISSATISFIED'))
);
