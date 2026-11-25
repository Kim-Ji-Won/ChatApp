package chatapp.client.network;

import chatapp.common.Message;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 데모 연결 하나에서 방별 원문과 성공한 답장 요청을 보관한다. */
final class DemoReplies {
    private record Saved(String sender, String text, boolean reply) {}
    private record Success(Message request, Message response) {}

    private final Map<String, LinkedHashMap<String, Saved>> rooms = new HashMap<>();
    private final LinkedHashMap<String, Success> successes = new LinkedHashMap<>();

    Message chat(String room, String sender, String text) {
        String id = remember(room, new Saved(sender, text, false));
        return Message.of("CHAT_MESSAGE", room, sender, text, id);
    }

    Message reply(Message request, String room, String sender) {
        String requestId = request.fields().isEmpty() ? "" : request.field(0);
        if (request.fields().size() != 4 || !validId(requestId))
            return error("INVALID_REPLY", "답장 요청 형식을 확인해 주세요.", requestId);
        if (room == null || !room.equals(request.field(1)))
            return error("NOT_IN_ROOM", "현재 방을 확인해 주세요.", requestId);
        Success previous = successes.get(requestId);
        if (previous != null) {
            if (request.equals(previous.request())) return previous.response();
            return error("INVALID_REPLY", "이미 사용한 요청 번호예요.", requestId);
        }
        String targetId = request.field(2);
        if (!validId(targetId)) return error("INVALID_REPLY", "원문 번호를 확인해 주세요.", requestId);
        String body = request.field(3).strip();
        if (body.isEmpty() || body.length() > 1000 || body.chars().anyMatch(Character::isISOControl))
            return error("INVALID_MESSAGE", "답장은 제어문자 없이 1~1000자로 입력해 주세요.", requestId);
        var history = rooms.get(room);
        Saved source = history == null ? null : history.get(targetId);
        if (source == null) return error("REPLY_TARGET_NOT_FOUND", "원문을 찾을 수 없어요.", requestId);
        if (source.reply()) return error("INVALID_REPLY_TARGET", "일반 메시지에만 답장할 수 있어요.", requestId);
        String id = remember(room, new Saved(sender, body, true));
        Message response = Message.of("CHAT_REPLY_MESSAGE", room, requestId, id, sender, body,
                targetId, source.sender(), source.text());
        successes.put(requestId, new Success(request, response));
        trim(successes);
        return response;
    }

    private String remember(String room, Saved message) {
        var history = rooms.computeIfAbsent(room, ignored -> new LinkedHashMap<>());
        String id = UUID.randomUUID().toString();
        history.put(id, message);
        trim(history);
        return id;
    }

    private static boolean validId(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static void trim(LinkedHashMap<String, ?> entries) {
        if (entries.size() > 200) entries.remove(entries.keySet().iterator().next());
    }

    private static Message error(String code, String explanation, String id) {
        return Message.of("ERROR", code, explanation, id);
    }
}
