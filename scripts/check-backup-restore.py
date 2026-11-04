#!/usr/bin/env python3
"""Optional real-H2 verification; all generated files stay in a temporary directory."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description="使用本机已有H2 JAR做临时文件库恢复验证，不运行Maven或业务服务")
    parser.add_argument("--h2-jar", required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    jar = Path(args.h2_jar).resolve(strict=True)
    tool = Path(__file__).with_name("backup-restore.py")

    def command(values, expected=0):
        result = subprocess.run(values, capture_output=True, text=True, timeout=30)
        if result.returncode != expected:
            raise AssertionError("隔离验证命令退出状态异常：" + result.stderr)
        return result

    with tempfile.TemporaryDirectory(prefix="wanfu-h2-restore-check-") as name:
        root = Path(name)
        source = root / "BackupFixture.java"
        source.write_text('''
import java.sql.*;
class BackupFixture {
 public static void main(String[] args) throws Exception {
  try (var c=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";DB_CLOSE_ON_EXIT=FALSE","sa",""); var s=c.createStatement()) {
   if(args[1].equals("create")) {
    s.execute("CREATE TABLE fixture(id INT PRIMARY KEY,balance DECIMAL(10,2),attachment VARCHAR(80))");
    s.execute("INSERT INTO fixture VALUES(1,12.50,'fictional.bin')");
   } else if(args[1].equals("hold")) {
    System.out.println("ready"); System.out.flush(); System.in.read();
   } else {
    try(var rows=s.executeQuery("SELECT balance,attachment FROM fixture WHERE id=1")) {
     if(!rows.next() || rows.getBigDecimal(1).compareTo(new java.math.BigDecimal("12.50"))!=0 || !rows.getString(2).equals("fictional.bin")) throw new AssertionError("fixture recovery mismatch");
    }
   }
  }
 }
}
''', encoding="utf-8")
        java = [args.java, "--class-path", str(jar), str(source)]
        command(java + [str(root / "source"), "create"])
        uploads = root / "uploads"
        uploads.mkdir()
        (uploads / "fictional.bin").write_bytes(b"fictional-private-attachment\x00")
        key, archive, restored = root / "signing.key", root / "snapshot.zip", root / "restored"
        command([sys.executable, str(tool), "self-test"])
        command([sys.executable, str(tool), "init-key", "--key-file", str(key)])
        backup = [sys.executable, str(tool), "backup", "--db-file", str(root / "source.mv.db"), "--uploads", str(uploads), "--output", str(archive), "--key-file", str(key)]
        process = subprocess.Popen(java + [str(root / "source"), "hold"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            if process.stdout.readline().strip() != "ready":
                raise AssertionError("临时H2连接未就绪")
            refusal = command(backup, 1)
            assert "仍被" in refusal.stderr or "H2锁文件" in refusal.stderr
            assert not archive.exists()
        finally:
            process.communicate("\n", timeout=10)
        command(backup)
        command([sys.executable, str(tool), "verify", "--archive", str(archive), "--key-file", str(key)])
        command([sys.executable, str(tool), "restore", "--archive", str(archive), "--destination", str(restored), "--key-file", str(key)])
        assert (restored / "uploads/fictional.bin").read_bytes() == (uploads / "fictional.bin").read_bytes()
        command(java + [str(restored / "database/wanfu"), "check"])
        command(java + [str(restored / "database/wanfu"), "check"])
    print(json.dumps({"success": True, "h2Jar": jar.name, "checks": "运行中H2拒绝、关闭后DB与附件快照、恢复到新目录、JDBC查询及关闭重开一致、标准库安全自检"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
