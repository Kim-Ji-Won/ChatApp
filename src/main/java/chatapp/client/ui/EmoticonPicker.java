package chatapp.client.ui;

import chatapp.common.Message;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** 서버가 보유를 확인한 항목만 전송한다. ID 목록은 팀원 노션 E01~E08 제안을 따른다. */
final class EmoticonPicker extends JPanel {
    private static final List<String> IDS = List.of("E01","E02","E03","E04","E05","E06","E07","E08");
    private static final List<String> LABELS = List.of("미소", "좋아요", "축하해요!", "고마워요", "힘내요!", "반가워요", "최고예요!", "잘했어요!");
    private final Set<String> owned = new HashSet<>();
    private final Map<String, JButton> buttons = new LinkedHashMap<>();
    private final JLabel summary = Theme.label("보유 목록을 불러와 주세요", 12, true);
    private final JButton refresh = Theme.button("목록 새로고침", false);
    private boolean ready;
    private boolean loaded;
    EmoticonPicker(Runnable onRefresh, Consumer<String> onSend) {
        super(new BorderLayout(0, 8)); setName("emoticonPicker"); setOpaque(false);
        setBorder(new EmptyBorder(8, 0, 8, 0));
        JPanel heading = Theme.panel(new BorderLayout()); summary.setName("emoticonSummary"); heading.add(summary, BorderLayout.CENTER);
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        refresh.setName("refreshEmoticons"); refresh.addActionListener(e -> onRefresh.run()); actions.add(refresh);
        JButton close = Theme.button("닫기", false); close.setName("closeEmoticons"); close.addActionListener(e -> setVisible(false)); actions.add(close);
        heading.add(actions, BorderLayout.EAST); add(heading, BorderLayout.NORTH);
        JPanel grid = Theme.panel(new GridLayout(2, 4, 5, 5));
        for (String id : IDS) {
            JButton b = Theme.button(label(id), false); b.setName("emoticon" + id);
            b.setIcon(new EmoticonIcon(id, 52));
            b.setDisabledIcon(new EmoticonIcon(id, 52, true));
            b.setHorizontalTextPosition(SwingConstants.CENTER); b.setVerticalTextPosition(SwingConstants.BOTTOM);
            b.setIconTextGap(1); b.setBorder(new EmptyBorder(4, 3, 4, 3));
            b.getAccessibleContext().setAccessibleName(label(id) + " 이모티콘"); b.setFont(new Font("SansSerif", Font.PLAIN, 12));
            b.addActionListener(e -> onSend.accept(id)); buttons.put(id,b); grid.add(b);
        }
        add(grid, BorderLayout.CENTER); reset();
    }
    static String label(String id) { int i=IDS.indexOf(id); return i<0 ? "알 수 없는 이모티콘" : LABELS.get(i); }
    static void validate(String id) throws IOException { if (!IDS.contains(id)) throw new IOException("알 수 없는 이모티콘 ID"); }
    boolean owns(String id) { return owned.contains(id); }
    void open() { setVisible(true); }
    void setReady(boolean ready) { this.ready=ready; update(); }
    void owned(Message m) throws IOException {
        Set<String> next = new HashSet<>();
        for (String id : m.fields()) { validate(id); if (!next.add(id)) throw new IOException("중복된 보유 이모티콘 ID"); }
        owned.clear(); owned.addAll(next); loaded=true; update();
    }
    void granted(String id) throws IOException { validate(id); owned.add(id); update(); }
    private void update() {
        summary.setText(!loaded ? "보유 목록을 불러와 주세요" : owned.isEmpty() ? "게임에서 승리해 이모티콘을 모아보세요" : "내 이모티콘 · " + owned.size() + "개");
        refresh.setEnabled(ready);
        buttons.forEach((id,b) -> {
            boolean unlocked=owned.contains(id);
            b.setEnabled(ready && unlocked); b.setDisabledIcon(new EmoticonIcon(id,52,!unlocked));
            b.setToolTipText(unlocked ? label(id) + " 보내기" : label(id) + " · 게임 승리 보상으로 받을 수 있어요");
            b.getAccessibleContext().setAccessibleDescription(unlocked ? "보유한 이모티콘" : "미보유, 게임 승리 보상으로 획득");
        });
    }
    void reset() { owned.clear(); loaded=false; ready=false; setVisible(false); update(); }
}
