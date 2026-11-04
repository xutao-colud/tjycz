CREATE TABLE leave_request (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, leave_date DATE NOT NULL,
 reason VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL, created_by BIGINT NOT NULL,
 reviewed_by BIGINT, review_comment VARCHAR(1000) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, version INT NOT NULL DEFAULT 0,
 request_id VARCHAR(160) NOT NULL UNIQUE, request_hash VARCHAR(64) NOT NULL,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(created_by) REFERENCES app_user(id),
 FOREIGN KEY(reviewed_by) REFERENCES app_user(id), CHECK(status IN ('REQUESTED','APPROVED','REJECTED','CANCELLED'))
);
CREATE INDEX idx_leave_student_date ON leave_request(student_id,leave_date,status);

CREATE TABLE pickup_authorization (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, guardian_id BIGINT NOT NULL,
 pickup_name VARCHAR(80) NOT NULL, relationship VARCHAR(40) NOT NULL, phone VARCHAR(30) NOT NULL,
 valid_from TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 code_hash VARCHAR(100) NOT NULL, failed_attempts INT NOT NULL DEFAULT 0, last_failed_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, used_at TIMESTAMP,
 version INT NOT NULL DEFAULT 0, request_id VARCHAR(160) NOT NULL UNIQUE, request_hash VARCHAR(64) NOT NULL,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(guardian_id) REFERENCES app_user(id),
 CHECK(status IN ('ACTIVE','REVOKED','USED')), CHECK(expires_at > valid_from)
);
CREATE INDEX idx_pickup_student_window ON pickup_authorization(student_id,valid_from,expires_at,status);

CREATE TABLE pickup_verification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, authorization_id BIGINT NOT NULL, authorization_version INT NOT NULL,
 student_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, verified_at TIMESTAMP NOT NULL,
 expires_at TIMESTAMP NOT NULL, used_at TIMESTAMP,
 request_id VARCHAR(160) NOT NULL UNIQUE, request_hash VARCHAR(64) NOT NULL,
 FOREIGN KEY(authorization_id) REFERENCES pickup_authorization(id), FOREIGN KEY(student_id) REFERENCES student(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_pickup_verification_student ON pickup_verification(student_id,actor_id,used_at);

CREATE TABLE safety_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, entity_type VARCHAR(20) NOT NULL,
 entity_id BIGINT NOT NULL, action VARCHAR(40) NOT NULL, actor_id BIGINT NOT NULL,
 actor_name VARCHAR(80) NOT NULL, actor_role VARCHAR(20) NOT NULL, detail VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP NOT NULL, FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_safety_student ON safety_log(student_id,id);
