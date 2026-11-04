ALTER TABLE daily_summary ADD COLUMN submitted_by BIGINT;
ALTER TABLE daily_summary ADD COLUMN submitted_name VARCHAR(80);
ALTER TABLE daily_summary ADD COLUMN submitted_at TIMESTAMP;
ALTER TABLE daily_summary ADD COLUMN reviewed_by BIGINT;
ALTER TABLE daily_summary ADD COLUMN reviewed_name VARCHAR(80);
ALTER TABLE daily_summary ADD COLUMN reviewed_at TIMESTAMP;
ALTER TABLE daily_summary ADD COLUMN review_comment VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE daily_summary ADD COLUMN review_version INT;
ALTER TABLE daily_summary ADD COLUMN review_content TEXT;
ALTER TABLE daily_summary ADD COLUMN review_teacher_comment VARCHAR(2000);
ALTER TABLE daily_summary ADD CONSTRAINT fk_summary_submitter FOREIGN KEY(submitted_by) REFERENCES app_user(id);
ALTER TABLE daily_summary ADD CONSTRAINT fk_summary_reviewer FOREIGN KEY(reviewed_by) REFERENCES app_user(id);

CREATE TABLE summary_review_history (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, summary_id BIGINT NOT NULL,
 action VARCHAR(30) NOT NULL, from_status VARCHAR(20), to_status VARCHAR(20) NOT NULL,
 version INT NOT NULL, actor_id BIGINT NOT NULL, actor_name VARCHAR(80) NOT NULL,
 actor_role VARCHAR(20) NOT NULL, comment VARCHAR(2000) NOT NULL DEFAULT '',
 content TEXT NOT NULL, teacher_comment VARCHAR(2000) NOT NULL DEFAULT '', created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(summary_id) REFERENCES daily_summary(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_summary_review_history ON summary_review_history(summary_id,id);

CREATE TABLE mistake_history (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, mistake_id BIGINT NOT NULL,
 action VARCHAR(40) NOT NULL, from_status VARCHAR(30), to_status VARCHAR(30),
 actor_id BIGINT, actor_name VARCHAR(80), actor_role VARCHAR(20),
 comment VARCHAR(2000) NOT NULL DEFAULT '', correction_id BIGINT, review_id BIGINT,
 created_at TIMESTAMP, legacy BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(mistake_id) REFERENCES mistake(id), FOREIGN KEY(actor_id) REFERENCES app_user(id),
 FOREIGN KEY(correction_id) REFERENCES correction(id), FOREIGN KEY(review_id) REFERENCES mistake_review(id)
);
CREATE INDEX idx_mistake_history ON mistake_history(mistake_id,id);

-- Existing records retain known evidence; historical actors and review times are not invented.
INSERT INTO mistake_history(mistake_id,action,comment,correction_id,created_at,legacy)
 SELECT mistake_id,'LEGACY_CORRECTION',text,id,created_at,TRUE FROM correction;
INSERT INTO mistake_history(mistake_id,action,to_status,comment,review_id,created_at,legacy)
 SELECT mistake_id,'LEGACY_REVIEW_COMPLETE','DONE','历史复习完成记录',id,completed_at,TRUE
 FROM mistake_review WHERE status='DONE';
INSERT INTO mistake_history(mistake_id,action,comment,legacy)
 SELECT id,'LEGACY_REVIEW_NOTE',teacher_comment,TRUE FROM mistake WHERE teacher_comment<>'';
