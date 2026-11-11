package chatapp.client;

import chatapp.client.ui.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public final class ClientMain {
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Swing 화면을 표시할 수 있는 데스크톱 환경에서 실행해 주세요.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            ChatPanel panel = new ChatPanel();
            JFrame frame = new JFrame("ChatApp · 김지원 프론트엔드 1주차");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setContentPane(panel); frame.pack();
            frame.setMinimumSize(new Dimension(960, 680));
            frame.setLocationRelativeTo(null);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { panel.close(); frame.dispose(); }
            });
            frame.setVisible(true);
        });
    }
}
