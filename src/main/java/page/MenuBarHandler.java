package page;

import io.BCUWriter;
import main.MainBCU;
import main.Opts;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public class MenuBarHandler {
    private static final JMenuBar bar = new JMenuBar();

    private static final List<JMenuItem> fileItems = new ArrayList<>();

    public static JMenuBar getBar() {
        return bar;
    }

    public static void initialize() {
        setFileItems();
        MainFrame.F.setJMenuBar(bar);
    }

    private static void setFileItems() {
        JMenu menu = new JMenu("File");
        JMenu history = new JMenu("History");

        bar.add(menu);
        bar.add(history);

        JMenu background = new JMenu("Background");
        JMenuItem chooseBackground = new JMenuItem("Choose GIF or MP4…");
        chooseBackground.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter("Animated backgrounds (*.gif, *.mp4)", "gif", "mp4"));
            if (chooser.showOpenDialog(MainFrame.F) == JFileChooser.APPROVE_OPTION)
                MainFrame.F.animatedBackground.load(chooser.getSelectedFile(), true);
        });
        JMenuItem clearBackground = new JMenuItem("Clear background");
        clearBackground.addActionListener(e -> MainFrame.F.animatedBackground.clear());
        background.add(chooseBackground);
        background.add(clearBackground);
        bar.add(background);

        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

        JMenuItem save = new JMenuItem("Save All");
        save.setAccelerator(KeyStroke.getKeyStroke('S', shortcut));
        save.addActionListener(e -> {
            BCUWriter.writeData();
            Opts.pop("Successfully saved data.", "Save Confirmation");
        });

        JMenuItem back = new JMenuItem("Go Back");
        back.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        back.addActionListener(e -> {
            if(MainFrame.getPanel().getBackButton() != null) {
                MainFrame.getPanel().getBackButton().doClick();
            }
        });

        save.setEnabled(false);
        back.setEnabled(false);

        menu.add(save);
        history.add(back);

        fileItems.add(save);
        fileItems.add(back);
    }

    public static JMenuItem getFileItem(String n) {
        for (JMenuItem i : fileItems) {
            if (i.getText().equals(n))
                return i;
        }

        if(MainBCU.loaded) {
            System.out.println("Missing menu item: " + n);
        }

        return null;
    }
}
