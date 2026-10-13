CREATE DATABASE commit_db;
CREATE DATABASE snippet_db;
CREATE DATABASE bug_db;
CREATE DATABASE sql_db;
CREATE DATABASE review_db;

GRANT ALL PRIVILEGES ON DATABASE commit_db TO commit_user;
GRANT ALL PRIVILEGES ON DATABASE snippet_db TO snippet_user;
GRANT ALL PRIVILEGES ON DATABASE bug_db TO bug_user;
GRANT ALL PRIVILEGES ON DATABASE sql_db TO sql_user;
GRANT ALL PRIVILEGES ON DATABASE review_db TO review_user;

\connect commit_db
GRANT ALL ON SCHEMA public TO commit_user;

\connect snippet_db
GRANT ALL ON SCHEMA public TO snippet_user;

\connect bug_db
GRANT ALL ON SCHEMA public TO bug_user;

\connect sql_db
GRANT ALL ON SCHEMA public TO sql_user;

\connect review_db
GRANT ALL ON SCHEMA public TO review_user;
