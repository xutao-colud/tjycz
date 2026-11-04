#!/usr/bin/env python3
"""Exercise campus authorization with isolated local demo records."""
import argparse
import io
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import uuid
import wave
from datetime import datetime
from zoneinfo import ZoneInfo


def main():
    parser = argparse.ArgumentParser(description='多校区权限验收：仅local，会创建标记“校区验收”的独立数据。')
    parser.add_argument('--base', required=True, help='显式指定隔离验收服务')
    args = parser.parse_args()
    base = args.base.rstrip('/')
    helper = Path(__file__).with_name('encrypt-password.cjs')
    suffix = uuid.uuid4().hex[:8]
    today = datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()

    def request(method, path, data=None, token=None, campus=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if campus is not None:
            headers['X-Campus-Id'] = str(campus)
        req = urllib.request.Request(base + path, json.dumps(data).encode() if data is not None else None, headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        value = json.loads(raw) if raw else None
        allowed = expected if isinstance(expected, tuple) else (expected,)
        assert status in allowed, f'{method} {path}: expected {allowed}, actual {status}, {value}'
        return value

    def encrypted(purpose, username, password):
        challenge = request('GET', '/auth/challenge?purpose=' + purpose)
        result = subprocess.run(['node', str(helper)], input=json.dumps({'challenge': challenge, 'purpose': purpose, 'username': username, 'password': password}), text=True, capture_output=True, check=True)
        return {'username': username, 'challengeId': challenge['challengeId'], 'encryptedPassword': result.stdout}

    def create_user(name, role, **grants):
        username = name + suffix
        password = 'CampusTestOnly2026!'
        body = {**encrypted('CREATE_USER', username, password), 'name': name, 'role': role, **grants}
        user = request('POST', '/users', body, super_token)
        session = request('POST', '/auth/login', encrypted('LOGIN', username, password))
        assert session['user']['role'] == role
        return user, session['token']

    assert request('GET', '/health')['demo'] is True, '仅允许local演示环境'
    session = request('POST', '/auth/demo', {'role': 'ADMIN'})
    assert session['user']['role'] == 'SUPER_ADMIN'
    super_token = session['token']
    campuses = [request('POST', '/campuses', {'name': '校区验收' + name + '-' + suffix, 'address': '独立权限验收'}, super_token) for name in ('甲', '乙')]
    ca, cb = [c['id'] for c in campuses]
    for c in campuses:
        request('PUT', f'/campuses/{c["id"]}/check-in-location', {'address': '虚构校区验收地点', 'latitude': 31.23, 'longitude': 121.47, 'radiusMeters': 200}, super_token)
    classes = [request('POST', '/classes', {'name': '校区验收班-' + suffix, 'teacherName': '验收教师', 'campusId': c['id']}, super_token) for c in campuses]
    courses = [request('POST', '/courses', {'name': '校区验收课-' + suffix, 'type': 'EVENING', 'unit': '次', 'price': 5000, 'warningThreshold': 2, 'campusId': c['id']}, super_token) for c in campuses]
    students = [request('POST', '/students', {'name': '校区验收学生' + str(i), 'gender': '男', 'grade': '三年级', 'classId': cl['id'], 'parentName': '验收家长', 'parentPhone': '13800000000'}, super_token) for i, cl in enumerate(classes)]
    sa, sb = [s['id'] for s in students]
    principals = [create_user('验收校长' + str(i), 'ADMIN', campusIds=[c['id']]) for i, c in enumerate(campuses)]
    pa, pb = [p[1] for p in principals]
    teacher, ta = create_user('验收教师', 'TEACHER', classIds=[classes[0]['id']])
    _, parent = create_user('验收家长', 'PARENT', studentIds=[sa])
    _, cross_parent = create_user('跨校区家长', 'PARENT', studentIds=[sa, sb])
    assert request('GET', '/me', token=pa)['role'] == 'ADMIN'
    assert {c['id'] for c in request('GET', '/campuses', token=pa)} == {ca}
    assert {ca, cb}.issubset({c['id'] for c in request('GET', '/campuses', token=super_token, campus=ca)})
    for path in ('/students', '/classes', '/courses', '/users', '/dashboard', '/schedules', '/accounts', '/lesson-ledger', '/renewals', '/summaries', '/mistakes', '/fitness', '/points', '/settlements', '/audit-logs'):
        request('GET', path, token=pa)
        request('GET', path, token=pa, campus=cb, expected=403)
    assert {s['id'] for s in request('GET', '/students', token=pa)} == {sa}
    assert {s['id'] for s in request('GET', '/students', token=pb)} == {sb}
    assert {sa, sb}.issubset({s['id'] for s in request('GET', '/students', token=super_token)})
    assert {s['id'] for s in request('GET', '/students', token=super_token, campus=ca)} == {sa}
    assert {s['id'] for s in request('GET', '/students', token=ta)} == {sa}
    assert {s['id'] for s in request('GET', '/students', token=parent)} == {sa}
    assert {s['id'] for s in request('GET', '/students', token=cross_parent)} == {sa, sb}
    request('GET', f'/students/{sb}', token=pa, expected=403)
    request('POST', '/classes', {'name': '应拒绝', 'teacherName': '应拒绝', 'campusId': cb}, pa, expected=403)
    request('POST', '/campuses', {'name': '应拒绝', 'address': ''}, pa, expected=403)
    request('PATCH', '/settings', {'institutionName': '应拒绝'}, pa, expected=403)
    request('POST', '/attendance/check-in', {'studentIds': [sa, sb], 'date': today}, pa, expected=403)
    print('✓ 总管理员跨校区/单校区查询，校长、老师、家长数据隔离；伪造校区头、跨校区ID和混合批量均拒绝')

    accounts = [request('POST', '/enrollments', {'studentId': sid, 'courseId': c['id']}, super_token)['accountId'] for sid, c in zip((sa, sb), courses)]
    request('POST', '/enrollments', {'studentId': sa, 'courseId': courses[1]['id']}, super_token, expected=(400, 403))
    request('POST', '/schedules', {'courseId': courses[1]['id'], 'classId': classes[0]['id'], 'date': today, 'startTime': '16:00', 'endTime': '20:00', 'units': 1}, super_token, expected=(400, 403))
    for campus, cl, course in zip(campuses, classes, courses):
        request('POST', '/schedules', {'courseId': course['id'], 'classId': cl['id'], 'date': today, 'startTime': '16:00', 'endTime': '20:00', 'units': 1}, super_token, campus=campus['id'])
    renewals = [request('POST', '/renewals', {'accountId': aid, 'amount': 50000, 'lessons': 10, 'giftLessons': 1, 'paymentMethod': 'CASH', 'note': '校区权限验收', 'requestId': str(uuid.uuid4())}, super_token) for aid in accounts]
    request('POST', f'/renewals/{renewals[1]["id"]}/confirm', {}, pa, expected=403)
    request('POST', '/renewals', {'accountId': accounts[1], 'amount': 1, 'lessons': 1, 'giftLessons': 0, 'paymentMethod': 'CASH', 'note': '', 'requestId': str(uuid.uuid4())}, pa, expected=403)
    for renewal in renewals:
        request('POST', f'/renewals/{renewal["id"]}/confirm', {}, super_token)
    for sid, campus_id in ((sa, ca), (sb, cb)):
        result = request('POST', '/attendance/check-in', {'studentIds': [sid], 'date': today, 'campusId': campus_id, 'location': {'latitude': 31.23, 'longitude': 121.47, 'accuracy': 5, 'capturedAt': datetime.datetime.now().timestamp() * 1000}}, super_token)
        assert all(row['success'] for row in result['results']), result
    for sid in (sa, sb):
        pickup = request('POST', '/pickups', {'studentId': sid, 'date': today, 'pickupName': '虚构跨校区监护人', 'relationship': '监护人', 'phone': '13800000000', 'requestId': str(uuid.uuid4())}, cross_parent)
        request('POST', f'/pickups/{pickup["id"]}/verify', {'pickupName': '虚构跨校区监护人', 'phoneLast4': '0000', 'code': pickup['pickupCode'], 'confirmedIdentity': True, 'expectedVersion': pickup['version'], 'requestId': str(uuid.uuid4())}, super_token)
    checked_out = request('POST', '/attendance/check-out', {'studentIds': [sa, sb], 'date': today}, super_token)
    assert all(row['success'] for row in checked_out['results']), checked_out
    ledger = request('GET', f'/lesson-ledger?accountId={accounts[1]}', token=super_token)
    consumption = next(l for l in ledger if l['type'] == 'CONSUMPTION')
    request('POST', f'/lesson-ledger/{consumption["id"]}/reverse', {'reason': '应拒绝'}, pa, expected=403)
    settlements = request('GET', '/settlements', token=super_token)
    foreign_settlement = next(s for s in settlements if s['studentId'] == sb)
    request('POST', f'/settlements/{foreign_settlement["id"]}/retry', {}, pa, expected=403)
    print('✓ 课程报名与排课不能跨校区；校长不能确认外校续费、冲正或补扣外校课时')

    own_users = request('GET', '/users', token=pa)
    assert teacher['id'] in {u['id'] for u in own_users}
    assert not any(u.get('username', '').startswith('跨校区家长') for u in own_users)
    request('PUT', f'/users/{teacher["id"]}/access', {'classIds': [classes[1]['id']]}, pa, expected=403)
    request('PUT', f'/users/{principals[0][0]["id"]}/access', {'campusIds': [cb]}, pa, expected=403)
    body = {**encrypted('CREATE_USER', '拒绝提权' + suffix, 'CampusTestOnly2026!'), 'name': '拒绝提权', 'role': 'ADMIN', 'campusIds': [ca]}
    request('POST', '/users', body, pa, expected=403)
    request('PUT', f'/users/{principals[0][0]["id"]}/access', {'campusIds': [cb]}, super_token)
    assert {s['id'] for s in request('GET', '/students', token=pa)} == {sb}, '旧会话未立即使用新授权'
    request('GET', f'/students/{sa}', token=pa, expected=403)
    request('PUT', f'/users/{principals[0][0]["id"]}/access', {'campusIds': [ca]}, super_token)
    print('✓ 校长只能管理本校区完整授权账号，不能自我扩权；总管理员修改授权后旧会话立即生效')

    tasks = [request('POST', '/homework/tasks', {'date': today, 'subject': '英语', 'type': 'ORAL', 'title': '校区验收语音-' + suffix, 'description': '附件权限', 'studentIds': [sid]}, super_token) for sid in (sa, sb)]
    assignments = request('GET', '/homework', token=super_token)
    homework = [next(h for h in assignments if h['taskId'] == task['taskId']) for task in tasks]
    audio = io.BytesIO()
    with wave.open(audio, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(8000)
        wav.writeframes(b'\x00\x00' * 800)
    boundary = 'wanfu-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="campus-test.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode() + audio.getvalue() + f'\r\n--{boundary}--\r\n'.encode())
    upload = urllib.request.Request(base + '/files', body, {'Authorization': 'Bearer ' + pa, 'X-Campus-Id': str(ca), 'Content-Type': 'multipart/form-data; boundary=' + boundary}, method='POST')
    with urllib.request.urlopen(upload, timeout=20) as response:
        file = json.load(response)
    policy = request('GET', '/privacy/policy', token=cross_parent)
    if policy.get('consentRequired', True):
        for sid in (sa, sb):
            for scope in ('SERVICE', 'MEDIA'):
                request('POST', '/privacy/consents', {'studentId': sid, 'scope': scope, 'policyVersion': policy['version'], 'granted': True}, cross_parent)
    request('POST', f'/homework/{homework[0]["id"]}/attachments', {'fileIds': [file['id']]}, pa)
    request('POST', f'/homework/{homework[1]["id"]}/attachments', {'fileIds': [file['id']]}, pb, expected=403)
    for token, status in ((pa, 200), (super_token, 200), (pb, 403)):
        download = urllib.request.Request(base + '/files/' + str(file['id']), headers={'Authorization': 'Bearer ' + token})
        try:
            with urllib.request.urlopen(download, timeout=10) as response:
                actual = response.status
                assert response.read(4) == b'RIFF'
        except urllib.error.HTTPError as error:
            actual = error.code
        assert actual == status, f'附件权限 expected {status}, got {actual}'
    request('PUT', f'/users/{principals[0][0]["id"]}/access', {'campusIds': [cb]}, super_token)
    download = urllib.request.Request(base + '/files/' + str(file['id']), headers={'Authorization': 'Bearer ' + pa})
    try:
        urllib.request.urlopen(download, timeout=10)
        raise AssertionError('原上传者撤销校区后仍可读取关联附件')
    except urllib.error.HTTPError as error:
        assert error.code == 403
    request('PUT', f'/users/{principals[0][0]["id"]}/access', {'campusIds': [ca]}, super_token)
    print('✓ 语音附件跨校区读取、挂接均拒绝，原上传人撤权后也无法绕过校区权限')
    print('多校区权限验收全部通过。独立数据前缀：校区验收-' + suffix)


if __name__ == '__main__':
    main()
