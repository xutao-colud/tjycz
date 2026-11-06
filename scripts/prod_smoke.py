#!/usr/bin/env python3
"""Verify empty production setup using explicit administrator credentials."""
import argparse
import concurrent.futures
import datetime
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import uuid
from zoneinfo import ZoneInfo


def main():
    parser = argparse.ArgumentParser(description='验证独立生产测试库。会创建测试班级和账号，请勿用于正式业务库。')
    parser.add_argument('--base', required=True, help='显式指定独立验收服务，勿指向正式业务库')
    args = parser.parse_args()
    password = os.environ.get('VERIFY_ADMIN_PASSWORD')
    if not password:
        raise SystemExit('需要设置 VERIFY_ADMIN_PASSWORD，且目标必须是可写的独立测试库。')
    base = args.base.rstrip('/')

    def request(method, path, payload=None, token=None, expected=200):
        if method == 'POST' and path in ('/auth/login', '/users'):
            payload = dict(payload)
            plaintext = payload.pop('password')
            payload['username'] = payload['username'].strip()
            purpose = 'LOGIN' if path == '/auth/login' else 'CREATE_USER'
            challenge = request('GET', '/auth/challenge?purpose=' + purpose, token=token)
            encrypted = subprocess.run(
                ['node', str(Path(__file__).resolve().with_name('encrypt-password.cjs'))],
                input=json.dumps({'challenge': challenge, 'purpose': purpose, 'username': payload['username'], 'password': plaintext}),
                text=True, capture_output=True, timeout=10,
            )
            if encrypted.returncode != 0 or not encrypted.stdout:
                raise RuntimeError('Password encryption failed.')
            payload.update(challengeId=challenge['challengeId'], encryptedPassword=encrypted.stdout)
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(base + path, json.dumps(payload).encode() if payload is not None else None, headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                raw, status = response.read(), response.status
        except urllib.error.HTTPError as error:
            raw, status = error.read(), error.code
        result = json.loads(raw) if raw else None
        allowed = expected if isinstance(expected, tuple) else (expected,)
        assert status in allowed, f'{method} {path}: {status}, {result}'
        return result

    health = request('GET', '/health')
    assert health.get('demo') is False, '必须关闭演示环境'
    request('POST', '/auth/demo', {'role': 'ADMIN'}, expected=(403, 404))
    request('POST', '/auth/login', {'username': 'admin', 'password': 'Admin123!'}, expected=401)
    request('GET', '/students', expected=401)
    admin = request('POST', '/auth/login', {'username': os.environ.get('VERIFY_ADMIN_USERNAME', 'admin'), 'password': password})['token']
    suffix = uuid.uuid4().hex[:8]
    today = datetime.datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()
    classroom = request('POST', '/classes', {'name': '生产验收-' + suffix, 'teacherName': '验证老师'}, admin)
    other_class = request('POST', '/classes', {'name': '隔离班-' + suffix, 'teacherName': '其他老师'}, admin)

    def student(class_id, name):
        return request('POST', '/students', {'name': name + suffix, 'gender': '男', 'grade': '三年级', 'classId': class_id, 'parentName': '验证监护人', 'parentPhone': '13900000000'}, admin)

    child = student(classroom['id'], '生产验证学生')
    outsider = student(other_class['id'], '隔离学生')
    sid = child['id']
    check_in_place = next(c for c in request('GET', '/campuses', token=admin) if c['id'] == child['campusId'])
    assert check_in_place.get('checkInRadius'), '请先在隔离验收校区配置签到地点'
    user_password = 'VerifyOnly2026!abc'
    for role, associations in [('TEACHER', {'classIds': [classroom['id']]}), ('PARENT', {'studentIds': [sid]})]:
        request('POST', '/users', {'name': '验证' + role, 'username': role.lower() + suffix, 'password': user_password, 'role': role, **associations}, admin)
    teacher = request('POST', '/auth/login', {'username': 'teacher' + suffix, 'password': user_password})['token']
    parent = request('POST', '/auth/login', {'username': 'parent' + suffix, 'password': user_password})['token']
    assert {s['id'] for s in request('GET', '/students', token=parent)} == {sid}
    assert {s['id'] for s in request('GET', '/students', token=teacher)} == {sid}
    for token in (teacher, parent):
        request('GET', f'/students/{outsider["id"]}', token=token, expected=(403, 404))
    request('POST', '/courses', {'name': '越权课程', 'type': 'EVENING', 'unit': '次', 'price': 0, 'warningThreshold': 2}, teacher, expected=403)
    print('✓ 生产禁用演示入口与默认账号，持久化班级/监护人授权生效')
    course = request('POST', '/courses', {'name': '生产验证课程-' + suffix, 'type': 'EVENING', 'unit': '次', 'price': 5000, 'warningThreshold': 2}, admin)
    enrollment = request('POST', '/enrollments', {'studentId': sid, 'courseId': course['id']}, admin)
    aid = enrollment['accountId']
    request('POST', '/schedules', {'courseId': course['id'], 'classId': classroom['id'], 'date': today, 'startTime': '16:00', 'endTime': '20:00', 'units': 1}, admin)
    request('POST', '/attendance/check-in', {'studentIds': [sid], 'date': today, 'campusId': check_in_place['id'], 'location': {'latitude': check_in_place['checkInLatitude'], 'longitude': check_in_place['checkInLongitude'], 'accuracy': 5, 'capturedAt': datetime.datetime.now().timestamp() * 1000}}, teacher)
    pickup = request('POST', '/pickups', {'studentId': sid, 'date': today, 'pickupName': '验证监护人', 'relationship': '监护人', 'phone': '13900000000', 'requestId': str(uuid.uuid4())}, parent)
    request('POST', f'/pickups/{pickup["id"]}/verify', {'pickupName': '验证监护人', 'phoneLast4': '0000', 'code': pickup['pickupCode'], 'confirmedIdentity': True, 'expectedVersion': pickup['version'], 'requestId': str(uuid.uuid4())}, teacher)
    checked_out = request('POST', '/attendance/check-out', {'studentIds': [sid], 'date': today}, teacher)
    assert checked_out['results'][0]['success']
    pending = [s for s in request('GET', '/settlements', token=teacher) if s['studentId'] == sid]
    assert len(pending) == 1 and pending[0]['status'] == 'PENDING'
    print('✓ 余额不足仍可签退，建立待补扣记录')
    renewal = request('POST', '/renewals', {'accountId': aid, 'amount': 20000, 'lessons': 4, 'giftLessons': 1, 'paymentMethod': 'CASH', 'note': '真实MySQL验证', 'requestId': str(uuid.uuid4())}, admin)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda _: request('POST', f'/renewals/{renewal["id"]}/confirm', {}, admin), range(4)))
    request('POST', f'/settlements/{pending[0]["id"]}/retry', {}, teacher)
    request('POST', f'/settlements/{pending[0]["id"]}/retry', {}, teacher)
    accounts = request('GET', f'/accounts?studentId={sid}', token=admin)
    assert float(next(a for a in accounts if a['id'] == aid)['balance']) == 4
    ledger = request('GET', f'/lesson-ledger?accountId={aid}', token=admin)
    assert len(ledger) == 2
    assert sum(float(l['amount']) for l in ledger) == 4
    print('✓ MySQL并发续费只入账一次，待处理消课重复补扣无重复记账')
    request('POST', '/summaries/generate', {'date': today, 'studentIds': [sid]}, teacher)
    summaries = request('GET', f'/summaries?studentId={sid}&date={today}', token=teacher)
    draft = summaries[0]
    assert request('GET', f'/summaries?studentId={sid}&date={today}', token=parent) == []
    edited = request('PATCH', f'/summaries/{draft["id"]}', {'teacherComment': '真实MySQL验证评语'}, teacher)
    submitted = request('POST', f'/summaries/{draft["id"]}/submit-review', {'expectedVersion': edited['version']}, teacher)
    request('POST', f'/summaries/{draft["id"]}/review', {'expectedVersion': submitted['version'], 'approved': True, 'comment': '独立审核通过'}, admin)
    published = request('POST', f'/summaries/{draft["id"]}/publish', {}, teacher)
    visible = request('GET', f'/summaries?studentId={sid}&date={today}', token=parent)
    assert len(visible) == 1 and visible[0]['content'] == published['content']
    request('POST', f'/summaries/{draft["id"]}/feedback', {'satisfaction': 'SATISFIED', 'text': '已收到，谢谢老师'}, parent)
    feedback = request('GET', f'/summaries?studentId={sid}&date={today}', token=teacher)[0]
    assert 'feedbackSatisfaction' not in feedback and 'feedbackText' not in feedback
    own_feedback = request('GET', f'/summaries?studentId={sid}&date={today}', token=parent)[0]
    assert own_feedback['feedbackSatisfaction'] == 'SATISFIED'
    request('PATCH', f'/summaries/{draft["id"]}', {'teacherComment': '不应覆盖已发布评语'}, teacher, expected=(400, 409))
    print('✓ 家长不能查看草稿，发布快照、评分反馈与禁止覆盖生效')
    points_body = {'studentId': sid, 'amount': 2, 'reason': '生产验证奖励', 'requestId': str(uuid.uuid4())}
    request('POST', '/points', points_body, teacher)
    request('POST', '/points', points_body, teacher)
    request('POST', '/points', {**points_body, 'amount': 3}, teacher, expected=409)
    assert len(request('GET', f'/points?studentId={sid}', token=teacher)) == 1
    print('✓ 相同积分请求幂等，不同内容复用requestId拒绝')
    print('真实 MySQL 生产配置验收全部通过。')


if __name__ == '__main__':
    main()
