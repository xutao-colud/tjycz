CREATE TABLE pickup_contact (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, guardian_id BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL, relationship VARCHAR(40) NOT NULL, phone VARCHAR(30) NOT NULL,
 avatar_file_id BIGINT, is_default BOOLEAN NOT NULL DEFAULT FALSE, face_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CONFIGURED',
 version INT NOT NULL DEFAULT 0, identity_version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP,
 request_id VARCHAR(160) NOT NULL UNIQUE, request_hash VARCHAR(64) NOT NULL,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(guardian_id) REFERENCES app_user(id),
 FOREIGN KEY(avatar_file_id) REFERENCES stored_file(id)
);
CREATE INDEX idx_pickup_contact_student ON pickup_contact(student_id,deleted_at,is_default);
ALTER TABLE pickup_authorization ADD COLUMN contact_id BIGINT;
ALTER TABLE pickup_authorization ADD COLUMN contact_version INT;
ALTER TABLE pickup_authorization ADD COLUMN mode VARCHAR(12) NOT NULL DEFAULT 'SINGLE';
ALTER TABLE pickup_authorization ADD COLUMN start_date DATE;
ALTER TABLE pickup_authorization ADD COLUMN end_date DATE;
ALTER TABLE pickup_authorization ADD COLUMN start_time TIME;
ALTER TABLE pickup_authorization ADD COLUMN end_time TIME;
ALTER TABLE pickup_authorization ADD COLUMN note VARCHAR(1000) NOT NULL DEFAULT '';
ALTER TABLE pickup_authorization ADD COLUMN face_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CONFIGURED';
ALTER TABLE pickup_authorization ADD CONSTRAINT fk_pickup_contact FOREIGN KEY(contact_id) REFERENCES pickup_contact(id);
ALTER TABLE pickup_authorization ADD CONSTRAINT ck_pickup_mode CHECK(mode IN ('SINGLE','MULTI'));
UPDATE pickup_authorization SET start_date=CAST(valid_from AS DATE),end_date=CAST(expires_at AS DATE),start_time=CAST(valid_from AS TIME),end_time=CAST(expires_at AS TIME);
CREATE TABLE pickup_use (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, authorization_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
 business_date DATE NOT NULL, verification_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, used_at TIMESTAMP NOT NULL,
 FOREIGN KEY(authorization_id) REFERENCES pickup_authorization(id), FOREIGN KEY(student_id) REFERENCES student(id),
 FOREIGN KEY(verification_id) REFERENCES pickup_verification(id), FOREIGN KEY(actor_id) REFERENCES app_user(id),
 UNIQUE(authorization_id,business_date), UNIQUE(student_id,business_date), UNIQUE(verification_id)
);
CREATE INDEX idx_pickup_use_student ON pickup_use(student_id,business_date);
INSERT INTO pickup_use(authorization_id,student_id,business_date,verification_id,actor_id,used_at)
SELECT p.id,p.student_id,CAST(v.used_at AS DATE),v.id,v.actor_id,v.used_at
FROM pickup_authorization p JOIN pickup_verification v ON v.authorization_id=p.id
WHERE v.used_at IS NOT NULL;
