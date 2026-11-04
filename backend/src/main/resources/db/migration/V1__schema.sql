CREATE TABLE app_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 name VARCHAR(80) NOT NULL, role VARCHAR(16) NOT NULL, class_id BIGINT,
 CHECK (role IN ('ADMIN','TEACHER','PARENT'))
);
CREATE TABLE school_class (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(80) NOT NULL, teacher_name VARCHAR(80) NOT NULL);
CREATE TABLE student (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(80) NOT NULL, gender VARCHAR(16) NOT NULL, grade VARCHAR(40) NOT NULL,
 class_id BIGINT NOT NULL, parent_name VARCHAR(80) NOT NULL, parent_phone VARCHAR(30) NOT NULL,
 points INT NOT NULL DEFAULT 0, FOREIGN KEY (class_id) REFERENCES school_class(id), CHECK (points >= 0)
);
CREATE TABLE parent_student (user_id BIGINT NOT NULL, student_id BIGINT NOT NULL, PRIMARY KEY (user_id,student_id), FOREIGN KEY (user_id) REFERENCES app_user(id), FOREIGN KEY (student_id) REFERENCES student(id));
CREATE TABLE app_session (token_hash VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, demo BOOLEAN NOT NULL, expires_at TIMESTAMP NOT NULL, FOREIGN KEY (user_id) REFERENCES app_user(id));
CREATE TABLE course (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(80) NOT NULL, type VARCHAR(30) NOT NULL, unit VARCHAR(20) NOT NULL, price BIGINT NOT NULL, warning_threshold DECIMAL(12,2) NOT NULL, enabled BOOLEAN NOT NULL, CHECK(price >= 0), CHECK(warning_threshold >= 0));
CREATE TABLE lesson_account (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, course_id BIGINT NOT NULL, balance DECIMAL(12,2) NOT NULL DEFAULT 0,
 purchased DECIMAL(12,2) NOT NULL DEFAULT 0, gifted DECIMAL(12,2) NOT NULL DEFAULT 0, consumed DECIMAL(12,2) NOT NULL DEFAULT 0,
 UNIQUE(student_id,course_id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(course_id) REFERENCES course(id), CHECK(balance >= 0), CHECK(purchased >= 0), CHECK(gifted >= 0), CHECK(consumed >= 0)
);
CREATE TABLE enrollment (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, course_id BIGINT NOT NULL, account_id BIGINT NOT NULL, UNIQUE(student_id,course_id), UNIQUE(account_id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(course_id) REFERENCES course(id), FOREIGN KEY(account_id) REFERENCES lesson_account(id));
CREATE TABLE schedule (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, course_id BIGINT NOT NULL, class_id BIGINT NOT NULL, date DATE NOT NULL, start_time VARCHAR(8) NOT NULL, end_time VARCHAR(8) NOT NULL, units DECIMAL(12,2) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(course_id,class_id,date,start_time), FOREIGN KEY(course_id) REFERENCES course(id), FOREIGN KEY(class_id) REFERENCES school_class(id), CHECK(units > 0)
);
CREATE TABLE attendance (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, date DATE NOT NULL, status VARCHAR(20) NOT NULL,
 check_in_time TIMESTAMP, check_out_time TIMESTAMP, source VARCHAR(30) NOT NULL,
 UNIQUE(student_id,date), FOREIGN KEY(student_id) REFERENCES student(id), CHECK(status IN ('PRESENT','LEFT'))
);
CREATE TABLE renewal (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT NOT NULL, amount BIGINT NOT NULL, lessons DECIMAL(12,2) NOT NULL, gift_lessons DECIMAL(12,2) NOT NULL, payment_method VARCHAR(30) NOT NULL, status VARCHAR(20) NOT NULL, note VARCHAR(1000) NOT NULL, request_id VARCHAR(100) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(account_id) REFERENCES lesson_account(id), CHECK(amount >= 0), CHECK(lessons > 0), CHECK(gift_lessons >= 0), CHECK(status IN ('PENDING','CONFIRMED'))
);
CREATE TABLE lesson_ledger (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT NOT NULL, type VARCHAR(30) NOT NULL, amount DECIMAL(12,2) NOT NULL, balance_before DECIMAL(12,2) NOT NULL, balance_after DECIMAL(12,2) NOT NULL, reason VARCHAR(1000) NOT NULL, event_key VARCHAR(160) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL, reversed BOOLEAN NOT NULL DEFAULT FALSE, original_id BIGINT UNIQUE,
 purchased_delta DECIMAL(12,2) NOT NULL DEFAULT 0, gifted_delta DECIMAL(12,2) NOT NULL DEFAULT 0, consumed_delta DECIMAL(12,2) NOT NULL DEFAULT 0,
 FOREIGN KEY(account_id) REFERENCES lesson_account(id), FOREIGN KEY(original_id) REFERENCES lesson_ledger(id), CHECK(balance_after >= 0)
);
CREATE TABLE settlement (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, schedule_id BIGINT NOT NULL, account_id BIGINT NOT NULL, date DATE NOT NULL, status VARCHAR(20) NOT NULL, reason VARCHAR(1000) NOT NULL,
 UNIQUE(student_id,schedule_id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(schedule_id) REFERENCES schedule(id), FOREIGN KEY(account_id) REFERENCES lesson_account(id), CHECK(status IN ('PENDING','SETTLED'))
);
CREATE TABLE homework_task (id BIGINT AUTO_INCREMENT PRIMARY KEY, date DATE NOT NULL, subject VARCHAR(16) NOT NULL, type VARCHAR(16) NOT NULL, title VARCHAR(200) NOT NULL, description VARCHAR(2000) NOT NULL);
CREATE TABLE homework (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, student_id BIGINT NOT NULL, status VARCHAR(30) NOT NULL, accuracy INT, comment VARCHAR(2000) NOT NULL DEFAULT '',
 UNIQUE(task_id,student_id), FOREIGN KEY(task_id) REFERENCES homework_task(id), FOREIGN KEY(student_id) REFERENCES student(id), CHECK(accuracy IS NULL OR (accuracy >= 0 AND accuracy <= 100))
);
CREATE TABLE mistake (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, subject VARCHAR(16) NOT NULL, title VARCHAR(300) NOT NULL, knowledge_point VARCHAR(300) NOT NULL, error_reason VARCHAR(1000) NOT NULL, status VARCHAR(30) NOT NULL, homework_id BIGINT, created_at TIMESTAMP NOT NULL, correction_text VARCHAR(2000) NOT NULL DEFAULT '', teacher_comment VARCHAR(2000) NOT NULL DEFAULT '',
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(homework_id) REFERENCES homework(id)
);
CREATE TABLE correction (id BIGINT AUTO_INCREMENT PRIMARY KEY, mistake_id BIGINT NOT NULL, text VARCHAR(2000) NOT NULL, created_at TIMESTAMP NOT NULL, FOREIGN KEY(mistake_id) REFERENCES mistake(id));
CREATE TABLE mistake_review (id BIGINT AUTO_INCREMENT PRIMARY KEY, mistake_id BIGINT NOT NULL, due_date DATE NOT NULL, status VARCHAR(20) NOT NULL, completed_at TIMESTAMP, UNIQUE(mistake_id,due_date), FOREIGN KEY(mistake_id) REFERENCES mistake(id));
CREATE TABLE fitness (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, date DATE NOT NULL, project VARCHAR(80) NOT NULL, count INT NOT NULL, duration INT NOT NULL, confirmed BOOLEAN NOT NULL,
 UNIQUE(student_id,date,project), FOREIGN KEY(student_id) REFERENCES student(id), CHECK(count >= 0), CHECK(duration >= 0)
);
CREATE TABLE point_ledger (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, amount INT NOT NULL, balance INT NOT NULL, type VARCHAR(40) NOT NULL, reason VARCHAR(1000) NOT NULL, event_key VARCHAR(160) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL, FOREIGN KEY(student_id) REFERENCES student(id), CHECK(balance >= 0)
);
CREATE TABLE daily_summary (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, date DATE NOT NULL, status VARCHAR(20) NOT NULL, content TEXT NOT NULL, teacher_comment VARCHAR(2000) NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 0, published_at TIMESTAMP,
 published_content TEXT, published_comment VARCHAR(2000), feedback_rating INT, feedback_text VARCHAR(2000), notification_status VARCHAR(40) NOT NULL DEFAULT 'NOT_CONFIGURED',
 UNIQUE(student_id,date), FOREIGN KEY(student_id) REFERENCES student(id), CHECK(feedback_rating IS NULL OR (feedback_rating >= 1 AND feedback_rating <= 5))
);
CREATE TABLE stored_file (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL, storage_key VARCHAR(80) NOT NULL UNIQUE, content_type VARCHAR(100) NOT NULL, size BIGINT NOT NULL, created_at TIMESTAMP NOT NULL, FOREIGN KEY(owner_id) REFERENCES app_user(id));
CREATE TABLE file_link (file_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL, target_id BIGINT NOT NULL, student_id BIGINT NOT NULL, PRIMARY KEY(file_id,kind,target_id), FOREIGN KEY(file_id) REFERENCES stored_file(id), FOREIGN KEY(student_id) REFERENCES student(id));
CREATE TABLE audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_name VARCHAR(80) NOT NULL, action VARCHAR(80) NOT NULL, detail VARCHAR(2000) NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE TABLE demo_day (date DATE PRIMARY KEY);
CREATE INDEX idx_attendance_date ON attendance(date);
CREATE INDEX idx_summary_date ON daily_summary(date);
CREATE INDEX idx_homework_student ON homework(student_id);
CREATE INDEX idx_point_student ON point_ledger(student_id);
CREATE INDEX idx_ledger_account ON lesson_ledger(account_id);
CREATE INDEX idx_file_student ON file_link(student_id);
