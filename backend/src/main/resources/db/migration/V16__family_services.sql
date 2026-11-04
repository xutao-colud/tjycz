ALTER TABLE student ADD COLUMN avatar_file_id BIGINT;
ALTER TABLE student ADD FOREIGN KEY (avatar_file_id) REFERENCES stored_file(id);
ALTER TABLE daily_summary ADD COLUMN feedback_satisfaction VARCHAR(20);
UPDATE daily_summary SET feedback_satisfaction=CASE WHEN feedback_rating>=3 THEN 'SATISFIED' ELSE 'DISSATISFIED' END WHERE feedback_rating IS NOT NULL;
ALTER TABLE daily_summary ADD CHECK (feedback_satisfaction IS NULL OR feedback_satisfaction IN ('SATISFIED','DISSATISFIED'));

CREATE TABLE campus_family_service (
 campus_id BIGINT PRIMARY KEY, meal_price BIGINT, payment_qr_file_id BIGINT, version INT NOT NULL DEFAULT 1,
 updated_by BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL,
 FOREIGN KEY(campus_id) REFERENCES campus(id), FOREIGN KEY(payment_qr_file_id) REFERENCES stored_file(id),
 FOREIGN KEY(updated_by) REFERENCES app_user(id), CHECK(meal_price IS NULL OR (meal_price>=0 AND meal_price<=100000000))
);
CREATE TABLE renewal_intent (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT NOT NULL, parent_id BIGINT NOT NULL,
 intention VARCHAR(20) NOT NULL, version INT NOT NULL DEFAULT 1, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 UNIQUE(account_id,parent_id), FOREIGN KEY(account_id) REFERENCES lesson_account(id), FOREIGN KEY(parent_id) REFERENCES app_user(id),
 CHECK(intention IN ('INTERESTED','NOT_NOW'))
);
CREATE TABLE meal_order (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, meal_date DATE NOT NULL,
 ordered BOOLEAN NOT NULL, amount BIGINT NOT NULL, ordered_by BIGINT NOT NULL, version INT NOT NULL DEFAULT 1,
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 UNIQUE(student_id,meal_date), FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(ordered_by) REFERENCES app_user(id),
 CHECK(amount>=0 AND amount<=100000000)
);
