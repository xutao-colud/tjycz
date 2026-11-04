#!/usr/bin/env python3
"""Offline H2 database + upload snapshots. Never starts or stops a service."""
import argparse
import contextlib
import datetime
import hashlib
import hmac
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

FORMAT = "wanfu-offline-h2-v1"
DATABASE = "database/wanfu.mv.db"
CHUNK = 1024 * 1024
MAX_FILES = 100_000
MAX_BYTES = 100 * 1024**3
MAX_MANIFEST = 32 * 1024**2


class BackupError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise BackupError(message)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def path_argument(value):
    path = Path(value).expanduser().absolute()
    require(not path.is_symlink(), "路径不能是符号链接")
    return path.parent.resolve(strict=True) / path.name


def new_target(value):
    path = path_argument(value)
    require(not path.exists() and not path.is_symlink(), "目标已存在，拒绝覆盖")
    return path


def private_file(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


def init_key(value):
    private_file(new_target(value), os.urandom(32))


def read_key(value):
    path = path_argument(value)
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as handle:
        info = os.fstat(handle.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) & 0o077 == 0,
                "签名密钥须是独立普通文件，且仅所有者可访问（chmod 600）")
        key = handle.read(33)
    require(len(key) == 32, "签名密钥必须恰好32字节，请使用 init-key 创建")
    return path, key


def stamp(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def inventory(database, uploads):
    files = {DATABASE: (database, stamp(database.stat(follow_symlinks=False)))}
    directories = {"database", "uploads"}
    metadata = {"uploads": stamp(uploads.stat(follow_symlinks=False))}
    require(stat.S_ISREG(files[DATABASE][1][2]) and files[DATABASE][1][3] == 1, "H2数据库须是没有硬链接的普通文件")
    def walk_error(error):
        raise BackupError("无法完整遍历附件目录，拒绝不完整备份") from error
    for root, dirs, names in os.walk(uploads, followlinks=False, onerror=walk_error):
        root = Path(root)
        for name in dirs + names:
            path = root / name
            info = path.stat(follow_symlinks=False)
            relative = "uploads/" + path.relative_to(uploads).as_posix()
            safe_name(relative)
            require(not stat.S_ISLNK(info.st_mode), "附件目录包含符号链接，拒绝备份")
            if stat.S_ISDIR(info.st_mode):
                directories.add(relative)
                metadata[relative] = stamp(info)
            else:
                require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, "附件须是没有硬链接的普通文件")
                files[relative] = (path, stamp(info))
    require(len(files) <= MAX_FILES and sum(item[1][4] for item in files.values()) <= MAX_BYTES,
            "快照超过100000个文件或100GiB的工具上限")
    return files, directories, metadata


def assert_offline(database):
    stem = str(database)[:-len(".mv.db")]
    require(not any(path.exists() or path.is_symlink() for path in (Path(stem + ".lock.db"), Path(stem + ".temp.db"))),
            "发现H2锁文件或临时数据库，须由服务所有者正常关闭数据库后再备份")
    program = shutil.which("lsof")
    require(program is not None, "缺少系统 lsof，无法确认数据库离线，拒绝备份")
    result = subprocess.run([program, "-t", "--", str(database)], capture_output=True, text=True, timeout=15)
    require(result.returncode in (0, 1) and not result.stderr.strip(), "无法完整检查数据库占用状态，拒绝备份")
    require(all(value == str(os.getpid()) for value in result.stdout.split()), "数据库仍被其他进程打开，拒绝备份")


@contextlib.contextmanager
def offline_database(path):
    try:
        import fcntl
    except ImportError as error:
        raise BackupError("本工具仅支持具有POSIX文件锁的macOS/Linux") from error
    assert_offline(path)
    descriptor = os.open(path, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "r+b") as handle:
        try:
            fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise BackupError("数据库仍被锁定，拒绝备份") from error
        assert_offline(path)
        yield handle


def zip_entry(name):
    entry = zipfile.ZipInfo(name)
    entry.create_system = 3
    entry.external_attr = (stat.S_IFREG | 0o600) << 16
    entry.compress_type = zipfile.ZIP_DEFLATED
    return entry


def sign(payload, key):
    return {"payload": payload, "hmacSha256": hmac.new(key, canonical(payload), hashlib.sha256).hexdigest()}


def backup(database_value, uploads_value, output_value, key_value):
    database, uploads, output = path_argument(database_value), path_argument(uploads_value), new_target(output_value)
    key_path, key = read_key(key_value)
    require(database.name.endswith(".mv.db") and database.is_file(), "--db-file须指向离线H2的 .mv.db 文件")
    require(uploads.is_dir(), "--uploads须指向附件目录")
    require(not key_path.is_relative_to(uploads) and key_path != database, "签名密钥必须存放在备份源之外")
    require(not output.is_relative_to(uploads) and output != database, "备份输出不能位于附件源内或覆盖数据库")
    temporary = None
    try:
        with offline_database(database) as database_handle:
            before = inventory(database, uploads)
            require(stamp(os.fstat(database_handle.fileno())) == before[0][DATABASE][1], "数据库文件在检查过程中已变化")
            descriptor, temporary_name = tempfile.mkstemp(prefix=".backup-", dir=output.parent)
            temporary = Path(temporary_name)
            os.fchmod(descriptor, 0o600)
            records = []
            with os.fdopen(descriptor, "w+b") as target:
                with zipfile.ZipFile(target, "w", allowZip64=True) as archive:
                    for name, (source, expected) in sorted(before[0].items()):
                        digest, size = hashlib.sha256(), 0
                        with contextlib.ExitStack() as stack:
                            handle = database_handle if name == DATABASE else stack.enter_context(source.open("rb"))
                            require(stamp(os.fstat(handle.fileno())) == expected, "备份源在复制前已变化")
                            with archive.open(zip_entry(name), "w", force_zip64=True) as member:
                                for block in iter(lambda: handle.read(CHUNK), b""):
                                    member.write(block)
                                    digest.update(block)
                                    size += len(block)
                            require(stamp(os.fstat(handle.fileno())) == expected and size == expected[4], "备份源在复制期间已变化")
                        records.append({"path": name, "size": size, "sha256": digest.hexdigest()})
                    payload = {"format": FORMAT, "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                               "database": DATABASE, "directories": sorted(before[1]), "files": records}
                    archive.writestr(zip_entry("manifest.json"), canonical(sign(payload, key)))
                target.flush()
                os.fsync(target.fileno())
            verify(temporary, key_value)
            require(inventory(database, uploads) == before, "数据库或附件目录在备份期间变化，未发布备份")
            assert_offline(database)
            # A hard link publishes the complete archive atomically and never replaces an existing path.
            os.link(temporary, output)
        return {"files": len(records), "bytes": sum(item["size"] for item in records)}
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def safe_name(value):
    require(isinstance(value, str) and 0 < len(value) <= 1024 and "\\" not in value and "\x00" not in value, "备份包含非法路径")
    path = PurePosixPath(value)
    require(not path.is_absolute() and all(part not in ("", ".", "..") for part in value.split("/")) and str(path) == value,
            "备份包含路径穿越或非规范路径")
    require(value == DATABASE or value == "database" or value == "uploads" or value.startswith("uploads/"), "备份路径超出数据库和附件范围")
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "manifest包含重复字段")
        result[key] = value
    return result


def manifest(archive, key):
    entries = archive.infolist()
    require(len(entries) <= MAX_FILES + 1 and len({entry.filename for entry in entries}) == len(entries), "备份包含过多或重复的ZIP条目")
    entry = archive.getinfo("manifest.json")
    require(entry.file_size <= MAX_MANIFEST, "manifest过大")
    envelope = json.loads(archive.read(entry), object_pairs_hook=unique_object)
    require(isinstance(envelope, dict) and set(envelope) == {"payload", "hmacSha256"}, "manifest结构错误")
    payload, signature = envelope["payload"], envelope["hmacSha256"]
    require(isinstance(signature, str) and re.fullmatch(r"[0-9a-f]{64}", signature), "manifest签名格式错误")
    require(hmac.compare_digest(signature, hmac.new(key, canonical(payload), hashlib.sha256).hexdigest()), "备份签名校验失败，密钥错误或manifest已被篡改")
    require(isinstance(payload, dict) and set(payload) == {"format", "createdAt", "database", "directories", "files"}
            and payload["format"] == FORMAT and payload["database"] == DATABASE, "不支持的备份格式")
    require(isinstance(payload["directories"], list) and isinstance(payload["files"], list), "manifest文件目录格式错误")
    directories = [safe_name(name) for name in payload["directories"]]
    require(len(set(directories)) == len(directories) and {"database", "uploads"}.issubset(directories)
            and DATABASE not in directories, "manifest目录列表错误")
    records = {}
    for record in payload["files"]:
        require(isinstance(record, dict) and set(record) == {"path", "size", "sha256"}, "manifest文件记录错误")
        name = safe_name(record["path"])
        require(name != "database" and name != "uploads" and name not in records and name not in directories,
                "manifest文件重复或与目录冲突")
        require(type(record["size"]) is int and record["size"] >= 0 and isinstance(record["sha256"], str)
                and re.fullmatch(r"[0-9a-f]{64}", record["sha256"]), "manifest文件校验字段错误")
        records[name] = record
    require(DATABASE in records and len(records) <= MAX_FILES and sum(item["size"] for item in records.values()) <= MAX_BYTES,
            "备份缺少数据库或超出100000文件/100GiB上限")
    for name in list(records) + directories:
        require(all(str(parent) in directories for parent in PurePosixPath(name).parents if str(parent) != "."), "manifest缺少父目录")
    require({entry.filename for entry in entries} == set(records) | {"manifest.json"}, "ZIP条目与manifest不一致")
    for entry in entries:
        mode = entry.external_attr >> 16
        require(not entry.is_dir() and stat.S_IFMT(mode) in (0, stat.S_IFREG) and not entry.flag_bits & 1,
                "备份包含符号链接、特殊文件或不支持的加密条目")
        if entry.filename in records:
            require(entry.file_size == records[entry.filename]["size"], "ZIP文件长度与manifest不一致")
    return payload, records


def consume(archive, records, destination=None):
    for name, record in records.items():
        digest, size = hashlib.sha256(), 0
        with contextlib.ExitStack() as stack:
            source = stack.enter_context(archive.open(name))
            target = None
            if destination is not None:
                descriptor = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                target = stack.enter_context(os.fdopen(descriptor, "wb"))
            for block in iter(lambda: source.read(CHUNK), b""):
                size += len(block)
                require(size <= record["size"], "备份解压长度超出manifest")
                digest.update(block)
                if target is not None:
                    target.write(block)
            require(size == record["size"] and hmac.compare_digest(digest.hexdigest(), record["sha256"]), "备份文件SHA256校验失败，拒绝恢复")
            if target is not None:
                target.flush()
                os.fsync(target.fileno())


def verify(archive_value, key_value):
    _, key = read_key(key_value)
    with zipfile.ZipFile(path_argument(archive_value)) as archive:
        payload, records = manifest(archive, key)
        consume(archive, records)
    return {"files": len(records), "bytes": sum(item["size"] for item in records.values())}


def restore(archive_value, destination_value, key_value):
    destination, temporary, reserved = new_target(destination_value), None, None
    _, key = read_key(key_value)
    try:
        with zipfile.ZipFile(path_argument(archive_value)) as archive:
            payload, records = manifest(archive, key)
            temporary = Path(tempfile.mkdtemp(prefix=".restore-", dir=destination.parent))
            for name in sorted(payload["directories"], key=lambda name: (name.count("/"), name)):
                (temporary / name).mkdir(mode=0o700)
            consume(archive, records, temporary)
            private_file(temporary / "manifest.json", canonical(sign(payload, key)))
        # Reserve a fresh, empty directory exclusively; only that reservation may be replaced.
        destination.mkdir(mode=0o700)
        reserved = destination.stat().st_ino
        require(destination.stat(follow_symlinks=False).st_ino == reserved and not destination.is_symlink(), "恢复目标发生变化")
        os.rename(temporary, destination)
        temporary = None
        return {"files": len(records), "bytes": sum(item["size"] for item in records.values())}
    except BaseException:
        if reserved is not None and destination.exists() and destination.stat(follow_symlinks=False).st_ino == reserved:
            destination.rmdir()
        raise
    finally:
        if temporary is not None:
            shutil.rmtree(temporary)


def self_test():
    def rejected(operation):
        try:
            operation()
        except (BackupError, OSError, ValueError, zipfile.BadZipFile):
            return
        raise AssertionError("应拒绝的操作意外成功")

    def rewrite(source, target, change):
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, "w") as edited:
            for entry in original.infolist():
                data = original.read(entry)
                edited.writestr(entry, change(entry.filename, data))

    with tempfile.TemporaryDirectory(prefix="wanfu-backup-selftest-") as name:
        root = Path(name)
        database, uploads, key = root / "fixture.mv.db", root / "uploads", root / "signing.key"
        database.write_bytes(b"fictional-offline-database-fixture\x00" * 100)
        (uploads / "nested").mkdir(parents=True)
        (uploads / "empty").mkdir()
        (uploads / "nested" / "fictional.png").write_bytes(b"fictional-private-attachment\x00")
        init_key(key)
        output, destination = root / "snapshot.zip", root / "restored"
        backup(database, uploads, output, key)
        assert verify(output, key) == {"files": 2, "bytes": database.stat().st_size + 29}
        restore(output, destination, key)
        assert (destination / DATABASE).read_bytes() == database.read_bytes()
        assert (destination / "uploads/nested/fictional.png").read_bytes() == (uploads / "nested/fictional.png").read_bytes()
        assert (destination / "uploads/empty").is_dir()
        assert stat.S_IMODE(output.stat().st_mode) == 0o600 and stat.S_IMODE(destination.stat().st_mode) == 0o700
        for item in destination.rglob("*"):
            assert stat.S_IMODE(item.stat().st_mode) == (0o700 if item.is_dir() else 0o600)
        rejected(lambda: restore(output, destination, key))
        rejected(lambda: backup(database, uploads, output, key))
        assert (destination / DATABASE).read_bytes() == database.read_bytes()
        bad = root / "tampered.zip"
        rewrite(output, bad, lambda path, data: b"X" + data[1:] if path == "uploads/nested/fictional.png" else data)
        rejected(lambda: verify(bad, key))
        rejected(lambda: restore(bad, root / "failed", key))
        assert not (root / "failed").exists() and not list(root.glob(".restore-*"))
        forged = root / "forged-manifest.zip"
        def forge(path, data):
            if path == "manifest.json":
                envelope = json.loads(data)
                envelope["payload"]["createdAt"] = "forged"
                return canonical(envelope)
            return data
        rewrite(output, forged, forge)
        rejected(lambda: verify(forged, key))
        wrong_key = root / "wrong.key"
        init_key(wrong_key)
        rejected(lambda: verify(output, wrong_key))
        traversal = root / "traversal.zip"
        with zipfile.ZipFile(output) as source, zipfile.ZipFile(traversal, "w") as target:
            payload = json.loads(source.read("manifest.json"))["payload"]
            payload["files"].append({"path": "../escape", "size": 1, "sha256": hashlib.sha256(b"x").hexdigest()})
            for entry in source.infolist():
                target.writestr(entry, canonical(sign(payload, read_key(key)[1])) if entry.filename == "manifest.json" else source.read(entry))
            target.writestr(zip_entry("../escape"), b"x")
        rejected(lambda: restore(traversal, root / "unsafe", key))
        assert not (root / "escape").exists() and not (root / "unsafe").exists()
        linked_archive = root / "linked.zip"
        with zipfile.ZipFile(output) as source, zipfile.ZipFile(linked_archive, "w") as target:
            for entry in source.infolist():
                if entry.filename == "uploads/nested/fictional.png":
                    entry.external_attr = (stat.S_IFLNK | 0o600) << 16
                target.writestr(entry, source.read(entry))
        rejected(lambda: restore(linked_archive, root / "linked-restore", key))
        link = uploads / "outside-link"
        link.symlink_to(database)
        rejected(lambda: backup(database, uploads, root / "symlink.zip", key))
        link.unlink()
        lock_path = Path(str(database)[:-len(".mv.db")] + ".lock.db")
        lock_path.write_bytes(b"fixture-lock")
        rejected(lambda: backup(database, uploads, root / "locked.zip", key))
        lock_path.unlink()
        process = subprocess.Popen([sys.executable, "-c", "import fcntl,sys; f=open(sys.argv[1],'r+b'); fcntl.lockf(f,fcntl.LOCK_EX); print('ready',flush=True); sys.stdin.read()", str(database)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            assert process.stdout.readline().strip() == "ready"
            rejected(lambda: backup(database, uploads, root / "running.zip", key))
        finally:
            process.communicate("", timeout=5)
        assert not list(root.glob(".backup-*")) and not list(root.glob(".restore-*"))
    return {"checks": "离线文件快照、字节恢复、私有权限、已有目标拒绝、SHA256、签名、防穿越、源符号链接与运行中库拒绝、失败清理"}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="离线H2数据库和附件一致快照；不启动或停止服务。备份签名但不加密。")
    actions = parser.add_subparsers(dest="action", required=True)
    key = actions.add_parser("init-key", help="创建独立的32字节私有签名密钥（不要随备份保存）")
    key.add_argument("--key-file", required=True)
    create = actions.add_parser("backup", help="服务已由所有者正常停止后创建一致快照")
    create.add_argument("--db-file", required=True)
    create.add_argument("--uploads", required=True)
    create.add_argument("--output", required=True)
    create.add_argument("--key-file", required=True)
    check = actions.add_parser("verify", help="校验签名、每文件SHA256和归档结构")
    check.add_argument("--archive", required=True)
    check.add_argument("--key-file", required=True)
    recover = actions.add_parser("restore", help="校验后恢复至全新目录，拒绝覆盖")
    recover.add_argument("--archive", required=True)
    recover.add_argument("--destination", required=True)
    recover.add_argument("--key-file", required=True)
    actions.add_parser("self-test", help="仅用临时虚构文件自检，不访问业务目录")
    args = parser.parse_args()
    if args.action == "init-key":
        init_key(args.key_file)
        result = {"status": "私有签名密钥已创建；须独立保管"}
    elif args.action == "backup":
        result = backup(args.db_file, args.uploads, args.output, args.key_file)
    elif args.action == "verify":
        result = verify(args.archive, args.key_file)
    elif args.action == "restore":
        result = restore(args.archive, args.destination, args.key_file)
    else:
        result = self_test()
    print(json.dumps({"action": args.action, "success": True, **result}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except BackupError as error:
        print("错误：" + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, EOFError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print("错误：操作失败（" + type(error).__name__ + "），未完成的临时数据已清理；请核对路径、权限和备份结构。", file=sys.stderr)
        sys.exit(1)
