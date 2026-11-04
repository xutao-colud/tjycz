CREATE TABLE pickup_alert (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, business_date DATE NOT NULL,
 authorization_id BIGINT, source VARCHAR(20) NOT NULL, source_key VARCHAR(200) NOT NULL UNIQUE,
 request_hash VARCHAR(64) NOT NULL, pickup_name VARCHAR(80) NOT NULL, phone_last4 VARCHAR(4) NOT NULL DEFAULT '',
 reason VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN', version INT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(authorization_id) REFERENCES pickup_authorization(id),
 FOREIGN KEY(created_by) REFERENCES app_user(id), CHECK(source IN ('MANUAL','VERIFICATION')),
 CHECK(status IN ('OPEN','HOLD','RESOLVED'))
);
CREATE INDEX idx_pickup_alert_student ON pickup_alert(student_id,business_date,status);
CREATE TABLE pickup_alert_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, alert_id BIGINT NOT NULL, action VARCHAR(30) NOT NULL,
 actor_id BIGINT NOT NULL, actor_name VARCHAR(80) NOT NULL, actor_role VARCHAR(20) NOT NULL,
 note VARCHAR(1000) NOT NULL, created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(alert_id) REFERENCES pickup_alert(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_pickup_alert_log ON pickup_alert_log(alert_id,id);
