package chatapp.client.ui;

import chatapp.common.Message;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** 게임 판정은 서버가 수행한다. 이 화면은 참가자와 서버가 공개한 결과만 보관한다. */
final class RoomGameView extends JPanel {
    private final JLabel state = Theme.label("방 친구들과 가위바위보 한 판", 14, true);
    private final Consumer<String> onResultRevealed;
    private final GameStage stage = new GameStage();
    private final JPanel buttons = Theme.panel(new FlowLayout(FlowLayout.CENTER, 8, 0));
    private boolean revealing;
    private String selectedChoice;
    private final JTextArea detail = new JTextArea();
    private final JButton start = Theme.button("함께 시작", true);
    private final Map<String, JButton> choices = new LinkedHashMap<>();
    private final Set<String> participants = new LinkedHashSet<>();
    private final Set<String> chosen = new HashSet<>();
    private final Set<String> retired = new LinkedHashSet<>();
    private String gameId;
    private String self;
    private boolean ready;
    private int memberCount;

    RoomGameView(Runnable onStart, Consumer<String> onChoice, Consumer<String> onResultRevealed) {
        super(new BorderLayout(0, 8));
        this.onResultRevealed=onResultRevealed;
        setName("gamePanel"); setOpaque(false); setBorder(new EmptyBorder(6, 22, 10, 22));
        JPanel card = Theme.rounded(new BorderLayout(0, 8), new Color(0xFFF8DD), 20);
        card.setBorder(new EmptyBorder(12, 14, 12, 14));
        JPanel heading = Theme.panel(new BorderLayout());
        state.setName("gameStatus"); heading.add(state, BorderLayout.CENTER);
        JButton close = Theme.button("접기", false); close.setName("closeGame");
        close.setBorder(new EmptyBorder(4, 10, 4, 10)); close.addActionListener(e -> setVisible(false));
        heading.add(close, BorderLayout.EAST); card.add(heading, BorderLayout.NORTH);
        detail.setName("gameDetail"); detail.setEditable(false); detail.setOpaque(false);
        detail.setFont(new Font("SansSerif", Font.PLAIN, 12)); detail.setLineWrap(true); detail.setWrapStyleWord(true);
        detail.setRows(2); detail.setForeground(Theme.INK);
        JScrollPane scroll = new JScrollPane(detail); scroll.setBorder(null); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        JPanel body = Theme.panel(new BorderLayout(0, 4));
        body.add(stage, BorderLayout.CENTER); body.add(scroll, BorderLayout.SOUTH);
        card.add(body, BorderLayout.CENTER);
        start.setName("startGame"); start.addActionListener(e -> onStart.run()); buttons.add(start);
        for (String choice : List.of("SCISSORS", "ROCK", "PAPER")) {
            JButton button = Theme.button(choiceLabel(choice), false); button.setName("choose" + choice);
            button.setIcon(new GameHandIcon(choice, 46)); button.setDisabledIcon(new GameHandIcon(choice, 46));
            button.setHorizontalTextPosition(SwingConstants.CENTER); button.setVerticalTextPosition(SwingConstants.BOTTOM);
            button.setPreferredSize(new Dimension(92, 78));
            button.setIconTextGap(0); button.setBorder(new EmptyBorder(3, 20, 3, 20));
            button.getAccessibleContext().setAccessibleName(choiceLabel(choice) + " 선택");
            button.addActionListener(e -> {
                selectedChoice=choice; stage.selected(choice); refreshButtons(); onChoice.accept(choice);
            }); choices.put(choice, button); buttons.add(button);
        }
        card.add(buttons, BorderLayout.SOUTH); add(card, BorderLayout.CENTER); clearRoom();
    }

    static String choiceLabel(String choice) {
        return switch (choice) { case "ROCK" -> "바위"; case "PAPER" -> "보"; case "SCISSORS" -> "가위"; default -> "?"; };
    }
    String gameId() { return gameId; }
    boolean canChoose() { return gameId != null && participants.contains(self) && !chosen.contains(self); }
    void setReady(boolean ready, String self, int count) {
        this.ready = ready; this.self = self; memberCount = count; refreshButtons();
    }
    private void refreshButtons() {
        start.setEnabled(ready && gameId == null && memberCount >= 2 && !revealing);
        start.setVisible(gameId == null && !revealing);
        choices.forEach((choice, button) -> {
            button.setVisible(gameId != null && participants.contains(self));
            button.setEnabled(ready && canChoose());
            boolean picked=choice.equals(selectedChoice);
            button.setText(choiceLabel(choice) + (picked ? " ✓" : ""));
            Icon hand = new GameHandIcon(choice, picked ? 54 : 46);
            button.setIcon(hand); button.setDisabledIcon(hand);
            button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(picked ? new Color(0xD4AC36) : new Color(0xFFF8DD), 2, true),
                new EmptyBorder(1, 18, 1, 18)));
        });
        buttons.setVisible(!revealing);
        revalidate(); repaint();
    }
    void open() { setVisible(true); }
    @Override public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (stage != null) { if (visible) stage.resume(); else stage.pause(); }
        revalidate(); repaint();
    }
    void choiceRejected() {
        if (gameId != null && !chosen.contains(self)) { selectedChoice=null; stage.selected(null); refreshButtons(); }
    }
    void membersChanged(List<String> members) {
        participants.retainAll(members); chosen.retainAll(participants);
        if (gameId != null) progress();
    }
    private void progress() {
        state.setText("가위바위보 · " + chosen.size() + "/" + participants.size() + "명 선택 완료");
        detail.setText(participants.contains(self)
                ? (chosen.contains(self) ? "선택을 보냈어요. 친구들의 선택을 기다려요." : "가위·바위·보 중 하나를 골라주세요. 선택은 결과가 나올 때 공개돼요.")
                : "이번 판은 관전 중이에요. 다음 판부터 함께할 수 있어요.");
        refreshButtons();
    }
    boolean started(Message m) throws IOException {
        if (m.fields().size() < 3) throw new IOException("게임 시작 필드 누락");
        int count = count(m.field(2), m.fields().size() - 3);
        if (count < 2 || m.fields().size() != count + 3 || m.field(0).isEmpty()) throw new IOException("게임 참가자 수 오류");
        List<String> names = m.fields().subList(3, m.fields().size());
        if (new HashSet<>(names).size() != count || names.contains("") || !names.contains(m.field(1))) throw new IOException("게임 참가자 목록 오류");
        if (retired.contains(m.field(0)) || Objects.equals(gameId, m.field(0))) return false;
        if (gameId != null) throw new IOException("진행 중인 게임과 다른 시작 응답");
        revealing=false; selectedChoice=null; stage.waiting();
        gameId = m.field(0); participants.clear(); participants.addAll(names); chosen.clear();
        open(); progress(); return true;
    }
    boolean chosen(Message m) throws IOException {
        m.requireFields(2);
        if (!Objects.equals(gameId, m.field(0)) || !participants.contains(m.field(1))) return false;
        chosen.add(m.field(1)); progress(); return true;
    }
    String result(Message m) throws IOException {
        if (m.fields().size() < 3) throw new IOException("게임 결과 필드 누락");
        int n = count(m.field(1), (m.fields().size() - 3) / 2);
        if (n < 2) throw new IOException("게임 결과 참가자 수 오류");
        int winnerOffset = 2 + n * 2;
        int wins = count(m.field(winnerOffset), n);
        if (m.fields().size() != winnerOffset + 1 + wins) throw new IOException("게임 결과 필드 수 오류");
        Set<String> names = new LinkedHashSet<>(); List<String> moves = new ArrayList<>(); List<String> values = new ArrayList<>();
        for (int i = 2; i < winnerOffset; i += 2) {
            String name = m.field(i), choice = m.field(i + 1);
            if (name.isEmpty() || !names.add(name) || choiceLabel(choice).equals("?")) throw new IOException("게임 결과 선택값 오류");
            moves.add(name + " " + choiceLabel(choice)); values.add(choice);
        }
        List<String> winners = m.fields().subList(winnerOffset + 1, m.fields().size());
        if (!names.containsAll(winners) || new HashSet<>(winners).size() != wins) throw new IOException("게임 승자 목록 오류");
        if (retired.contains(m.field(0)) || (gameId != null && !gameId.equals(m.field(0)))) return null;
        String summary = wins == 0 ? "이번 판은 무승부예요" : "승리 · " + String.join(", ", winners);
        // 중도 입장 관전자에게는 GAME_STARTED 없이 GAME_RESULT가 올 수 있다.
        retire(m.field(0)); gameId = null; state.setText("가위, 바위, 보! 결과를 공개할게요"); detail.setText("서버에서 결과가 도착했어요.");
        chosen.clear(); participants.clear(); selectedChoice=null; revealing=true;
        stage.reveal(new ArrayList<>(names), values, winners, () -> {
            revealing=false; state.setText(summary); detail.setText(String.join(" / ", moves));
            refreshButtons(); onResultRevealed.accept(summary);
        });
        open(); refreshButtons(); return summary;
    }
    String canceled(Message m) throws IOException {
        m.requireFields(2);
        if (!List.of("TIMEOUT", "PLAYERS_LEFT").contains(m.field(1))) throw new IOException("게임 취소 사유 오류");
        if (retired.contains(m.field(0)) || (gameId != null && !gameId.equals(m.field(0)))) return null;
        String summary = m.field(1).equals("TIMEOUT") ? "선택한 사람이 부족해 게임이 취소됐어요" : "참가자가 부족해 게임이 취소됐어요";
        retire(m.field(0)); gameId = null; chosen.clear(); participants.clear();
        revealing=false; selectedChoice=null; stage.idle();
        state.setText("다음에 다시 함께해요"); detail.setText(summary); open(); refreshButtons(); return summary;
    }
    private static int count(String text, int max) throws IOException {
        try { int n = Integer.parseInt(text); if (n < 0 || n > max) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw new IOException("게임 인원 필드 오류", e); }
    }
    private void retire(String id) {
        retired.add(id); if (retired.size() > 300) retired.remove(retired.iterator().next());
    }
    void clearRoom() {
        if (gameId != null) retire(gameId);
        gameId = null; chosen.clear(); participants.clear(); revealing=false; selectedChoice=null; stage.idle();
        state.setText("방 친구들과 가위바위보 한 판"); detail.setText("시작하는 순간 방에 있는 모두가 참가해요. 두 명 이상 모이면 시작할 수 있어요.");
        setVisible(false); refreshButtons();
    }
    void reset() { clearRoom(); retired.clear(); }
}
