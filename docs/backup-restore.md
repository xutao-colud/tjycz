# 离线备份与隔离恢复

`scripts/backup-restore.py` 用于本项目 local 的 H2 `.mv.db` 文件和私有附件目录。仅使用 Python 3.9+ 标准库；离线检查依赖 macOS/Linux 的 `lsof` 和 POSIX 文件锁。不会连接、启动、停止或重启业务服务，也不会默认选择 `backend/data`。

备份包含数据库、附件、空目录以及 manifest。每个文件记录 SHA256；manifest 使用标准 HMAC-SHA256 签名，签名密钥独立保存，避免修改文件后同时改写校验清单。ZIP 文件权限为 `0600`，恢复目录/子目录为 `0700`，恢复文件为 `0600`。**签名不提供加密**；包含学生信息的备份应放在受控的加密磁盘或机构已有加密备份存储中，密钥另行保管。丢失密钥将无法通过本工具验证及恢复。

## H2 操作

以下路径是运维示例，执行前按环境替换。先由服务所有者正常停止全部后端实例和附件写入者，确认维护窗口；不要复制仍在运行中的 H2 文件。当前项目可用 `scripts/backend-local.sh status` 检查服务归属，该脚本只会停止自己管理的进程，其他服务应由所有者处理。

```bash
umask 077
mkdir -m 700 /secure/wanfu-backups
mkdir -m 700 /secure/wanfu-backup-keys

# 只需创建一次；不放入 uploads、数据目录或备份包内。
python3 scripts/backup-restore.py init-key \
  --key-file /secure/wanfu-backup-keys/manifest.key

# 必须先正常关闭数据库。所有路径须显式指定；已有备份文件绝不覆盖。
python3 scripts/backup-restore.py backup \
  --db-file /srv/wanfu/data/wanfu.mv.db \
  --uploads /srv/wanfu/data/uploads \
  --output /secure/wanfu-backups/maintenance-20261028.zip \
  --key-file /secure/wanfu-backup-keys/manifest.key

python3 scripts/backup-restore.py verify \
  --archive /secure/wanfu-backups/maintenance-20261028.zip \
  --key-file /secure/wanfu-backup-keys/manifest.key

# destination 必须不存在；不覆盖正在使用或已存在的任何目录。
python3 scripts/backup-restore.py restore \
  --archive /secure/wanfu-backups/maintenance-20261028.zip \
  --destination /srv/wanfu-restore-rehearsal \
  --key-file /secure/wanfu-backup-keys/manifest.key
```

恢复后数据库位于 `database/wanfu.mv.db`，附件位于 `uploads/`。隔离应用的 JDBC 文件路径应指向 `.../database/wanfu`（不含 `.mv.db`），`APP_UPLOAD_DIR` 指向同一恢复目录的 `uploads`。在独立端口检查账号绑定、校区权限、账目与流水、小结快照及附件字节后，再由服务所有者安排切换；此工具不会执行切换。

工具发现数据库打开、锁文件、无法取得 POSIX 锁，或 `lsof` 不可用/无法完整检查时会拒绝备份。持有数据库锁期间复制，并比对源文件及目录复制前后的大小、时间和 inode；源目录存在符号链接、硬链接或特殊文件也会拒绝。备份输出采用原子发布且拒绝覆盖。恢复先在私有临时目录逐文件校验，全部通过后替换自己独占创建的空目标；正常报错或中断会清理未完成的恢复，不保留可用的半份数据。操作系统强制杀进程或断电可能留下 `.backup-*` / `.restore-*` 临时项，它们不是验收通过的备份或恢复目录。

不备份 `.env`、日志、JAR 或签名密钥；数据库本身仍包含密码哈希、会话等业务安全数据，整个 ZIP 必须保密。工具成功输出仅含动作、文件数和字节数，不打印密钥、数据库内容、环境变量或附件文件名。最多支持 100000 个文件和 100GiB 未压缩数据。

## 自检与已验证范围

```bash
# 只使用临时虚构文件：权限、字节恢复、篡改、伪造清单、错误密钥、路径穿越、
# ZIP符号链接、源符号链接、已存在目标、运行中/加锁文件拒绝及失败清理。
python3 scripts/backup-restore.py self-test

# 可选：使用本机已有JDK 17和项目锁定的H2 2.2.224，不运行Maven。
python3 scripts/check-backup-restore.py \
  --java /path/to/jdk-17/bin/java \
  --h2-jar /path/to/h2-2.2.224.jar
```

真实 H2 检查会在临时目录创建虚构的余额和附件索引，持有 JDBC 连接时验证工具拒绝备份，关闭后备份并恢复，再通过 JDBC 核对余额、附件引用和关闭重开结果。它没有连接 18080，也没有读取、复制或修改现有 `backend/data` / `uploads`。

## 当前 Compose 的 MySQL 路径

仓库 `compose.yaml` 使用 `mysql:8.4`，数据库服务名 `mysql`，业务库 `wanfu`；后端服务 `backend` 的 `/app/uploads` 使用独立命名卷。现有运维脚本没有生产备份命令。H2 工具不会将 MySQL 数据目录当作 `.mv.db` 复制。

MySQL 必须在一个维护窗口内完成 **停止所有业务/附件写入 → InnoDB 一致快照导出 → 同窗口复制附件 → 校验 → 隔离恢复演练**。`--single-transaction --quick` 只协调数据库事务快照，不冻结附件；其他写入者、定时任务和 DDL 也应暂停。非 InnoDB 表需额外评估锁表方式。选项及备份账号权限依据 [MySQL 8.4 的 mysqldump 官方说明](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)。

以下是当前单后端 Compose 的人工操作路径，**本次未执行这些命令**。执行者须具备生产维护授权；多副本部署还须暂停其他所有写入者。不要开启 shell `set -x`，不要把口令放入命令行或输出 `.env`。

```bash
set -eu
umask 077
snapshot_dir=/secure/wanfu-mysql-maintenance-20261028
mkdir -m 700 "$snapshot_dir"

# 由服务所有者安排维护窗口，记录原始状态；只停后端，不停MySQL。
docker compose stop backend

# 已配置的MYSQL_PASSWORD仅在容器内传给客户端进程环境，不进入argv或导出文件。
# 先核实该账号具备SELECT、SHOW VIEW、TRIGGER、EVENT以及导出例程所需权限；
# 若默认应用账号权限不足，使用预先配置的专用备份账号/私有client配置，禁止忽略错误。
docker compose exec -T mysql sh -c \
  'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysqldump --user="$MYSQL_USER" --single-transaction --quick --no-tablespaces --routines --events --triggers --set-gtid-purged=OFF --databases "$MYSQL_DATABASE"' \
  > "$snapshot_dir/database.sql"

# 当前项目为单后端容器；docker cp可从已停止容器读取同一附件卷。
backend_container=$(docker compose ps -aq backend)
test -n "$backend_container"
mkdir -m 700 "$snapshot_dir/uploads"
docker cp "$backend_container:/app/uploads/." "$snapshot_dir/uploads/"
chmod -R go-rwx "$snapshot_dir"
```

任一步骤失败时保留维护状态并排查；不把不完整目录标为成功备份。完成 SQL 与附件后，对二者生成并独立签名保存 SHA256 清单，或交给机构已有的带认证加密备份系统；不要把独立生成的两个时间点当作一致备份。

恢复演练应使用全新数据库实例和附件卷：用 `mysql` 客户端导入 `database.sql`（其中含库名 `wanfu`，因此必须是隔离实例），将同份快照的 `uploads` 复制到新卷，使用原兼容版本应用和独立端口核对 `stored_file.storage_key` 与文件字节、课时账本、签退幂等、账号校区权限及已发布小结。不会覆盖生产实例或原卷；验收完成后再安排切换。MySQL、Docker 环境与权限未在本轮实测，不能据此标记生产恢复已通过。
