# 4주차 백엔드: 메시지 답장

원래 계획상 4주차(검증·제출) 작업이었으나, 5주 일정으로 조정되며 이번 주에 당겨서 진행했다.
통신 규칙은 지원님이 제안한 안을 그대로 구현 기준으로 삼았다(이슈 #16).

## 범위

같은 방의 일반 텍스트 메시지에만 답장할 수 있다. 귓속말·시스템·이모티콘·답장에 또 답장하는 것은
제외한다. 서버가 일반 메시지·답장 각각에 UUID로 messageId를 발급한다.

## 주요 설계 결정

- **CHAT_MESSAGE가 4필드로 바뀐다.** 기존 `CHAT_MESSAGE(roomId, sender, text)`에 messageId가
  추가되어 `CHAT_MESSAGE(roomId, sender, text, messageId)`가 된다. 이 변경 때문에 프론트와
  동시에 반영해야 통합이 된다(프론트는 `feature/frontend-week4`, PR #15에 이미 반영됨).
- **원문은 서버가 보관한 사본에서 채운다.** 클라이언트는 `replyToMessageId`만 보내고, 원문
  작성자·내용은 클라이언트가 보내지 않는다 — 서버가 `Room.recentMessages`에서 조회해 응답에
  채운다. 클라이언트가 원문 내용을 조작해 보낼 수 없다.
- **방별 "일반 채팅 + 답장" 합계 최근 200개를 `Room.recentMessages`에 보관한다.** `Todo`/
  `Schedule`과 같은 자리(Room 안)에 두어, 빈 방에서도 유지되고(3주차 "빈 방 안 지우기" 정책과
  동일) 서버 재시작 시 초기화된다. 200개를 넘으면 가장 오래전에 넣은 것부터 지운다
  (`LinkedHashMap`의 삽입 순서 이용).
- **답장 대상 검증은 "존재 여부"와 "종류"를 따로 구분한다.** 원문 ID가 보관 목록에 없으면
  `REPLY_TARGET_NOT_FOUND`(200개 한도에 밀려났거나 애초에 없던 ID), 있지만 그 자체가 답장이면
  `INVALID_REPLY_TARGET`이다. 귓속말·시스템·이모티콘 메시지는애초에 `recentMessages`에 들어가지
  않으므로(메시지 자체에 messageId가 없으므로) 클라이언트가 그 ID를 알 수 없고, 자연히
  `REPLY_TARGET_NOT_FOUND`로만 걸러진다.
- **requestId·replyToMessageId는 표준 UUID 형식까지 검사한다.** 기존 할 일·일정의 requestId는
  "비어 있지 않은 문자열"만 확인했지만, 이번 제안은 "UUID 오류"를 `INVALID_REPLY`에 명시적으로
  포함했으므로 `UUID.fromString(...).toString().equalsIgnoreCase(value)`로 표준 형식까지 확인한다.
- **연결별 답장 요청 중복 처리 캐시를 할 일·일정과 분리하고, 크기를 200개로 제한한다.**
  `Session.plannerRequests`(무제한)와 별개로 `Session.replyRequests`(`LinkedHashMap`, 최근
  200개)를 둔다. 답장은 일반 채팅만큼 빈번할 수 있어 무제한으로 두면 긴 접속에서 메모리가 계속
  늘어난다. 재전송 시 현재 방을 확인하는 로직은 할 일·일정과 같지만, 이 기능의 오류 코드 목록에
  `FORBIDDEN`이 없으므로 방이 바뀐 경우도 `NOT_IN_ROOM`으로 응답한다.
- **답장 본문 검증은 기존 일반 채팅과 동일한 `clean()`을 그대로 쓴다.** 한 줄만 허용(제어문자·줄바꿈
  불가), 1~1000자. 별도 규칙을 만들지 않았다.

## 메시지 처리 요약

- `CHAT_SEND(text)`: 기존과 동일하되, 서버가 UUID로 messageId를 발급해 `Room.recentMessages`에
  저장한 뒤 `CHAT_MESSAGE(roomId, sender, text, messageId)`를 방 전체(보낸 사람 포함)에 전달한다.
- `CHAT_REPLY_SEND(requestId, roomId, replyToMessageId, text)`: 필드 수(4)·requestId UUID 형식
  확인 → requestId 중복 처리 → 현재 방 확인 → replyToMessageId UUID 형식 확인 → 본문 검증 →
  원문 조회(존재·종류) → 성공 시 답장도 UUID messageId를 발급해 `Room.recentMessages`에 저장하고
  `CHAT_REPLY_MESSAGE(roomId, requestId, messageId, sender, text, replyToMessageId, replyToSender,
  replyToText)`를 방 전체(보낸 사람 포함)에 전달한다.
- 오류는 `ERROR(code, description, requestId)`. requestId가 비어 있으면 세 번째 필드도 빈
  문자열로 돌려준다.

## 테스트

`src/test/java/chatapp/ServerChecks.java`에 `chatReplyChecks`를 추가해 18건, 기존 325건과
합쳐 `sh test-server.sh` 기준 총 343건이다. 필드 개수·UUID 형식 오류, 방 불일치, 본문 검증,
원문 없음, 성공 시 필드 순서와 양쪽 수신, 답장에 또 답장 거부, requestId 중복 재전송과 다른
내용 거부, requestId 누락 시 빈 문자열 응답, 빈 방을 거쳐도 원문이 유지되는지까지 확인한다.
`sh test.sh`(프론트 기준, 211건)도 영향 없음을 확인했다.

## 아직 안 한 것

- `feature/frontend-week4`(PR #15)가 아직 머지되지 않아, 실제 프론트와의 통합 확인은 하지
  않았다. 머지 후 실제 서버+실제 클라이언트로 헤드리스 통합 테스트를 한 번 더 할 계획이다.
- 재접속 후 자동 재전송, 과거 대화 조회는 이번 범위 밖(합의대로).
