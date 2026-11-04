#!/usr/bin/env python3
"""Run an isolated business flow against a local demo backend (creates marked test data)."""
import argparse
import concurrent.futures
import datetime
import json
import urllib.error
import urllib.request
import uuid
from zoneinfo import ZoneInfo


def main():
    parser = argparse.ArgumentParser(description='本地演示环境端到端验收，会创建独立的「集成验收」数据。')
    parser.add_argument('--base', required=True, help='显式指定独立验收服务，勿指向正式业务库')
    args = parser.parse_args()
    base = args.base.rstrip('/')
    campus_id = None

    def request(method, path, payload=None, token=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
            if campus_id is not None:
                headers['X-Campus-Id'] = str(campus_id)
        body = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(base + path, body, headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                raw, status = response.read(), response.status
        except urllib.error.HTTPError as error:
            raw, status = error.read(), error.code
        result = json.loads(raw) if raw else None
        allowed = expected if isinstance(expected, tuple) else (expected,)
        if status not in allowed:
            raise AssertionError(f'{method} {path}: 预期 {allowed}，实际 {status}，{result}')
        return result

    health = request('GET', '/health')
    if health.get('demo') is not True:
        raise SystemExit('仅允许在开启 demo 的本地环境创建验收数据。')
    admin = request('POST', '/auth/demo', {'role': 'ADMIN'})['token']
    check_in_place = request('GET', '/campuses', token=admin)[0]
    campus_id = check_in_place['id']
    assert check_in_place.get('checkInRadius'), '请先在隔离测试校区配置签到地点'
    teacher = request('POST', '/auth/demo', {'role': 'TEACHER'})['token']
    parent = request('POST', '/auth/demo', {'role': 'PARENT'})['token']
    suffix = uuid.uuid4().hex[:8]
    today = datetime.datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()
    reviewer = request('POST', '/auth/demo', {'role': 'CAMPUS_ADMIN'})['token']
    classroom = request('POST', '/classes', {'name': '集成验收-' + suffix, 'teacherName': '验收教师'}, admin)
    student = request('POST', '/students', {
        'name': '验收学生' + suffix, 'gender': '男', 'grade': '三年级',
        'classId': classroom['id'], 'parentName': '验收家长', 'parentPhone': '13800000000',
    }, admin)
    sid = student['id']
    request('GET', f'/students/{sid}', token=parent, expected=(403, 404))
    request('GET', f'/students/{sid}', token=teacher, expected=(403, 404))
    print('✓ 家长、教师不能越权访问独立班级学生')
    course = request('POST', '/courses', {
        'name': '验收课程-' + suffix, 'type': 'EVENING', 'unit': '次',
        'price': 5000, 'warningThreshold': 3,
    }, admin)
    enrollment = request('POST', '/enrollments', {'studentId': sid, 'courseId': course['id']}, admin)
    account_id = enrollment['accountId']
    request('POST', '/schedules', {
        'courseId': course['id'], 'classId': classroom['id'], 'date': today,
        'startTime': '15:30', 'endTime': '20:30', 'units': 1,
    }, admin)
    renewal_body = {
        'accountId': account_id, 'amount': 50000, 'lessons': 10,
        'giftLessons': 2, 'paymentMethod': 'CASH', 'note': '自动集成验收', 'requestId': str(uuid.uuid4()),
    }
    renewal = request('POST', '/renewals', renewal_body, admin)
    repeated = request('POST', '/renewals', renewal_body, admin)
    assert renewal['id'] == repeated['id'], '续费登记未幂等'
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda _: request('POST', f'/renewals/{renewal["id"]}/confirm', {}, admin), range(4)))

    def balance():
        accounts = request('GET', f'/accounts?studentId={sid}', token=admin)
        return float(next(a for a in accounts if a['id'] == account_id)['balance'])

    assert balance() == 12, '并发确认续费重复入账或赠送课时不正确'
    print('✓ 续费重复登记、并发确认仅入账一次，购买与赠送合计12次')
    for _ in range(2):
        result = request('POST', '/attendance/check-in', {'studentIds': [sid], 'date': today, 'campusId': campus_id, 'location': {'latitude': check_in_place['checkInLatitude'], 'longitude': check_in_place['checkInLongitude'], 'accuracy': 5, 'capturedAt': datetime.datetime.now().timestamp() * 1000}}, admin)
        assert all(r['success'] for r in result['results']), result
    guardian_id = request('GET', '/me', token=parent)['id']
    bound = request('GET', '/users', token=admin)
    guardian = next(u for u in bound if u['id'] == guardian_id)
    request('PUT', f'/users/{guardian_id}/access', {'studentIds': guardian['studentIds'] + [sid]}, admin)
    pickup = request('POST', '/pickups', {'studentId': sid, 'date': today, 'pickupName': '验收家长', 'relationship': '监护人', 'phone': '13800000000', 'requestId': str(uuid.uuid4())}, parent)
    request('POST', f'/pickups/{pickup["id"]}/verify', {'pickupName': '验收家长', 'phoneLast4': '0000', 'code': pickup['pickupCode'], 'confirmedIdentity': True, 'expectedVersion': pickup['version'], 'requestId': str(uuid.uuid4())}, admin)
    for _ in range(2):
        result = request('POST', '/attendance/check-out', {'studentIds': [sid], 'date': today}, admin)
        assert all(r['success'] for r in result['results']), result
    assert balance() == 11, '重复签退产生重复消课'
    ledger = request('GET', f'/lesson-ledger?accountId={account_id}', token=admin)
    consumption = [entry for entry in ledger if entry['type'] == 'CONSUMPTION']
    assert len(consumption) == 1, f'有效消课数量不正确: {consumption}'
    original_id = consumption[0]['id']
    request('POST', f'/lesson-ledger/{original_id}/reverse', {'reason': '集成验收冲正'}, admin)
    assert balance() == 12, '冲正未恢复课时'
    request('POST', f'/lesson-ledger/{original_id}/reverse', {'reason': '重复冲正'}, admin, expected=(200, 400, 409))
    assert balance() == 12, '重复冲正错误增加课时'
    print('✓ 重复签到、签退不重复消课；冲正可追溯且只生效一次')
    task = request('POST', '/homework/tasks', {
        'date': today, 'subject': '数学', 'type': 'WRITTEN', 'title': '集成验收应用题',
        'description': '验收业务状态与积分', 'studentIds': [sid],
    }, admin)
    homework = next(h for h in request('GET', f'/homework?studentId={sid}', token=admin) if h['taskId'] == task['taskId'])
    hid = homework['id']
    request('POST', f'/homework/{hid}/status', {'status': 'IN_PROGRESS'}, admin)
    request('POST', f'/homework/{hid}/status', {'status': 'PENDING_REVIEW'}, admin)
    request('POST', f'/homework/{hid}/review', {'status': 'CORRECTION', 'accuracy': 80, 'comment': '请订正第2题'}, admin)
    request('POST', f'/homework/{hid}/status', {'status': 'PENDING_RECHECK', 'submissionText': '验收：已按老师要求订正'}, admin)
    request('POST', f'/homework/{hid}/review', {'status': 'COMPLETED', 'accuracy': 100, 'comment': '订正通过'}, admin)
    request('POST', f'/homework/{hid}/review', {'status': 'COMPLETED', 'accuracy': 100, 'comment': '重复确认'}, admin, expected=(200, 400, 409))
    points = request('GET', f'/points?studentId={sid}', token=admin)
    assert len(points) == 1 and points[0]['amount'] >= 0, '完成作业重复发积分'
    print('✓ 作业开始、提交、需订正、待复核、完成闭环与奖励防重')
    mistake = request('POST', '/mistakes', {
        'studentId': sid, 'subject': '数学', 'title': '验收错题', 'knowledgePoint': '应用题',
        'errorReason': '审题不仔细', 'homeworkId': hid,
    }, admin)
    mid = mistake['id']
    request('POST', f'/mistakes/{mid}/correct', {'text': '已重新审题并完成订正'}, admin)
    reviewed = request('POST', f'/mistakes/{mid}/review', {'approved': True, 'comment': '复核通过'}, admin)
    assert reviewed['status'] == 'MASTERED'
    assert len(reviewed['reviews']) == 3, '未按1/3/7天建立复习任务'
    request('POST', '/fitness/batch', {'date': today, 'studentIds': [sid], 'project': '跳绳', 'count': 100, 'duration': 60}, admin)
    before = len(request('GET', f'/points?studentId={sid}', token=admin))
    request('POST', '/fitness/batch', {'date': today, 'studentIds': [sid], 'project': '跳绳', 'count': 100, 'duration': 60}, admin)
    assert len(request('GET', f'/points?studentId={sid}', token=admin)) == before
    print('✓ 错题订正复核、1/3/7天复习、体能积分防重')
    request('POST', '/summaries/generate', {'date': today, 'studentIds': [sid]}, admin)
    summary = request('GET', f'/summaries?studentId={sid}&date={today}', token=admin)[0]
    assert summary['status'] == 'DRAFT'
    edited = request('PATCH', f'/summaries/{summary["id"]}', {'teacherComment': '今天表现积极，已完成订正。'}, admin)
    submitted = request('POST', f'/summaries/{summary["id"]}/submit-review', {'expectedVersion': edited['version']}, admin)
    request('POST', f'/summaries/{summary["id"]}/review', {'expectedVersion': submitted['version'], 'approved': True, 'comment': '独立核对通过'}, reviewer)
    published = request('POST', f'/summaries/{summary["id"]}/publish', {}, admin)
    assert published['status'] == 'PUBLISHED' and published['version'] >= 1
    assert published['notificationStatus'] != 'SENT', '未配置微信不应声称已发送'
    print('✓ 日小结生成、教师评语、发布快照与真实通知状态')
    print(f'全部验收通过。验收数据：学生 {sid}，班级 {classroom["name"]}。')


if __name__ == '__main__':
    main()
