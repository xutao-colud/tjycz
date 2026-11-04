CREATE TABLE question_scan (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, campus_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL, session_hash VARCHAR(64) NOT NULL, source_file_id BIGINT NOT NULL, page_file_id BIGINT,
 subject VARCHAR(16) NOT NULL, request_id VARCHAR(100) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
 error_message VARCHAR(1000) NOT NULL DEFAULT '', engine VARCHAR(200) NOT NULL DEFAULT '',
 page_width INT NOT NULL DEFAULT 0, page_height INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 1,
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 UNIQUE(actor_id,request_id), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(campus_id) REFERENCES campus(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id), FOREIGN KEY(source_file_id) REFERENCES stored_file(id), FOREIGN KEY(page_file_id) REFERENCES stored_file(id)
);
CREATE INDEX idx_question_scan_pending ON question_scan(status,created_at);
CREATE TABLE question_scan_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, scan_id BIGINT NOT NULL, sort_order INT NOT NULL,
 display_number VARCHAR(80) NOT NULL, group_name VARCHAR(160) NOT NULL DEFAULT '',
 question_text MEDIUMTEXT NOT NULL, recognition_text MEDIUMTEXT NOT NULL,
 regions_json TEXT NOT NULL, warnings_json TEXT NOT NULL, imported_mistake_id BIGINT, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(scan_id) REFERENCES question_scan(id), FOREIGN KEY(imported_mistake_id) REFERENCES mistake(id)
);
CREATE INDEX idx_question_scan_student ON question_scan(student_id,created_at);
