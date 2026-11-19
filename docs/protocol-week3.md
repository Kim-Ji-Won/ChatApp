# 3주차 할 일·일정 통신 규칙

기준: 2026-11-19 김지원이 공동 확정했다고 전달한 [노션](https://app.notion.com/p/ChatApp-3-41c8c3c90e8c80f49b71ede662660e1a).
노션 위쪽 표보다 5-2에 추가된 정윤님의 답변을 우선한다. **변경 알림 7종은 roomId, requestId 순서로 시작한다.** 목록 ITEM에는 roomId를 추가하지 않는다. 기존 Message 프레임은 변경하지 않는다.

## 요청

| 종류 | 필드 순서 |
| --- | --- |
| TODO_CREATE | requestId, roomId, title, sourceSender, sourceText, assignee, dueDate, dueTime |
| TODO_EDIT | requestId, todoId, expectedRevision, title, assignee, dueDate, dueTime |
| TODO_COMPLETE | requestId, todoId, expectedRevision, done (`1`/`0`) |
| TODO_DELETE | requestId, todoId, expectedRevision |
| TODO_LIST | roomId |
| SCHEDULE_CREATE | requestId, roomId, date, title, time, memo |
| SCHEDULE_EDIT | requestId, scheduleId, expectedRevision, date, title, time, memo |
| SCHEDULE_DELETE | requestId, scheduleId, expectedRevision |
| SCHEDULE_LIST | roomId, date (빈 문자열이면 전체) |

## 응답

TODO 레코드 T = `todoId, revision, title, author, assignee, dueDate, dueTime, done, sourceSender, sourceText` (10필드).

SCHEDULE 레코드 S = `scheduleId, revision, date, title, time, memo, author` (7필드).

| 종류 | 필드 순서 | 대상 |
| --- | --- | --- |
| TODO_LIST_START | roomId, count | 요청자 |
| TODO_ITEM | T | 요청자 |
| TODO_LIST_END | roomId | 요청자 |
| TODO_CREATED / TODO_EDITED | roomId, requestId, T | 요청자 포함 현재 방 전체 |
| TODO_COMPLETED | roomId, requestId, todoId, revision, done | 요청자 포함 현재 방 전체 |
| TODO_DELETED | roomId, requestId, todoId | 요청자 포함 현재 방 전체 |
| SCHEDULE_LIST_START | roomId, count | 요청자 |
| SCHEDULE_ITEM | S | 요청자 |
| SCHEDULE_LIST_END | roomId | 요청자 |
| SCHEDULE_CREATED / SCHEDULE_EDITED | roomId, requestId, S | 요청자 포함 현재 방 전체 |
| SCHEDULE_DELETED | roomId, requestId, scheduleId | 요청자 포함 현재 방 전체 |
| ERROR (기존 기능·목록 조회) | code, description | 요청자 |
| ERROR (신규 변경 요청) | code, description, requestId | 요청자 |

오류: `NOT_IN_ROOM`, `INVALID_TODO`, `TODO_NOT_FOUND`, `INVALID_SCHEDULE`, `SCHEDULE_NOT_FOUND`, `FORBIDDEN`, `CONFLICT`.
신규 변경 요청에 requestId 자체가 누락되면 오류의 세 번째 필드는 빈 문자열이다.

## 처리·권한

- 수정·삭제는 작성자만, 할 일 완료/취소는 작성자 또는 담당자만 수행한다. 서버가 현재 연결의 닉네임·방으로 최종 판단한다. 인증 계정이 아닌 닉네임 식별이므로 재접속 후 동일 닉네임을 다른 사람이 사용하는 제한은 이번 주에도 남는다.
- 일반 대화 원문의 발신자·본문을 사본으로 저장한다. 수정할 때 원문 필드는 보내지 않는다. 귓속말·시스템 안내·스티커를 공유 할 일로 만들지 않는다. 서버 원본과 사본의 일치 증명은 범위 밖이다.
- 프론트는 변경마다 UUID requestId, 현재 알고 있는 expectedRevision을 전송한다. 새 요청이나 충돌 후 재저장은 새 UUID를 쓴다. revision은 서버가 발급·증가한다.
- 한 연결의 화면 요청을 하나씩 처리한다. 목록은 START→ITEM*→END 사이에 관련 변경 알림이 끼어들지 않게 서버가 전송한다. 프론트는 개수·ID 중복·방을 검사하고 END 후 목록을 바꾼다. 빠른 날짜 검색은 마지막 조건을 보관했다가 순차 조회한다.
- 서버는 선행 변경 알림→CONFLICT 오류의 연결별 순서를 보장한다. 프론트는 오류 후 재조회와 사용자의 최신 내용 확인을 요구하며 수정 입력을 유지한다. 최신 revision만 바꿔 자동 재저장하지 않는다.
- 변경 알림은 roomId를 확인하고, 낮은/동일 revision을 무시한다. 삭제 ID는 현재 방 체류 중 기록하여 과거 성공 응답으로 되살아나지 않게 한다. ROOM_LEFT에서 목록·수집 상태를 초기화한다. ITEM에는 방 ID가 없으므로 퇴장 후 이전 ITEM을 보내지 않는 서버 순서도 필요하다.
- 서버는 연결 동안 성공 requestId·요청·응답·roomId를 기억한다. 같은 ID/내용 재전송은 현재 방 권한을 확인한 뒤 요청자에게만 기존 응답을 보낸다. 다른 내용이면 INVALID_TODO/INVALID_SCHEDULE. 재접속 후 자동 생성 재시도는 하지 않는다.

## 입력·저장

- 제목: 앞뒤 공백 제거 후 1~80 UTF-16 코드 단위, 한 줄, 제어문자 금지.
- 메모: 0~500, 원문 사본: 1~1000. LF 줄바꿈은 허용하고 다른 제어문자는 거부한다. 프론트의 다중행 입력은 LF를 사용한다.
- YYYY-MM-DD의 실제 날짜, 한국 시간 기준 24시간제 HH:mm. 과거 날짜 허용.
- 할 일 담당자·마감일·시간은 선택이다. 날짜 없이 시간만 지정할 수 없다. 일정 날짜는 필수, 시간·메모는 선택이다.
- 새 담당자는 현재 방 참가자에서 선택한다. 기존 담당자가 퇴장해도 닉네임을 보존하고, 담당자 변경 없는 제목·마감일 수정은 허용한다.
- 빈 방과 방의 할 일·일정은 유지한다. 메모리 저장이므로 **서버 재시작 시 초기화**한다. 명시적 방 삭제와 DB 저장은 이번 주 범위 밖이다.

이 문서는 통신 계약을 기록한다. 현재 main의 2주차 서버가 3주차 메시지와 빈 방 유지까지 이미 구현했다는 뜻은 아니다.
