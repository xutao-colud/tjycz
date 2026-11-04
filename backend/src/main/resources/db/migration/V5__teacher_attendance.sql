CREATE TABLE teacher_attendance (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 campus_id BIGINT NOT NULL,
 date DATE NOT NULL,
 check_in_time TIMESTAMP NOT NULL,
 UNIQUE(user_id,campus_id,date),
 FOREIGN KEY(user_id) REFERENCES app_user(id),
 FOREIGN KEY(campus_id) REFERENCES campus(id)
);
CREATE INDEX idx_teacher_attendance_campus_date ON teacher_attendance(campus_id,date);
