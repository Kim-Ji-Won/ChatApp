# 3주차 백엔드: 할 일·일정

통신 규칙 기준: `feature/frontend-week3`(PR #9)의 `docs/protocol-week3.md`(2026-11-19 공동 확정).
이 PR은 아직 머지되지 않았지만, 그 문서를 그대로 구현 기준으로 삼았다. 필드 순서·오류 코드·
권한 규칙은 전부 그 문서와 동일하다.

## 범위

채팅 메시지에서 만드는 할 일(TODO_*)과 방별 일정(SCHEDULE_*). 서버가 상태를 확정하고, 클라이언트는
요청마다 `requestId`(UUID)를 붙인다.

## 주요 설계 결정

- **빈 방을 더 이상 삭제하지 않는다.** `Hub.removeFromRoom`에서 `room.members.isEmpty()`일 때
  방을 지우던 코드를 제거했다. 그 방의 할 일·일정을 보존하기 위한 정책 변경이며, 로비 목록에는
  인원 0명인 방이 계속 남는다. 기존 1주차 테스트 중 "접속 종료 반영(빈 방 삭제)"를 이 정책에 맞춰
  "빈 방 유지, 인원 0"으로 바꿨다.
- **todoId/scheduleId는 현재 방 안에서만 찾는다.** `Room`에 `Map<String, Todo> todos`와
  `Map<String, Schedule> schedules`를 각각 두어, 다른 방의 ID로는 애초에 조회·조작이 불가능하다.
- **참가자를 닉네임이 아니라 객체로 식별하는 2주차 패턴을 여기서도 적용**했다. 담당자는 저장은
  닉네임 문자열이지만(퇴장해도 유지해야 하므로), 신규 지정 시에는 그 순간 `room.members`에 있는
  세션들의 닉네임과 비교해 검증한다.
- **requestId 중복 처리**: `Session`에 `Map<String, Hub.RequestRecord> plannerRequests`를 두어
  연결이 살아있는 동안의 성공 요청·응답을 기억한다(연결이 끊기면 Session과 함께 사라짐). 같은
  requestId·같은 내용이 다시 오면 재실행 없이 저장해 둔 응답을 재전송하되, 그 사이 다른 방으로
  옮겼으면 FORBIDDEN으로 거부한다. 같은 requestId·다른 내용이면 최초 기록은 유지한 채
  INVALID_TODO/INVALID_SCHEDULE로 거부한다.
- **CONFLICT에 최신 내용을 따로 싣지 않는다.** `Hub`가 모든 요청을 락 하나로 직렬화하므로, 선행
  변경의 방 전체 브로드캐스트가 항상 이 CONFLICT 오류보다 먼저 요청자의 전송 큐에 들어가 있다.
- **줄바꿈 허용 여부가 다른 두 검증 함수**를 뒀다: 제목은 기존 `clean()`(한 줄, 제어문자 금지)을
  그대로 쓰고, 메모·원문 사본은 새로 추가한 `cleanMultiline()`으로 `\n`만 허용한다.

## 메시지 처리 요약

- `TODO_LIST(roomId)` / `SCHEDULE_LIST(roomId, date)`: 요청자에게만 START→ITEM×N→END. 목록 중간에
  다른 변경 알림이 끼어들 수 없다(synchronized 메서드 하나로 스냅샷을 만들고 그대로 전송하므로).
- `TODO_CREATE`/`SCHEDULE_CREATE`: 입력 검증 후 revision=1로 생성, 요청자 포함 방 전체에
  `*_CREATED(roomId, requestId, 레코드)`.
- `TODO_EDIT`/`SCHEDULE_EDIT`: 작성자만, expectedRevision 일치 시에만, 검증 통과 시 revision++
  하고 `*_EDITED`를 방 전체에.
- `TODO_COMPLETE`: 작성자 또는 담당자만, expectedRevision 일치 시 `TODO_COMPLETED`를 방 전체에.
- `TODO_DELETE`/`SCHEDULE_DELETE`: 작성자만, expectedRevision 일치 시 삭제하고 `*_DELETED`를 방
  전체에.
- 오류는 전부 `ERROR(code, description, requestId)`(목록 조회 오류만 `ERROR(code, description)`).
  requestId가 비어 있으면 세 번째 필드도 빈 문자열로 돌려준다.

## 테스트

`src/test/java/chatapp/ServerChecks.java`에 44건을 추가해 `sh test-server.sh` 기준 총 304건이다.

- `todoBasicChecks`: 생성·조회·수정·완료·삭제 정상 흐름과 필드 순서, 방 전체 전달(보낸 사람 포함).
- `todoPermissionAndValidationChecks`: 빈 제목/없는 담당자/날짜 없이 시간만/잘못된 날짜/빈 원문
  거부, 작성자가 아닌 사람의 수정·삭제·완료 처리 거부.
- `todoConflictAndDedupChecks`: 오래된 revision CONFLICT, 같은 requestId·같은 내용 재전송(재실행
  없이 응답만 재전송), 같은 requestId·다른 내용 거부, requestId 누락 시 세 번째 필드 빈 문자열.
- `scheduleChecks`: 생성·조회(날짜 필터·전체)·수정·삭제, 메모 줄바꿈 보존, 입력 검증, 권한.
- `emptyRoomPersistsChecks`: 모두 퇴장해도 방이 삭제되지 않음, 담당자가 퇴장해도 닉네임 유지,
  담당자를 바꾸지 않는 수정은 계속 허용, 새로 지정하려는 담당자가 현재 방에 없으면 거부.

기존 1주차·2주차 검사(260건)도 그대로 통과한다. `sh test.sh`(프론트 2주차 기준, 175건)도 영향 없음.

## 아직 안 한 것

- `feature/frontend-week3`(PR #9)가 아직 머지되지 않아, 실제 프론트와의 통합 확인은 하지 않았다.
  머지 후 2주차 때처럼 실제 서버+실제 클라이언트로 헤드리스 통합 테스트를 한 번 더 할 계획이다.
- 사용자가 직접 방을 지우는 기능, 할 일·일정의 DB 저장은 이번 주 범위 밖(노션 합의대로).
