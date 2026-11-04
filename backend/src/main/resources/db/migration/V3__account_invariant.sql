ALTER TABLE lesson_account ADD CONSTRAINT ck_account_equation CHECK (balance = purchased + gifted - consumed);
