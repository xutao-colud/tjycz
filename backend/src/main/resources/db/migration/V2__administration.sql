CREATE TABLE teacher_class (user_id BIGINT NOT NULL, class_id BIGINT NOT NULL, PRIMARY KEY(user_id,class_id), FOREIGN KEY(user_id) REFERENCES app_user(id), FOREIGN KEY(class_id) REFERENCES school_class(id));
CREATE TABLE app_setting (setting_key VARCHAR(80) PRIMARY KEY, setting_value VARCHAR(2000) NOT NULL);
INSERT INTO app_setting VALUES ('institutionName','晚辅智管'),('campusName','主校区'),('teacherName','晚辅老师'),('HOMEWORK_COMPLETED','5'),('MISTAKE_MASTERED','2'),('FITNESS','2');
