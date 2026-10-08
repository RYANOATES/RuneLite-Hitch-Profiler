package runelitehitchprofiler;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.PluginPanel;

final class RuneLiteHitchProfilerPanel extends PluginPanel
{
    private static final int EVENT_MIN_HEIGHT = 260;
    private final JLabel status = new JLabel("Starting...");
    private final JTextArea summary = area(), detail = area(), compare = area(), saved = area(), logStatus = area();
    private final JButton pause = new JButton("Pause");
    private final JComboBox<String> filter = new JComboBox<>(new String[]{"All events","Hitches","Severe hitches","Map loads","Boat likely","Notes"});
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Time","Event","ms"},0) {
        @Override public boolean isCellEditable(int row,int col) { return false; }
    };
    private final JTable table = new JTable(model);
    private final List<String[]> visible = new ArrayList<>();
    private List<String[]> rows = new ArrayList<>();
    private String report = "";
    private final Graph graph = new Graph();
    private final JLabel hitchCount=new JLabel("0"), severeCount=new JLabel("0"), peakValue=new JLabel("0 ms"), loadCount=new JLabel("0");

    RuneLiteHitchProfilerPanel(Runnable pauseAction, Runnable newAction, Consumer<String> noteAction)
    {
        setLayout(new BorderLayout(0,8));
        setBorder(BorderFactory.createEmptyBorder(8,6,8,6));
        getScrollPane().getVerticalScrollBar().setUnitIncrement(18);
        JPanel head = new JPanel(new BorderLayout(0,7));
        JPanel banner=new JPanel(new BorderLayout(7,0));
        banner.add(JournalTheme.crest(),BorderLayout.WEST);
        JLabel title = new JLabel("<html>RuneLite<br>Hitch Profiler</html>");
        title.setFont(new Font(Font.SERIF,Font.BOLD,19));
        banner.add(title,BorderLayout.CENTER);
        banner.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,JournalTheme.EDGE),BorderFactory.createEmptyBorder(3,0,8,0)));
        head.add(banner,BorderLayout.NORTH); head.add(status,BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(1,2,3,0));
        pause.addActionListener(e -> pauseAction.run()); buttons.add(pause);
        JButton fresh = new JButton("New session"); fresh.addActionListener(e -> newAction.run()); buttons.add(fresh); head.add(buttons,BorderLayout.SOUTH);
        add(head,BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        JPanel live = new JPanel(new BorderLayout(0,5));
        JPanel upper = new JPanel(new BorderLayout()); upper.add(scrolling(summary,85),BorderLayout.CENTER); upper.add(graph,BorderLayout.SOUTH);
        JPanel cards=new JPanel(new GridLayout(2,2,6,6));
        cards.add(metric("HITCHES",hitchCount)); cards.add(metric("SEVERE",severeCount));
        cards.add(metric("PEAK GAP",peakValue)); cards.add(metric("MAP INTERVALS",loadCount));
        cards.setBorder(BorderFactory.createEmptyBorder(7,0,7,0));
        upper.add(cards,BorderLayout.NORTH);
        live.add(upper,BorderLayout.NORTH);
        JPanel history = new JPanel(new BorderLayout(0,4)); history.add(filter,BorderLayout.NORTH);
        filter.addActionListener(e -> rebuild());
        table.setRowHeight(25); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(65);
        table.getColumnModel().getColumn(1).setPreferredWidth(85);
        table.getColumnModel().getColumn(2).setPreferredWidth(48);
        table.setDefaultRenderer(Object.class,new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int col) {
                Component c=super.getTableCellRendererComponent(t,value,selected,focus,row,col);
                if(!selected) {
                    c.setBackground(row%2==0?JournalTheme.FIELD:JournalTheme.PANEL);
                    c.setForeground(JournalTheme.TEXT);
                    if(visible.get(row)[5].equals("severe") && col==2) {
                        c.setBackground(JournalTheme.RED); c.setForeground(Color.WHITE);
                    }
                }
                setHorizontalAlignment(col==2?SwingConstants.RIGHT:SwingConstants.LEFT);
                return c;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            int i=table.getSelectedRow();
            if(i>=0 && i<visible.size()) { String[] r=visible.get(i); detail.setText(r[0]+" | "+r[3].replace('_',' ')+"\n"+r[4]); }
        });
        JScrollPane scroll=scrolling(table,EVENT_MIN_HEIGHT);
        scroll.setName("eventHistory");
        scroll.setMinimumSize(new Dimension(100,EVENT_MIN_HEIGHT));
        history.add(scroll,BorderLayout.CENTER);
        detail.setText("Select an event for context. Boat status is an estimate from camera focus.");
        JPanel historyFooter=new JPanel(new BorderLayout(0,4));
        historyFooter.add(resizeHandle(scroll),BorderLayout.NORTH);
        historyFooter.add(scrolling(detail,65),BorderLayout.CENTER);
        history.add(historyFooter,BorderLayout.SOUTH); live.add(history,BorderLayout.CENTER);
        tabs.addTab("Live",live); tabs.addTab("Compare",scrolling(compare,440)); tabs.addTab("Saved",scrolling(saved,440));
        add(tabs,BorderLayout.CENTER);
        // GridLayout made every control as tall as the longest wrapped log text.
        JPanel footer=new JPanel(new GridBagLayout());
        GridBagConstraints footerRow = new GridBagConstraints();
        footerRow.gridx=0; footerRow.gridy=GridBagConstraints.RELATIVE;
        footerRow.weightx=1; footerRow.fill=GridBagConstraints.HORIZONTAL;
        footerRow.insets=new Insets(2,0,2,0);
        JTextField note=new JTextField(); note.setToolTipText("Add a note: route, renderer or settings used (up to 160 characters)");
        note.setPreferredSize(new Dimension(200,26));
        footer.add(note,footerRow);
        JPanel actions=new JPanel(new GridLayout(1,2,4,0));
        JButton mark=new JButton("Add note"); mark.addActionListener(e -> { noteAction.accept(note.getText()); note.setText(""); }); actions.add(mark);
        JButton copy=new JButton("Copy report"); copy.addActionListener(e -> {
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report+"\n"+logStatus.getText()),null); }
            catch(RuntimeException ex) { detail.setText("Clipboard unavailable. Try again."); }
        }); actions.add(copy); footer.add(actions,footerRow); footer.add(scrolling(logStatus,55),footerRow); add(footer,BorderLayout.SOUTH);
        JournalTheme.apply(getScrollPane());
        title.setForeground(JournalTheme.GOLD); status.setForeground(JournalTheme.MUTED);
        for(JLabel value:new JLabel[]{hitchCount,severeCount,peakValue,loadCount}) value.setForeground(JournalTheme.GOLD);
        severeCount.setForeground(new Color(255,155,136));
        table.setSelectionBackground(JournalTheme.EDGE); table.setSelectionForeground(Color.WHITE);
        table.setGridColor(JournalTheme.EDGE); table.setShowVerticalLines(false);
        table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int col) {
                JLabel label=(JLabel)super.getTableCellRendererComponent(t,value,selected,focus,row,col);
                label.setBackground(JournalTheme.PANEL); label.setForeground(JournalTheme.GOLD);
                label.setBorder(BorderFactory.createEmptyBorder(6,4,6,4)); return label;
            }
        });
    }

    private JPanel metric(String title,JLabel value)
    {
        JPanel card=new JPanel(new BorderLayout(0,2));
        card.setName("journalMetric");
        card.setBorder(JournalTheme.border(7));
        JLabel label=new JLabel(title); label.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,9));
        value.setFont(new Font(Font.SERIF,Font.BOLD,20));
        card.add(label,BorderLayout.NORTH); card.add(value,BorderLayout.CENTER);
        return card;
    }

    void metrics(long hitches,long severe,double peak,long loads)
    {
        hitchCount.setText(Long.toString(hitches)); severeCount.setText(Long.toString(severe));
        peakValue.setText(String.format(java.util.Locale.ROOT,"%.0f ms",peak)); loadCount.setText(Long.toString(loads));
    }

    private JComponent resizeHandle(JScrollPane events)
    {
        JLabel handle=new JLabel("Drag to resize",SwingConstants.CENTER);
        handle.setName("eventResizeHandle");
        handle.setPreferredSize(new Dimension(210,20));
        handle.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        handle.setToolTipText("Drag up or down. Minimum height: 260 pixels.");
        handle.setBorder(BorderFactory.createMatteBorder(1,0,1,0,JournalTheme.EDGE));
        MouseAdapter drag=new MouseAdapter() {
            private int startY, startHeight;
            private boolean dragging;
            @Override public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                dragging=true;
                startY=event.getYOnScreen();
                startHeight=Math.max(EVENT_MIN_HEIGHT,events.getHeight());
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (!dragging) return;
                int height=Math.max(EVENT_MIN_HEIGHT,startHeight+event.getYOnScreen()-startY);
                events.setPreferredSize(new Dimension(events.getPreferredSize().width,height));
                RuneLiteHitchProfilerPanel.this.revalidate();
            }
            @Override public void mouseReleased(MouseEvent event) { dragging=false; }
        };
        handle.addMouseListener(drag);
        handle.addMouseMotionListener(drag);
        return handle;
    }

    static JScrollPane scrolling(JComponent content, int height)
    {
        JScrollPane pane = new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setPreferredSize(new Dimension(210,height));
        pane.setMinimumSize(new Dimension(100,Math.min(55,height)));
        pane.getVerticalScrollBar().setUnitIncrement(18);
        // Nested panes otherwise swallow wheel events at their boundaries.
        pane.setWheelScrollingEnabled(false);
        pane.addMouseWheelListener(event -> {
            JScrollPane target=pane;
            while (target!=null)
            {
                JScrollBar bar=target.getVerticalScrollBar();
                double rotation=event.getPreciseWheelRotation();
                int direction=rotation<0?-1:1;
                int step=event.getScrollType()==MouseWheelEvent.WHEEL_BLOCK_SCROLL
                    ? bar.getBlockIncrement(direction) : bar.getUnitIncrement(direction)*event.getScrollAmount();
                int delta=(int)Math.ceil(Math.abs(rotation)*Math.max(1,step))*direction;
                int before=bar.getValue();
                bar.setValue(before+delta);
                if(bar.getValue()!=before) break;
                target=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,target.getParent());
            }
            event.consume();
        });
        return pane;
    }

    private static JTextArea area()
    {
        JTextArea a=new JTextArea(); a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true);
        // Read-only live output must never scroll the enclosing RuneLite sidebar
        // to reveal its caret when a document is replaced by a refresh.
        DefaultCaret caret = new DefaultCaret() {
            @Override protected void adjustVisibility(Rectangle location) { }
        };
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        a.setCaret(caret);
        a.setOpaque(false); a.setFont(new JLabel().getFont()); return a;
    }
    private static void setTextIfChanged(JTextArea area, String text)
    {
        if (!area.getText().equals(text)) area.setText(text);
    }
    void savedHistory(String text) { if(!saved.getText().equals(text)) saved.setText(text); }
    void update(String state,String report,String previous,List<String[]> rows,String logging,boolean paused)
    {
        status.setText(state); pause.setText(paused?"Resume":"Pause"); this.report=report;
        boolean changed = this.rows.size() != rows.size();
        if (!changed) for (int i=0;i<rows.size();i++) if (!java.util.Arrays.equals(this.rows.get(i),rows.get(i))) { changed=true; break; }
        this.rows=rows;
        String[] lines=report.split("\n");
        setTextIfChanged(summary, lines.length>5?lines[3]+"\n"+lines[4]+"\n"+lines[5]:report);
        setTextIfChanged(compare, "Compare equal-length runs on the same route. Add notes for settings; change one setting at a time.\n\nCURRENT\n"+report+"\nPREVIOUS SESSION\n"+(previous.isEmpty()?"Use New session after your first run to retain a comparison here.":previous));
        setTextIfChanged(logStatus, logging); logStatus.setToolTipText(logging);
        if (changed) { rebuild(); graph.repaint(); }
    }
    private void rebuild()
    {
        int selected=table.getSelectedRow(); String[] old=selected>=0&&selected<visible.size()?visible.get(selected):null;
        visible.clear(); model.setRowCount(0); String choice=(String)filter.getSelectedItem();
        for(String[] row:rows)
        {
            if(choice.equals("Hitches")&&!row[1].equals("HITCH"))continue;
            if(choice.equals("Severe hitches")&&!row[5].equals("severe"))continue;
            if(choice.equals("Map loads")&&!row[1].equals("LOAD_INTERVAL"))continue;
            if(choice.equals("Boat likely")&&!row[3].equals("BOAT_LIKELY"))continue;
            if(choice.equals("Notes")&&!row[1].equals("NOTE"))continue;
            visible.add(row); model.addRow(new Object[]{row[0],row[1].equals("HITCH")?"Hitch":row[1].equals("NOTE")?"Note":"Load interval",row[1].equals("NOTE")?"":row[2]});
            if(old!=null&&old[0].equals(row[0])&&old[1].equals(row[1])&&old[2].equals(row[2])) table.setRowSelectionInterval(visible.size()-1,visible.size()-1);
        }
    }
    private final class Graph extends JPanel
    {
        Graph() { setPreferredSize(new Dimension(200,55)); setToolTipText("Last 40 recorded events, oldest to newest; bar height is duration, capped at 500 ms. Red: severe hitch. Blue: partial map interval."); }
        @Override protected void paintComponent(Graphics g)
        {
            super.paintComponent(g); int n=Math.min(40,rows.size()); if(n==0)return;
            for(int i=0;i<n;i++) { String[] r=rows.get(n-1-i); int h=(int)Math.min(45,Double.parseDouble(r[2])*45/500);
                g.setColor(r[1].equals("LOAD_INTERVAL")?new Color(85,175,215):r[5].equals("severe")?new Color(240,110,90):new Color(230,185,85));
                g.fillRect(i*getWidth()/n,50-h,Math.max(1,getWidth()/n-1),Math.max(1,h)); }
        }
    }
}
