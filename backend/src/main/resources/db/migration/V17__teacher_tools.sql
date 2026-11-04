ALTER TABLE mistake ADD COLUMN question_text VARCHAR(10000) NOT NULL DEFAULT '';
UPDATE mistake SET question_text=title;
INSERT INTO app_setting(setting_key,setting_value) VALUES('SUMMARY_PUBLISHED','2');

CREATE TABLE parent_notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, parent_id BIGINT NOT NULL, student_id BIGINT NOT NULL, attendance_id BIGINT NOT NULL,
 event VARCHAR(20) NOT NULL, title VARCHAR(100) NOT NULL, message VARCHAR(1000) NOT NULL, created_at TIMESTAMP NOT NULL, read_at TIMESTAMP,
 delivery_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CONFIGURED',
 UNIQUE(parent_id,attendance_id,event), FOREIGN KEY(parent_id) REFERENCES app_user(id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(attendance_id) REFERENCES attendance(id)
);
CREATE TABLE point_preset (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, campus_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, points INT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 1, FOREIGN KEY(campus_id) REFERENCES campus(id), CHECK(points>0 AND points<=100)
);
INSERT INTO point_preset(campus_id,name,points) SELECT id,'主动帮助同学',2 FROM campus;
INSERT INTO point_preset(campus_id,name,points) SELECT id,'课堂专注',2 FROM campus;
INSERT INTO point_preset(campus_id,name,points) SELECT id,'主动思考',3 FROM campus;
CREATE TABLE point_award (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, preset_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 points INT NOT NULL, reason VARCHAR(100) NOT NULL, request_id VARCHAR(100) NOT NULL, created_at TIMESTAMP NOT NULL,
 UNIQUE(actor_id,request_id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(preset_id) REFERENCES point_preset(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE reward_prize (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, campus_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, points_cost INT NOT NULL,
 stock INT NOT NULL, image_file_id BIGINT, enabled BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 1,
 FOREIGN KEY(campus_id) REFERENCES campus(id), FOREIGN KEY(image_file_id) REFERENCES stored_file(id), CHECK(points_cost>0 AND points_cost<=1000000), CHECK(stock>=0)
);
CREATE TABLE prize_redemption (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, prize_id BIGINT NOT NULL, student_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 prize_name VARCHAR(100) NOT NULL, points_cost INT NOT NULL, image_file_id BIGINT, request_id VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL,
 UNIQUE(actor_id,request_id), FOREIGN KEY(prize_id) REFERENCES reward_prize(id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(actor_id) REFERENCES app_user(id), FOREIGN KEY(image_file_id) REFERENCES stored_file(id)
);
CREATE INDEX idx_parent_notification_student ON parent_notification(student_id);
CREATE INDEX idx_prize_redemption_student ON prize_redemption(student_id);
CREATE TABLE teaching_capture (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
 target_id BIGINT NOT NULL, request_id VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 UNIQUE(actor_id,request_id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
