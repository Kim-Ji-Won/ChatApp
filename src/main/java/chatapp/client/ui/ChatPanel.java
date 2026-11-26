package chatapp.client.ui;

import chatapp.client.network.*;
import chatapp.common.Message;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** 모든 화면 상태 변경은 EDT에서 수행한다. 통신은 transport와 작업 스레드에 위임한다. */
public final class ChatPanel extends JPanel implements AutoCloseable {
    private record Room(String id, String name, int count) { @Override public String toString() { return name; } }
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = Theme.panel(cards);
    private final JPanel membersPanel = new JPanel(new BorderLayout(0, 18));
    private final JButton membersToggle = namedButton("membersToggle", "멤버 0명 ▾", false);
    private final JButton whisper = namedButton("whisper", "귓속말", false);
    private final WhisperComposer whisperComposer = new WhisperComposer(this::sendWhisper);
    private final JPanel replyPreview = Theme.panel(new BorderLayout(10, 0));
    private final JLabel replyAuthor = Theme.label("", 12, true);
    private final JLabel replyExcerpt = Theme.label("", 12, false);
    private final JButton cancelReply = namedButton("cancelReply", "×", false);
    private ConversationView.Quote replyTarget;
    private String pendingReplyId;
    private String pendingReplyText;
    private String pendingWhisperTarget;
    private String pendingWhisperBody;
    private final JButton toolsToggle = namedButton("toolsToggle", "+", false);
    private final JPanel composerTools = Theme.panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JButton game = namedButton("game", "가위바위보", false);
    private final JButton emoticons = namedButton("emoticons", "", false);
    private final RoomGameView gameView = new RoomGameView(this::startGame, this::chooseGame, this::gameResultRevealed);
    private final EmoticonPicker emoticonPicker = new EmoticonPicker(this::loadEmoticons, this::sendEmoticon);
    private final Set<String> rewardedGames = new LinkedHashSet<>();
    private String pendingGame;
    private String pendingEmoticon;
    private Message afterLeave;
    private final JTextField host = Theme.field("host", "127.0.0.1");
    private final JTextField port = Theme.field("port", "5000");
    private final JTextField nickname = Theme.field("nickname", "");
    private final JTextField roomSearch = Theme.field("roomSearch", "");
    private final JButton newConversation = namedButton("newConversation", "+ 새 대화", true);
    private final JPanel createRoomPanel = Theme.panel(new BorderLayout(0, 10));
    private final List<Room> allRooms = new ArrayList<>();
    private final Map<String, String> recentMessages = new HashMap<>();
    private final Map<String, String> recentTimes = new HashMap<>();
    private final Map<String, Long> recentActivity = new HashMap<>();
    private final Map<String, String> drafts = new HashMap<>();
    private long activitySequence;
    private final JButton newMessages = namedButton("newMessages", "새 메시지 ↓", true);
    private final JLabel roomCount = Theme.label("대화 목록", 11, false);
    private final JLabel emptyRooms = Theme.label("아직 대화가 없어요. 새 대화를 시작해 보세요.", 12, false);
    private final JTextField roomName = Theme.field("roomName", "");
    private final JTextField messageInput = Theme.field("messageInput", "");
    private final JButton connect = namedButton("connect", "서버 연결", true);
    private final JButton demo = namedButton("demo", "먼저 둘러보기", false);
    private final JButton disconnect = namedButton("disconnect", "연결 종료", false);
    private final JButton create = namedButton("create", "방 만들기", true);
    private final JButton refresh = namedButton("refresh", "새로고침", false);
    private final JButton roomMenu = new MoreMenu();
    private final JMenuItem leave = new JMenuItem("방 나가기");
    private final JButton backToRooms = namedButton("backToRooms", "‹", false);
    private final JButton send = namedButton("send", "보내기", true);
    private final DefaultListModel<Room> roomModel = new DefaultListModel<>();
    private final JList<Room> roomList = new JList<>(roomModel) {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    };
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> users = new JList<>(userModel);
    private final ConversationView transcript = new ConversationView();
    private final CardLayout roomContentCards = new CardLayout();
    private final JPanel roomContent = Theme.panel(roomContentCards);
    private final JButton plannerToggle = namedButton("plannerToggle", "우리의 계획", false);
    private final JButton plansTool = namedButton("plansTool", "우리의 계획", false);
    private final PlannerPanel planner = new PlannerPanel(this::plannerRequest, this::showChat);

    private final JPanel accountHeader = Theme.panel(new BorderLayout());
    private final JLabel lobbyIdentity = Theme.label("", 12, false);
    private final JLabel menuIdentity = Theme.label("", 12, false);
    private final Border pageMargin = new EmptyBorder(12, 20, 10, 20);
    private final JLabel identity = Theme.label("연결되지 않음", 13, false);
    private final JLabel notice = Theme.label("오늘도, 가볍게 이야기해요.", 12, false);
    private final JLabel status = Theme.label("닉네임을 입력하고 데모 또는 서버 연결을 선택하세요.", 12, false);
    private final JLabel roomTitle = Theme.label("채팅방", 17, true);
    private final JLabel userTitle = Theme.label("함께 대화 중", 13, true);
    private final ThreadPoolExecutor network = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "chatapp-client-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final javax.swing.Timer timeout = new javax.swing.Timer(7000, event ->
            connectionLost("서버 응답 시간이 초과되었습니다. 연결을 확인하고 다시 접속하세요."));
    private ChatTransport transport;
    private long session;
    private boolean connected;
    private boolean connecting;
    private boolean demoMode;
    private String currentRoom;
    private String self;
    private String pending;

    public ChatPanel() {
        super(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(pageMargin);
        setPreferredSize(new Dimension(1080, 720));
        timeout.setRepeats(false);
        JLabel brand = Theme.label("ChatApp", 20, true);
        brand.setIcon(Theme.smile(30)); brand.setIconTextGap(10);
        accountHeader.add(brand, BorderLayout.WEST);
        JPanel account = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        identity.setIconTextGap(6);
        disconnect.setBorder(new EmptyBorder(8, 14, 8, 14));
        account.add(identity); account.add(disconnect);
        accountHeader.add(account, BorderLayout.EAST);
        add(accountHeader, BorderLayout.NORTH);
        pages.add(connectionPage(), "connection");
        pages.add(lobbyPage(), "lobby");
        pages.add(chatPage(), "room");
        JPanel center = Theme.panel(new BorderLayout(0, 12));
        notice.setName("notice");
        notice.setForeground(Theme.MUTED);
        notice.setBorder(new EmptyBorder(0, 3, 5, 0));
        center.add(notice, BorderLayout.NORTH);
        center.add(pages, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        status.setName("status"); status.setVisible(false);
        status.setBorder(new EmptyBorder(3, 0, 0, 0));
        add(status, BorderLayout.SOUTH);
        connect.addActionListener(event -> start(false));
        demo.addActionListener(event -> start(true));
        nickname.addActionListener(event -> start(false));
        disconnect.addActionListener(event -> disconnectSession());
        newConversation.addActionListener(event -> {
            createRoomPanel.setVisible(!createRoomPanel.isVisible());
            if (createRoomPanel.isVisible()) roomName.requestFocusInWindow();
            pages.revalidate(); pages.repaint();
        });
        roomSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { filterRooms(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { filterRooms(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { filterRooms(); }
        });
        create.addActionListener(event -> createRoom());
        roomName.addActionListener(event -> createRoom());
        roomList.addListSelectionListener(event -> updateButtons());
        roomList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int index = roomList.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1 && index >= 0 &&
                        roomList.getCellBounds(index,index).contains(event.getPoint())) {
                    roomList.setSelectedIndex(index); joinRoom();
                }
            }
        });
        roomList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openRoom");
        roomList.getActionMap().put("openRoom", new AbstractAction() {
            public void actionPerformed(ActionEvent event) { joinRoom(); }
        });
        refresh.addActionListener(event -> request(Message.of("ROOM_LIST"), "list"));
        leave.addActionListener(event -> request(Message.of("ROOM_LEAVE"), "leave"));
        membersToggle.addActionListener(event -> {
            membersPanel.setVisible(!membersPanel.isVisible()); updateMembers();
            pages.revalidate(); pages.repaint();
        });
        whisper.addActionListener(event -> {
            emoticonPicker.setVisible(false); hideComposerTools();
            whisperComposer.open(); pages.revalidate(); pages.repaint();
        });
        toolsToggle.addActionListener(event -> {
            boolean show=!composerTools.isVisible();
            emoticonPicker.setVisible(false); whisperComposer.setVisible(false);
            composerTools.setVisible(show); toolsToggle.setText(show ? "×" : "+");
            pages.revalidate(); pages.repaint();
        });
        game.addActionListener(event -> { hideComposerTools(); emoticonPicker.setVisible(false); gameView.setVisible(!gameView.isVisible()); });
        emoticons.addActionListener(event -> {
            boolean show=!emoticonPicker.isVisible(); hideComposerTools(); whisperComposer.setVisible(false);
            setStickerReward(false); emoticonPicker.setVisible(show);
            if(show) { gameView.setVisible(false); loadEmoticons(); }
            pages.revalidate(); pages.repaint();
        });
        send.addActionListener(event -> sendChat());
        messageInput.addActionListener(event -> sendChat());
        messageInput.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { saveDraft(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { saveDraft(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { saveDraft(); }
        });
        newMessages.addActionListener(event -> transcript.jumpToLatest());
        plannerToggle.addActionListener(event -> showPlanner());
        backToRooms.addActionListener(event -> {
            membersPanel.setVisible(false);
            updateMembers();
            showPage("lobby");
            roomList.requestFocusInWindow();
        });
        plansTool.addActionListener(event -> showPlanner());
        transcript.setReplyActions(quote -> {
            if (currentRoom == null || pending != null) return;
            setReplyTarget(quote);
            messageInput.requestFocusInWindow();
        }, () -> info("현재 대화에 원문이 없어요.", true));
        transcript.setTaskHandler((from, body) -> {
            if(currentRoom==null || pending!=null) return;
            membersPanel.setVisible(false); gameView.setVisible(false); hideComposerTools();
            roomContentCards.show(roomContent,"planner"); plannerToggle.setVisible(false); membersToggle.setVisible(false); planner.focusClose(); updateMembers();
            planner.fromMessage(from,body);
        });
        updateButtons();
    }

    private boolean plannerRequest(Message message) {
        if(!connected || currentRoom==null || pending!=null) return false;
        request(message,"planner"); return true;
    }
    private void showPlanner() {
        membersPanel.setVisible(false); gameView.setVisible(false); hideComposerTools();
        roomContentCards.show(roomContent,"planner"); plannerToggle.setVisible(false); membersToggle.setVisible(false); planner.focusClose(); updateMembers(); planner.open();
    }
    private void showChat() { roomContentCards.show(roomContent,"chat"); plannerToggle.setVisible(true); membersToggle.setVisible(true); messageInput.requestFocusInWindow(); }

    private void gameResultRevealed(String summary) { transcript.addNotice(summary); }

    private void hideComposerTools() { composerTools.setVisible(false); toolsToggle.setText("+"); }

    private void showPage(String page) {
        boolean inRoom = "room".equals(page);
        accountHeader.setVisible("connection".equals(page));
        setBorder(inRoom ? null : pageMargin);
        notice.setVisible("connection".equals(page));
        cards.show(pages, page);
    }

    private JPanel connectionPage() {
        JPanel outer = Theme.panel(new GridBagLayout());
        JPanel pair = Theme.panel(new GridLayout(1, 2, 38, 0));
        pair.setPreferredSize(new Dimension(850, 440));
        JPanel welcome = Theme.panel(new GridBagLayout());
        JPanel words = Theme.panel(new GridLayout(5, 1, 0, 8));
        JLabel face = new JLabel(Theme.smile(88)); face.setHorizontalAlignment(SwingConstants.LEFT);
        words.add(face); words.add(Theme.label("반가워요!", 32, true));
        words.add(Theme.label("우리, 여기서 이야기해요.", 23, true));
        JLabel intro = Theme.label("가벼운 수다도, 함께할 약속도.", 14, false); intro.setForeground(Theme.MUTED);
        words.add(intro); welcome.add(words); pair.add(welcome);
        JPanel form = box(new BorderLayout(0, 22));
        form.add(Theme.label("어떤 이름으로 불러드릴까요?", 18, true), BorderLayout.NORTH);
        JPanel fields = Theme.panel(new GridLayout(3, 1, 0, 15));
        nickname.putClientProperty("placeholder", "친구들에게 보여줄 이름");
        fields.add(labeled("닉네임", nickname));
        fields.add(labeled("서버 주소", host)); fields.add(labeled("포트", port));
        form.add(fields, BorderLayout.CENTER);
        JPanel actions = Theme.panel(new GridLayout(2, 1, 0, 10));
        actions.add(connect); actions.add(demo); form.add(actions, BorderLayout.SOUTH);
        pair.add(form); outer.add(pair); return outer;
    }
    private static final class MoreMenu extends JButton {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            if (getModel().isPressed() || getModel().isRollover() || hasFocus()) {
                g.setColor(new Color(0, 0, 0, 18));
                g.fillOval(cx - 15, cy - 15, 30, 30);
            }
            g.setColor(isEnabled() ? Theme.INK : Theme.MUTED);
            for (int offset = -6; offset <= 6; offset += 6)
                g.fillOval(cx + offset - 2, cy - 2, 4, 4);
            g.dispose();
        }
    }

    private JButton accountMenu(JButton button, String name, String label, JLabel accountLabel, JMenuItem roomAction) {
        button.setName(name);
        button.setToolTipText(label);
        button.getAccessibleContext().setAccessibleName(label);
        button.setPreferredSize(new Dimension(34, 32));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPopupMenu popup = new JPopupMenu();
        accountLabel.setBorder(new EmptyBorder(6, 12, 6, 12));
        accountLabel.setForeground(Theme.MUTED);
        popup.add(accountLabel);
        popup.addSeparator();
        if (roomAction != null) popup.add(roomAction);
        JMenuItem endSession = new JMenuItem("연결 종료");
        endSession.addActionListener(event -> disconnectSession());
        popup.add(endSession);

        button.setComponentPopupMenu(popup);
        button.addActionListener(event -> popup.show(button,
                button.getWidth() - popup.getPreferredSize().width, button.getHeight()));
        button.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !button.isShowing()) {
                popup.setVisible(false);
            }
        });
        return button;
    }

    private JPanel lobbyPage() {
        JPanel conversations = new JPanel(new BorderLayout(0, 12)); conversations.setBackground(Color.WHITE);
        conversations.setName("conversations");
        conversations.setBorder(new EmptyBorder(22, 20, 16, 20));
        JPanel tools = Theme.panel(new BorderLayout(0, 12));
        JPanel heading = Theme.panel(new BorderLayout());
        heading.add(Theme.label("채팅", 24, true), BorderLayout.WEST);
        newConversation.setFont(new Font("SansSerif", Font.BOLD, 12));
        newConversation.setBorder(new EmptyBorder(9, 12, 9, 12));
        JPanel headerActions = Theme.panel(new BorderLayout(8, 0));
        headerActions.add(newConversation, BorderLayout.CENTER);
        headerActions.add(accountMenu(new MoreMenu(), "lobbyMenu", "대화 목록 메뉴", lobbyIdentity, null), BorderLayout.EAST);
        heading.add(headerActions, BorderLayout.EAST);
        tools.add(heading, BorderLayout.NORTH);
        roomSearch.putClientProperty("placeholder", "대화 이름으로 찾기");
        roomSearch.setFont(new Font("SansSerif", Font.PLAIN, 13));
        roomSearch.setBorder(new EmptyBorder(10, 12, 10, 12));
        roomSearch.setToolTipText("현재 대화 목록에서 이름으로 검색합니다.");
        tools.add(roomSearch, BorderLayout.CENTER);
        roomName.putClientProperty("placeholder", "예: 오늘 뭐 먹지?");
        roomName.setBorder(new EmptyBorder(10, 12, 10, 12));
        create.setBorder(new EmptyBorder(9, 12, 9, 12));
        createRoomPanel.setBorder(new EmptyBorder(4, 0, 2, 0));
        createRoomPanel.add(Theme.label("어떤 이름으로 이야기할까요?", 12, true), BorderLayout.NORTH);
        createRoomPanel.add(roomName, BorderLayout.CENTER);
        createRoomPanel.add(create, BorderLayout.SOUTH); createRoomPanel.setVisible(false);
        tools.add(createRoomPanel, BorderLayout.SOUTH); conversations.add(tools, BorderLayout.NORTH);
        roomList.setName("roomList"); roomList.setBackground(Color.WHITE);
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); roomList.setFixedCellHeight(76);
        roomList.setCellRenderer((list, room, index, selected, focus) -> {
            JPanel cell = Theme.rounded(new BorderLayout(9, 0), room.id().equals(currentRoom) ? new Color(0xFFF6CE) : Color.WHITE, 20);
            // Open-room state and keyboard selection are independent during a room switch.
            if (selected && list.hasFocus()) {
                cell.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(9, 7, 9, 7)));
            } else cell.setBorder(new EmptyBorder(10, 8, 10, 8));
            cell.add(new JLabel(Theme.avatar(room.name(), 44)), BorderLayout.WEST);
            JPanel words = Theme.panel(new BorderLayout(0, 4));
            JPanel titleRow = Theme.panel(new BorderLayout(6, 0));
            JLabel title = Theme.label(room.name(), 16, true); titleRow.add(title, BorderLayout.CENTER);
            String time = recentTimes.get(room.id());
            JLabel meta = Theme.label(time != null ? time : room.count()+"명", 10, false);
            meta.setForeground(Theme.MUTED); titleRow.add(meta, BorderLayout.EAST);
            words.add(titleRow, BorderLayout.NORTH);
            String draft = drafts.get(room.id());
            JLabel preview = Theme.label(draft != null ? "[작성 중] " + draft : recentMessages.getOrDefault(room.id(), "들어가서 첫 인사를 나눠보세요"), 12, false);
            preview.setForeground(draft != null ? new Color(0xA97824) : Theme.MUTED); words.add(preview, BorderLayout.CENTER);
            cell.add(words, BorderLayout.CENTER);
            cell.setToolTipText("대화: " + room.name() + " / " + preview.getText());
            cell.setSize(Math.max(list.getWidth(), 200), list.getFixedCellHeight());
            cell.doLayout(); words.doLayout(); titleRow.doLayout();
            return cell;
        });
        JScrollPane scroll = new JScrollPane(roomList); scroll.setBorder(null); scroll.getViewport().setBackground(Color.WHITE);
        JPanel listArea = Theme.panel(new BorderLayout(0, 8));
        roomCount.setName("roomCount"); roomCount.setForeground(Theme.MUTED);
        listArea.add(roomCount, BorderLayout.NORTH); listArea.add(scroll, BorderLayout.CENTER);
        emptyRooms.setName("emptyRooms"); emptyRooms.setForeground(Theme.MUTED);
        listArea.add(emptyRooms, BorderLayout.SOUTH); conversations.add(listArea, BorderLayout.CENTER);
        JPanel footer = Theme.panel(new BorderLayout(8, 0));
        refresh.setText("목록 갱신"); refresh.setToolTipText("서버에서 최신 대화 목록을 받아옵니다.");
        refresh.setFont(new Font("SansSerif", Font.BOLD, 12));
        refresh.setBorder(new EmptyBorder(7, 9, 7, 9));
        footer.add(refresh, BorderLayout.EAST);
        conversations.add(footer, BorderLayout.SOUTH);
        return conversations;
    }
    private void filterRooms() {
        Room selected = roomList.getSelectedValue();
        String query = roomSearch.getText().strip().toLowerCase(Locale.ROOT);
        roomModel.clear();
        List<Room> sorted = new ArrayList<>(allRooms);
        sorted.sort(Comparator.comparingLong((Room room) -> recentActivity.getOrDefault(room.id(), 0L)).reversed());
        for (Room room : sorted) {
            if (!room.name().toLowerCase(Locale.ROOT).contains(query)) continue;
            roomModel.addElement(room);
            if (selected != null && selected.id().equals(room.id())) roomList.setSelectedValue(room, true);
        }
        roomCount.setText(query.isEmpty() ? "대화 "+roomModel.size() : "검색 결과 "+roomModel.size());
        emptyRooms.setText(query.isEmpty() ? "새 대화를 만들고 인사를 나눠보세요." : "다른 대화 이름으로 검색해 보세요.");
        emptyRooms.setVisible(roomModel.isEmpty()); updateButtons();
    }
    private JPanel chatPage() {
        JPanel panel = Theme.panel(new BorderLayout());
        JPanel conversation = new JPanel(new BorderLayout()); conversation.setBackground(Theme.CHAT);
        JPanel heading = Theme.panel(new BorderLayout(16, 0)); heading.setBorder(new EmptyBorder(14, 22, 12, 22));
        roomTitle.setName("roomTitle");
        JPanel titleGroup = Theme.panel(new GridBagLayout());
        GridBagConstraints slot = new GridBagConstraints();
        slot.gridy = 0; slot.anchor = GridBagConstraints.WEST;
        backToRooms.setToolTipText("대화 목록으로");
        backToRooms.getAccessibleContext().setAccessibleName("대화 목록으로");
        backToRooms.setFont(backToRooms.getFont().deriveFont(22f));
        backToRooms.setBorder(new EmptyBorder(2, 10, 4, 10));
        slot.insets = new Insets(0, 0, 0, 10);
        titleGroup.add(backToRooms, slot);
        roomTitle.setMinimumSize(new Dimension(36, 24));
        titleGroup.add(roomTitle, slot);
        plannerToggle.setIcon(calendarIcon());
        plannerToggle.setFont(plannerToggle.getFont().deriveFont(Font.PLAIN, 12f));
        plannerToggle.setBorder(new EmptyBorder(5, 8, 5, 8));
        titleGroup.add(plannerToggle, slot);
        slot.weightx = 1;
        titleGroup.add(Box.createHorizontalGlue(), slot);
        heading.add(titleGroup, BorderLayout.CENTER);
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        membersToggle.setBorder(new EmptyBorder(8, 10, 8, 10));
        leave.setName("leave");
        actions.add(membersToggle);
        actions.add(accountMenu(roomMenu, "roomMenu", "대화방 메뉴", menuIdentity, leave));
        heading.add(actions, BorderLayout.EAST);
        JPanel chatHeader = Theme.panel(new BorderLayout());
        chatHeader.add(heading, BorderLayout.NORTH); chatHeader.add(gameView, BorderLayout.CENTER);
        conversation.add(chatHeader, BorderLayout.NORTH);
        JScrollPane messages = new JScrollPane(transcript); messages.setBorder(null);
        messages.setName("messageScroll");
        messages.getViewport().setBackground(Theme.CHAT); messages.getVerticalScrollBar().setUnitIncrement(24);
        JPanel chatContent=Theme.panel(new BorderLayout());
        chatContent.add(messages, BorderLayout.CENTER);
        JPanel composerWrap = Theme.panel(new BorderLayout()); composerWrap.setBorder(new EmptyBorder(10, 18, 18, 18));
        JPanel composer = Theme.rounded(new BorderLayout(10, 0), Color.WHITE, 24);
        composer.setBorder(new EmptyBorder(10, 10, 10, 10));
        messageInput.putClientProperty("placeholder", "메시지를 입력하세요");
        messageInput.setToolTipText("메시지 1~1000자 · Enter로 보내기");
        JPanel inputActions=Theme.panel(new FlowLayout(FlowLayout.LEFT,4,0));
        toolsToggle.setToolTipText("게임·귓속말·우리의 계획 열기");
        toolsToggle.getAccessibleContext().setAccessibleName("대화 도구 열기");
        toolsToggle.setFont(new Font("SansSerif",Font.PLAIN,22));
        toolsToggle.setBorder(new EmptyBorder(5,12,5,12));
        setStickerReward(false);
        emoticons.setBorder(new EmptyBorder(4, 5, 4, 5));
        inputActions.add(toolsToggle); inputActions.add(emoticons);
        composer.add(inputActions, BorderLayout.WEST);
        composer.add(messageInput, BorderLayout.CENTER); composer.add(send, BorderLayout.EAST);
        JPanel jumpRow = Theme.panel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        jumpRow.setBorder(new EmptyBorder(0, 0, 8, 0)); jumpRow.add(newMessages); jumpRow.setVisible(false);
        newMessages.setVisible(false);
        transcript.attachScrollPane(messages, unseen -> {
            newMessages.setVisible(unseen); jumpRow.setVisible(unseen);
            composerWrap.revalidate(); composerWrap.repaint();
        });
        JPanel previewText = Theme.panel(new GridLayout(2, 1));
        // 메시지와 닉네임을 Swing HTML로 해석하지 않는다.
        replyExcerpt.putClientProperty("html.disable", true);
        replyAuthor.putClientProperty("html.disable", true);
        replyExcerpt.setMinimumSize(new Dimension(0, 16));
        previewText.add(replyAuthor);
        previewText.add(replyExcerpt);
        replyPreview.setName("replyPreview");
        replyPreview.setBorder(new EmptyBorder(4, 12, 6, 6));
        replyPreview.add(previewText, BorderLayout.CENTER);
        cancelReply.setToolTipText("답장 취소");
        cancelReply.getAccessibleContext().setAccessibleName("답장 취소");
        cancelReply.addActionListener(event -> {
            setReplyTarget(null);
            messageInput.requestFocusInWindow();
        });
        replyPreview.add(cancelReply, BorderLayout.EAST);
        replyPreview.setVisible(false);
        JPanel aboveInput = Theme.panel(new BorderLayout());
        aboveInput.add(jumpRow, BorderLayout.NORTH);
        aboveInput.add(replyPreview, BorderLayout.CENTER);
        composerWrap.add(aboveInput, BorderLayout.NORTH);
        composerWrap.add(composer, BorderLayout.CENTER);
        JPanel extras = Theme.panel(new BorderLayout());
        composerTools.setName("composerTools"); composerTools.setBorder(new EmptyBorder(10,0,4,0));
        game.setIcon(new GameHandIcon("ROCK",28)); composerTools.add(game); composerTools.add(whisper); composerTools.add(plansTool);
        composerTools.setVisible(false);
        extras.add(composerTools, BorderLayout.NORTH);
        JPanel drawers=Theme.panel(new BorderLayout());
        drawers.add(emoticonPicker, BorderLayout.NORTH); drawers.add(whisperComposer, BorderLayout.CENTER);
        extras.add(drawers, BorderLayout.CENTER);
        composerWrap.add(extras, BorderLayout.SOUTH);
        chatContent.add(composerWrap, BorderLayout.SOUTH);
        roomContent.add(chatContent,"chat"); roomContent.add(planner,"planner");
        conversation.add(roomContent,BorderLayout.CENTER);
        panel.add(conversation, BorderLayout.CENTER);
        JPanel side = membersPanel; side.setName("membersPanel"); side.setBackground(new Color(0xF6F5F1));
        side.setBorder(new EmptyBorder(24, 18, 20, 18)); side.setPreferredSize(new Dimension(170, 0));
        userTitle.setName("userTitle");
        side.add(userTitle, BorderLayout.NORTH);
        users.setName("users"); users.setFixedCellHeight(54); users.setBackground(side.getBackground());
        users.setCellRenderer((list, user, index, selected, focus) -> {
            JLabel label = Theme.label(user.equals(self) ? user+" · 나" : user, 13, false);
            label.setIcon(Theme.avatar(user, 34)); label.setIconTextGap(10);
            label.setBorder(new EmptyBorder(7, 0, 7, 0)); return label;
        });
        JScrollPane userScroll = new JScrollPane(users); userScroll.setBorder(null);
        userScroll.getViewport().setBackground(side.getBackground()); side.add(userScroll, BorderLayout.CENTER);
        side.setVisible(false); panel.add(side, BorderLayout.EAST); return panel;
    }

    private void start(boolean demoMode) {
        if (connected || connecting) return;
        String name = nickname.getText().strip();
        if (!validText(name, 20)) { info("닉네임은 제어문자 없이 1~20자로 입력해 주세요.", true); return; }
        String address = host.getText().strip();
        int number = 5000;
        if (!demoMode) {
            try {
                number = Integer.parseInt(port.getText().strip());
                if (number < 1 || number > 65535 || address.isEmpty()) throw new NumberFormatException();
            } catch (NumberFormatException e) { info("서버 주소와 포트(1~65535)를 확인해 주세요.", true); return; }
        }
        this.demoMode = demoMode;
        connecting = true;
        long token = ++session;
        ChatTransport next = demoMode ? new DemoTransport() : new SocketTransport();
        transport = next;
        updateButtons();
        info(demoMode ? "화면 데모를 준비하고 있습니다." : "서버에 연결하고 있습니다…", false);
        int finalPort = number;
        submit(() -> {
            try {
                next.connect(address, finalPort, name, new ChatTransport.Listener() {
                    @Override public void onMessage(Message message) {
                        SwingUtilities.invokeLater(() -> { if (session == token) receive(message); });
                    }
                    @Override public void onDisconnected(String reason) {
                        SwingUtilities.invokeLater(() -> { if (session == token) connectionLost(reason); });
                    }
                });
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> { if (session == token) connectionLost("연결 실패: " + reason(e)); });
            }
        });
    }
    private void saveDraft() {
        if (currentRoom == null) return;
        String value = messageInput.getText();
        if (value.isEmpty()) drafts.remove(currentRoom); else drafts.put(currentRoom,value);
        roomList.repaint();
    }
    private void createRoom() {
        String text = roomName.getText().strip();
        if (!validText(text, 40)) { info("방 이름은 제어문자 없이 1~40자로 입력해 주세요.", true); return; }
        openRoom(Message.of("ROOM_CREATE", text));
    }
    private void joinRoom() {
        Room room = roomList.getSelectedValue();
        if (room == null || !connected || pending != null) return;
        if (room.id().equals(currentRoom)) {
            showPage("room");
            if (plannerToggle.isVisible()) messageInput.requestFocusInWindow();
            else planner.focusClose();
        } else {
            openRoom(Message.of("ROOM_JOIN", room.id()));
        }
    }
    private void openRoom(Message message) {
        if (!connected || pending != null) return;
        if (currentRoom == null) request(message, "room");
        else {
            // 서버의 한 사용자-한 방 규칙 유지: 퇴장 성공 후 다음 방을 요청한다.
            afterLeave = message;
            request(Message.of("ROOM_LEAVE"), "leave");
        }
    }
    private void updateMembers() {
        membersToggle.setText("멤버 " + userModel.size() + "명 " + (membersPanel.isVisible() ? "▴" : "▾"));
        userTitle.setText("대화 멤버 · " + userModel.size() + "명");
    }
    private void setReplyTarget(ConversationView.Quote quote) {
        replyTarget = quote;
        replyAuthor.setText(quote == null ? "" : quote.sender() + "님에게 답장");
        replyExcerpt.setText(quote == null ? "" : quote.text());
        replyPreview.setVisible(quote != null);
        replyPreview.revalidate();
    }

    private void clearReply() {
        pendingReplyId = null;
        pendingReplyText = null;
        setReplyTarget(null);
    }

    private void sendChat() {
        if (!connected || currentRoom == null || pending != null) return;
        String body = messageInput.getText().strip();
        if (!validText(body, 1000)) { info("메시지는 제어문자 없이 1~1000자로 입력해 주세요.", true); return; }
        if (replyTarget == null) {
            request(Message.of("CHAT_SEND", body), "chat");
            return;
        }
        pendingReplyId = UUID.randomUUID().toString();
        pendingReplyText = body;
        request(Message.of("CHAT_REPLY_SEND", pendingReplyId, currentRoom, replyTarget.id(), body), "reply");
    }
    private void startGame() {
        if (!connected || currentRoom == null || pending != null || gameView.gameId() != null) return;
        if (userModel.size() < 2) { info("두 명 이상 모이면 시작할 수 있어요.", true); return; }
        request(Message.of("GAME_START"), "game-start");
    }
    private void chooseGame(String choice) {
        if (pending != null || !connected || currentRoom == null || !gameView.canChoose()) return;
        pendingGame = gameView.gameId();
        request(Message.of("GAME_CHOOSE", pendingGame, choice), "game-choose");
    }
    private void loadEmoticons() {
        if (connected && currentRoom != null && pending == null) request(Message.of("EMOTICON_LIST"), "emoticon-list");
    }
    private void sendEmoticon(String id) {
        if (!connected || currentRoom == null || pending != null || !emoticonPicker.owns(id)) return;
        pendingEmoticon = id; request(Message.of("EMOTICON_SEND", id), "emoticon-send");
    }
    private void sendWhisper(String target, String body) {
        if (!connected || pending != null || currentRoom == null) return;
        if (target == null || target.equals(self) || !userModel.contains(target)) {
            info("같은 방에 있는 귓속말 상대를 선택해 주세요.", true); return;
        }
        if (!validText(body, 1000)) {
            info("귓속말은 제어문자 없이 1~1000자로 입력해 주세요.", true); return;
        }
        pendingWhisperTarget = target; pendingWhisperBody = body;
        request(Message.of("WHISPER_SEND", target, body), "whisper");
    }
    private void request(Message message, String operation) {
        if (!connected || pending != null) return;
        info("", false);
        pending = operation;
        timeout.restart(); updateButtons();
        ChatTransport target = transport;
        long token = session;
        submit(() -> {
            try { target.send(message); }
            catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> { if (session == token) connectionLost("전송 실패: " + reason(e)); });
            }
        });
    }
    private void receive(Message message) {
        try {
            switch (message.type()) {
                case "WELCOME" -> {
                    message.requireFields(1);
                    self = message.field(0); connecting = false; connected = true;
                    identity.setText(self + (demoMode ? " · 데모" : " · 연결됨"));
                    identity.setIcon(demoMode ? null : connectionDot());
                    menuIdentity.setText(identity.getText());
                    lobbyIdentity.setText(identity.getText());
                    showPage("lobby");
                    info("방 목록에서 입장하거나 새 방을 만들어 주세요.", false);
                }
                case "ROOMS" -> {
                    if (message.fields().size() % 3 != 0) throw new IOException("방 목록 필드 수 오류");
                    List<Room> nextRooms = new ArrayList<>();
                    Set<String> ids = new HashSet<>();
                    for (int i = 0; i < message.fields().size(); i += 3) {
                        int count = Integer.parseInt(message.field(i + 2));
                        if (count < 0 || !ids.add(message.field(i))) throw new IOException("방 목록 값 오류");
                        nextRooms.add(new Room(message.field(i), message.field(i + 1), count));
                    }
                    allRooms.clear(); allRooms.addAll(nextRooms); filterRooms();
                    finish("list");
                    if (nextRooms.isEmpty() && currentRoom == null) info("아직 방이 없습니다. 첫 방을 만들어 보세요.", false);
                }
                case "ROOM_JOINED" -> {
                    message.requireFields(2);
                    currentRoom = message.field(0); roomTitle.setText(message.field(1));
                    roomTitle.setToolTipText(message.field(1));
                    planner.enter(currentRoom,self); showChat();
                    whisperComposer.clear(); gameView.clearRoom(); emoticonPicker.setVisible(false); hideComposerTools();
                    clearReply(); transcript.clear(); userModel.clear(); userTitle.setText("함께 대화 중");
                    String draft = drafts.getOrDefault(currentRoom, "");
                    messageInput.setText(draft); roomName.setText("");
                    createRoomPanel.setVisible(false); roomSearch.setText("");
                    showPage("room"); finish("room");
                    membersPanel.setVisible(false); updateMembers(); filterRooms();
                    for (int i = 0; i < roomModel.size(); i++) {
                        if (roomModel.get(i).id().equals(currentRoom)) { roomList.setSelectedIndex(i); break; }
                    }
                    roomList.repaint();
                    info("방에 입장했습니다. 메시지를 입력해 보세요.", false);
                    messageInput.requestFocusInWindow();
                }
                case "ROOM_LEFT" -> {
                    message.requireFields(0); saveDraft(); currentRoom = null; roomList.repaint();
                    planner.enter(null,self); showChat();
                    whisperComposer.clear(); gameView.clearRoom(); emoticonPicker.setVisible(false); hideComposerTools();
                    userModel.clear(); clearReply(); transcript.clear(); messageInput.setText("");
                    showPage("lobby"); finish("leave"); info("로비로 돌아왔습니다.", false);
                    membersPanel.setVisible(false); updateMembers();
                    Message nextRoom = afterLeave; afterLeave = null;
                    if (nextRoom != null) request(nextRoom, "room");
                }
                case "ROOM_USERS" -> {
                    if (message.fields().isEmpty()) throw new IOException("참가자 목록 필드 수 오류");
                    if (Objects.equals(currentRoom, message.field(0))) {
                        userModel.clear();
                        message.fields().subList(1, message.fields().size()).forEach(userModel::addElement);
                        whisperComposer.updateTargets(Collections.list(userModel.elements()), self);
                        planner.updateMembers(Collections.list(userModel.elements()));
                        gameView.membersChanged(Collections.list(userModel.elements()));
                        updateMembers();
                    }
                }
                case "CHAT_MESSAGE" -> receiveChat(message, false);
                case "CHAT_REPLY_MESSAGE" -> receiveChat(message, true);
                case "GAME_STARTED" -> {
                    if (currentRoom != null && gameView.started(message)) {
                        emoticonPicker.setVisible(false); hideComposerTools();
                        transcript.addNotice(message.field(1) + "님이 가위바위보를 시작했어요");
                        if (Objects.equals(self, message.field(1))) finish("game-start");
                    }
                }
                case "GAME_CHOSEN" -> {
                    if (currentRoom != null && gameView.chosen(message) && Objects.equals(self, message.field(1))
                            && Objects.equals(pendingGame, message.field(0))) {
                        finish("game-choose"); pendingGame = null;
                    }
                }
                case "GAME_RESULT", "GAME_CANCELED" -> {
                    if (currentRoom != null) {
                        String result = message.type().equals("GAME_RESULT") ? gameView.result(message) : gameView.canceled(message);
                        if (result != null) {
                            emoticonPicker.setVisible(false); hideComposerTools();
                            if (message.type().equals("GAME_CANCELED")) transcript.addNotice(result);
                            if (Objects.equals(pendingGame, message.field(0))) { finish("game-choose"); pendingGame = null; }
                        }
                    }
                }
                case "EMOTICON_OWNED" -> { emoticonPicker.owned(message); finish("emoticon-list"); }
                case "EMOTICON_GRANTED" -> {
                    message.requireFields(2); EmoticonPicker.validate(message.field(1));
                    if (rewardedGames.add(message.field(0))) {
                        if (rewardedGames.size() > 300) rewardedGames.remove(rewardedGames.iterator().next());
                        emoticonPicker.granted(message.field(1));
                        // 보상은 계정에 도착하는 이벤트이므로 방 밖에서도 보유 목록에 반영한다.
                        if (currentRoom != null) transcript.addNotice("새 이모티콘을 받았어요 · " + EmoticonPicker.label(message.field(1)));
                        setStickerReward(true);
                    }
                }
                case "EMOTICON_MESSAGE" -> {
                    message.requireFields(3); EmoticonPicker.validate(message.field(2));
                    if (Objects.equals(currentRoom, message.field(0))) {
                        transcript.addEmoticon(message.field(1), message.field(2), Objects.equals(self, message.field(1)));
                        recentMessages.put(currentRoom, (Objects.equals(self,message.field(1)) ? "나" : message.field(1))
                                + ": [이모티콘] " + EmoticonPicker.label(message.field(2)));
                        recentTimes.put(currentRoom, java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
                        recentActivity.put(currentRoom, ++activitySequence); filterRooms();
                        if (Objects.equals(self, message.field(1)) && Objects.equals(pendingEmoticon, message.field(2))) {
                            finish("emoticon-send"); pendingEmoticon = null;
                        }
                    }
                }
                case "WHISPER_MESSAGE" -> {
                    message.requireFields(4);
                    String room = message.field(0), from = message.field(1), to = message.field(2), body = message.field(3);
                    if (Objects.equals(currentRoom, room) && (Objects.equals(self, from) || Objects.equals(self, to))) {
                        boolean mine = Objects.equals(self, from);
                        String label = mine ? "귓속말 · " + to + "님에게" : "귓속말 · " + from + "님에게서";
                        transcript.addMessage(label, "[" + label + "]\n" + body, mine);
                        if (mine && "whisper".equals(pending) && Objects.equals(to, pendingWhisperTarget)
                                && Objects.equals(body, pendingWhisperBody)) {
                            whisperComposer.acknowledge(to, body); finish("whisper");
                            pendingWhisperTarget = null; pendingWhisperBody = null;
                            info("귓속말을 전송했습니다.", false);
                        }
                    }
                }
                case "SYSTEM" -> {
                    message.requireFields(2);
                    if (Objects.equals(currentRoom, message.field(0))) transcript.addNotice(message.field(1));
                }
                case "ERROR" -> {
                    if(message.fields().size()!=2 && message.fields().size()!=3) throw new IOException("ERROR 필드 수 오류");
                    if ("reply".equals(pending)) {
                        if (message.fields().size() == 3 && message.field(2).equals(pendingReplyId)) {
                            finish("reply");
                            pendingReplyId = null;
                            pendingReplyText = null;
                            info(message.field(1) + " (" + message.field(0) + ")", true);
                            messageInput.requestFocusInWindow();
                        }
                        break;
                    }
                    if(planner.waiting()) {
                        if(planner.error(message)) finish("planner");
                        break;
                    }
                    // 신규 기능의 늦은 오류는 기존 채팅/게임 대기를 해제하지 않는다.
                    if(message.fields().size()==3) break;
                    gameView.choiceRejected(); timeout.stop(); pending = null; afterLeave = null;
                    pendingWhisperTarget = null; pendingWhisperBody = null; pendingGame = null; pendingEmoticon = null;
                    info(message.field(1) + " (" + message.field(0) + ")", true);
                }
                default -> {
                    if(chatapp.client.planner.PlannerState.supports(message.type())) {
                        if(planner.receive(message)) finish("planner");
                    } else info("미지원 서버 메시지: " + message.type(), true);
                }
            }
            updateButtons();
        } catch (IOException | RuntimeException e) {
            connectionLost("서버 응답 형식을 확인해 주세요: " + reason(e));
        }
    }
    private void receiveChat(Message message, boolean reply) throws IOException {
        boolean legacy = !reply && message.fields().size() == 3;
        if (!legacy) message.requireFields(reply ? 8 : 4);
        if (!Objects.equals(currentRoom, message.field(0))) return;
        String sender = message.field(reply ? 3 : 1);
        String body = message.field(reply ? 4 : 2);
        String id = legacy ? null : message.field(reply ? 2 : 3);
        if (!legacy) UUID.fromString(id);
        ConversationView.Quote source = null;
        if (reply) {
            UUID.fromString(message.field(1));
            UUID.fromString(message.field(5));
            source = new ConversationView.Quote(message.field(5), message.field(6), message.field(7));
        }
        boolean mine = sender.equals(self);
        if (transcript.addChat(sender, body, mine, id, source)) {
            recentMessages.put(currentRoom, (mine ? "나" : sender) + ": " + body);
            recentTimes.put(currentRoom, java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
            recentActivity.put(currentRoom, ++activitySequence);
            filterRooms();
        }
        if (!mine) return;
        if (reply && "reply".equals(pending) && message.field(1).equals(pendingReplyId)) {
            if (messageInput.getText().strip().equals(pendingReplyText)) messageInput.setText("");
            clearReply();
            finish("reply");
            info("답장을 전송했습니다.", false);
        } else if (!reply && "chat".equals(pending)) {
            if (messageInput.getText().strip().equals(body)) messageInput.setText("");
            finish("chat");
            info("메시지를 전송했습니다.", false);
        }
    }

    private void finish(String operation) {
        if (operation.equals(pending)) { pending = null; timeout.stop(); }
    }
    private void updateButtons() {
        boolean ready = connected && pending == null;
        connect.setEnabled(!connected && !connecting); demo.setEnabled(!connected && !connecting);
        nickname.setEnabled(!connected && !connecting); host.setEnabled(!connected && !connecting); port.setEnabled(!connected && !connecting);
        disconnect.setVisible(connected || connecting);
        newConversation.setEnabled(ready);
        roomSearch.setEnabled(connected); roomList.setEnabled(ready);
        create.setEnabled(ready);
        refresh.setEnabled(ready); roomName.setEnabled(ready);
        roomMenu.setEnabled(connected && currentRoom != null);
        backToRooms.setEnabled(ready && currentRoom != null);
        leave.setEnabled(ready && currentRoom != null); send.setEnabled(ready && currentRoom != null);
        membersToggle.setEnabled(connected && currentRoom != null);
        toolsToggle.setEnabled(connected && currentRoom != null);
        cancelReply.setEnabled(!"reply".equals(pending));
        messageInput.setEnabled(connected && currentRoom != null);
        whisper.setEnabled(ready && currentRoom != null);
        whisperComposer.setReady(ready && currentRoom != null);
        game.setEnabled(connected && currentRoom != null); emoticons.setEnabled(ready && currentRoom != null);
        gameView.setReady(ready && currentRoom != null, self, userModel.size());
        emoticonPicker.setReady(ready && currentRoom != null);
        plannerToggle.setEnabled(connected && currentRoom!=null);
        plansTool.setEnabled(connected && currentRoom!=null);
        planner.setReady(ready && currentRoom!=null);
    }
    private void connectionLost(String reason) { reset(); info(reason, true); }
    private void disconnectSession() { reset(); info("연결을 종료했습니다.", false); }
    private void reset() {
        session++; timeout.stop(); planner.enter(null,null); showChat();
        hideComposerTools(); gameView.reset(); emoticonPicker.reset(); rewardedGames.clear(); pendingGame = null; pendingEmoticon = null;
        setStickerReward(false);
        whisperComposer.clear(); pendingWhisperTarget = null; pendingWhisperBody = null;
        if (transport != null) transport.close();
        transport = null; connected = false; connecting = false; pending = null; currentRoom = null; afterLeave = null;
        allRooms.clear(); recentMessages.clear(); recentTimes.clear(); recentActivity.clear(); drafts.clear(); activitySequence=0; roomSearch.setText("");
        createRoomPanel.setVisible(false); filterRooms();
        roomModel.clear(); userModel.clear(); clearReply(); transcript.clear(); messageInput.setText("");
        identity.setText("연결되지 않음"); identity.setIcon(null); notice.setText("오늘도, 가볍게 이야기해요.");
        membersPanel.setVisible(false); updateMembers();
        showPage("connection"); updateButtons();
    }
    private void submit(Runnable work) {
        try { network.execute(work); }
        catch (RejectedExecutionException e) { connectionLost("처리할 요청이 많습니다. 다시 연결해 주세요."); }
    }
    private void info(String text, boolean error) {
        status.setText(text); status.setToolTipText(text);
        status.setVisible(error || connecting);
        status.setForeground(error ? new Color(0xB42318) : Theme.MUTED);
    }
    private static boolean validText(String text, int limit) {
        return !text.isEmpty() && text.length() <= limit && text.chars().noneMatch(Character::isISOControl);
    }
    private static String reason(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void setStickerReward(boolean newReward) {
        String label = newReward ? "이모티콘 · 새 보상" : "이모티콘";
        emoticons.setToolTipText(label);
        emoticons.getAccessibleContext().setAccessibleName(label);
        Icon sticker = new EmoticonIcon("E01", 29);
        if (!newReward) { emoticons.setIcon(sticker); return; }
        emoticons.setIcon(new Icon() {
            public int getIconWidth() { return sticker.getIconWidth(); }
            public int getIconHeight() { return sticker.getIconHeight(); }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                sticker.paintIcon(c, g, x, y);
                g.setColor(new Color(0xC8513B)); g.fillOval(x+23, y, 6, 6);
            }
        });
    }

    private static Icon calendarIcon() {
        return new Icon() {
            public int getIconWidth() { return 14; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.scale(14.0 / 16, 14.0 / 16);
                g.setColor(Theme.INK); g.setStroke(new BasicStroke(1.4f));
                g.drawRoundRect(2, 3, 12, 11, 3, 3);
                g.drawLine(2, 7, 14, 7); g.drawLine(5, 1, 5, 4); g.drawLine(11, 1, 11, 4);
                g.dispose();
            }
        };
    }

    private static Icon connectionDot() {
        return new Icon() {
            public int getIconWidth() { return 8; }
            public int getIconHeight() { return 8; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0x64A581)); g.fillOval(x, y, 8, 8); g.dispose();
            }
        };
    }
    private static JButton namedButton(String name, String text, boolean primary) {
        JButton button = Theme.button(text, primary); button.setName(name); return button;
    }
    private static JPanel box(LayoutManager layout) {
        JPanel panel = Theme.rounded(layout, Color.WHITE, 28);
        panel.setBorder(new EmptyBorder(24, 26, 24, 26));
        return panel;
    }
    private static JPanel labeled(String title, JComponent input) {
        JPanel panel = Theme.panel(new BorderLayout(0, 5));
        panel.add(Theme.label(title, 12, true), BorderLayout.NORTH); panel.add(input, BorderLayout.CENTER);
        return panel;
    }
    @Override public void close() { reset(); network.shutdownNow(); }
}
