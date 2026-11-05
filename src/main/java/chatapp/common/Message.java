package chatapp.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 팀 합의 전인 v1 통신 초안. Java 객체 직렬화 없이 길이로 프레임을 구분한다. */
public record Message(String type, List<String> fields) {
    public static final int MAX_BYTES = 65_536;
    public Message {
        if (type == null || !type.matches("[A-Z_]{1,32}"))
            throw new IllegalArgumentException("잘못된 메시지 종류");
        fields = List.copyOf(fields);
    }
    public static Message of(String type, String... fields) {
        return new Message(type, List.of(fields));
    }
    public String field(int index) { return fields.get(index); }
    public void requireFields(int count) throws IOException {
        if (fields.size() != count) throw new IOException(type + " 필드 수가 올바르지 않습니다.");
    }
    public void write(DataOutputStream output) throws IOException {
        StringBuilder text = new StringBuilder(type);
        for (String field : fields)
            text.append('\t').append(Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_8)));
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("메시지가 너무 큽니다.");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }
    public static Message read(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 1 || size > MAX_BYTES) throw new IOException("잘못된 프레임 길이: " + size);
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\t", -1);
        try {
            List<String> fields = new ArrayList<>();
            for (int i = 1; i < parts.length; i++)
                fields.add(new String(Base64.getDecoder().decode(parts[i]), StandardCharsets.UTF_8));
            return new Message(parts[0], fields);
        } catch (IllegalArgumentException e) {
            throw new IOException("메시지를 해석할 수 없습니다.", e);
        }
    }
}
