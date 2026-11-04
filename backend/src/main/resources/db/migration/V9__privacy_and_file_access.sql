ALTER TABLE student ADD COLUMN privacy_erased_at TIMESTAMP;
ALTER TABLE stored_file ADD COLUMN privacy_blocked BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE file_access_ticket (
 ticket_hash VARCHAR(64) PRIMARY KEY, session_hash VARCHAR(64) NOT NULL, file_id BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 FOREIGN KEY(session_hash) REFERENCES app_session(token_hash) ON DELETE CASCADE,
 FOREIGN KEY(file_id) REFERENCES stored_file(id)
);
CREATE INDEX idx_file_ticket_expiry ON file_access_ticket(expires_at);
CREATE TABLE privacy_consent (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 scope VARCHAR(20) NOT NULL, policy_version VARCHAR(40) NOT NULL, granted BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(user_id) REFERENCES app_user(id), CHECK(scope IN ('SERVICE','MEDIA'))
);
CREATE INDEX idx_privacy_consent_student ON privacy_consent(student_id,user_id,scope,id);
CREATE TABLE privacy_request (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, requested_by BIGINT NOT NULL,
 type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN', reason VARCHAR(1000) NOT NULL,
 review_note VARCHAR(1000) NOT NULL DEFAULT '', reviewed_by BIGINT, reviewed_at TIMESTAMP,
 completed_by BIGINT, completed_at TIMESTAMP, created_at TIMESTAMP NOT NULL, export_json MEDIUMTEXT,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(requested_by) REFERENCES app_user(id),
 FOREIGN KEY(reviewed_by) REFERENCES app_user(id), FOREIGN KEY(completed_by) REFERENCES app_user(id),
 CHECK(type IN ('EXPORT','ERASURE')), CHECK(status IN ('OPEN','APPROVED','REJECTED','COMPLETED'))
);
CREATE INDEX idx_privacy_request_student ON privacy_request(student_id,id);
