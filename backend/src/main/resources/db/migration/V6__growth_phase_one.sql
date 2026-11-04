ALTER TABLE homework ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE homework ADD COLUMN submitted_at TIMESTAMP;
ALTER TABLE homework ADD COLUMN reviewed_at TIMESTAMP;
ALTER TABLE homework ADD COLUMN completed_at TIMESTAMP;
ALTER TABLE homework ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE homework ADD COLUMN last_actor_id BIGINT;
ALTER TABLE fitness ADD COLUMN occurred_at TIMESTAMP;
ALTER TABLE fitness ADD COLUMN recorded_at TIMESTAMP;
ALTER TABLE fitness ADD COLUMN actor_id BIGINT;

CREATE TABLE growth_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, business_date DATE NOT NULL,
 event_type VARCHAR(30) NOT NULL, source_type VARCHAR(30) NOT NULL, source_id BIGINT NOT NULL,
 action VARCHAR(40) NOT NULL, title VARCHAR(200) NOT NULL, detail TEXT NOT NULL, status VARCHAR(40) NOT NULL,
 occurred_at TIMESTAMP, recorded_at TIMESTAMP NOT NULL, actor_id BIGINT NOT NULL, actor_name VARCHAR(80) NOT NULL,
 event_key VARCHAR(180) NOT NULL UNIQUE, request_key VARCHAR(180) UNIQUE, request_hash VARCHAR(64),
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_growth_student_time ON growth_event(student_id,occurred_at,business_date);

CREATE TABLE fitness_pause (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, date DATE NOT NULL,
 reason VARCHAR(2000) NOT NULL, measures VARCHAR(2000) NOT NULL, actor_id BIGINT NOT NULL,
 occurred_at TIMESTAMP, updated_at TIMESTAMP NOT NULL, version INT NOT NULL DEFAULT 0,
 UNIQUE(student_id,date), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);

CREATE TABLE follow_up (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, business_date DATE NOT NULL,
 kind VARCHAR(20) NOT NULL, type VARCHAR(30) NOT NULL, title VARCHAR(200) NOT NULL,
 reason VARCHAR(2000) NOT NULL, measures VARCHAR(2000) NOT NULL, owner_id BIGINT NOT NULL,
 needs_parent BOOLEAN NOT NULL, parent_action VARCHAR(2000) NOT NULL, next_feedback_at TIMESTAMP,
 source_type VARCHAR(30), source_id BIGINT, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 created_by BIGINT NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(owner_id) REFERENCES app_user(id),
 FOREIGN KEY(created_by) REFERENCES app_user(id), CHECK(kind IN ('ISSUE','MESSAGE')),
 CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','CANCELLED'))
);
CREATE INDEX idx_followup_student_date ON follow_up(student_id,business_date,status);
CREATE TABLE follow_up_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, follow_up_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL, actor_name VARCHAR(80) NOT NULL, actor_role VARCHAR(20) NOT NULL,
 action VARCHAR(30) NOT NULL, from_status VARCHAR(20), to_status VARCHAR(20),
 text TEXT NOT NULL, created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(follow_up_id) REFERENCES follow_up(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE follow_up_view (
 follow_up_id BIGINT NOT NULL, user_id BIGINT NOT NULL, last_log_id BIGINT NOT NULL, read_at TIMESTAMP NOT NULL,
 PRIMARY KEY(follow_up_id,user_id), FOREIGN KEY(follow_up_id) REFERENCES follow_up(id), FOREIGN KEY(user_id) REFERENCES app_user(id)
);
