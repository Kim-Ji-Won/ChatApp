package chatapp.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 일반 채팅 입력과 분리해 귓속말을 실수로 전체 방에 보내지 않도록 한다. */
final class WhisperComposer extends JPanel {
    private final JComboBox<String> target = new JComboBox<>();
    private final JTextField input = Theme.field("whisperInput", "");
    private final JButton send = Theme.button("귓속말 보내기", true);
    private final JButton close = Theme.button("닫기", false);

    WhisperComposer(BiConsumer<String, String> submit) {
        super(new BorderLayout(8, 8));
        setName("whisperComposer");
        setBackground(new Color(0xF3EEFA));
        setBorder(new EmptyBorder(10, 12, 10, 12));
        target.setName("whisperTarget"); target.setFont(new Font("SansSerif", Font.PLAIN, 13));
        target.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.putClientProperty("html.disable", Boolean.TRUE);
                return label;
            }
        });
        JPanel heading = Theme.panel(new BorderLayout(8, 0));
        heading.add(Theme.label("귓속말 · 선택한 상대에게만 전달돼요", 12, true), BorderLayout.CENTER);
        close.setName("closeWhisper"); heading.add(close, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);
        JPanel fields = Theme.panel(new BorderLayout(8, 0));
        fields.add(target, BorderLayout.WEST);
        input.putClientProperty("placeholder", "따로 전할 이야기를 적어주세요");
        fields.add(input, BorderLayout.CENTER);
        send.setName("sendWhisper"); fields.add(send, BorderLayout.EAST);
        add(fields, BorderLayout.CENTER);
        send.addActionListener(event -> submit.accept((String) target.getSelectedItem(), input.getText().strip()));
        input.addActionListener(event -> { if (send.isEnabled()) send.doClick(); });
        close.addActionListener(event -> setVisible(false));
        setVisible(false);
    }

    void updateTargets(List<String> members, String self) {
        String selected = (String) target.getSelectedItem();
        target.removeAllItems();
        for (String member : members) if (!member.equals(self)) target.addItem(member);
        // 상대가 퇴장해도 다른 상대를 자동으로 선택하지 않는다.
        target.setSelectedItem(null);
        if (selected != null && members.contains(selected) && !selected.equals(self)) target.setSelectedItem(selected);
    }

    void setReady(boolean ready) {
        send.setEnabled(ready && target.getItemCount() > 0);
        target.setEnabled(ready);
    }

    void clear() { target.removeAllItems(); input.setText(""); setVisible(false); }
    void open() { setVisible(true); input.requestFocusInWindow(); }
    void acknowledge(String recipient, String body) {
        if (Objects.equals(target.getSelectedItem(), recipient) && input.getText().strip().equals(body)) input.setText("");
    }
}
