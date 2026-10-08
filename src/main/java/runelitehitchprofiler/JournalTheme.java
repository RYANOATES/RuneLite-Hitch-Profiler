package runelitehitchprofiler;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;

/** Theme stays local to this panel; never changes RuneLite's global UI defaults. */
final class JournalTheme
{
    static final Color BACKGROUND=new Color(24,27,25), PANEL=new Color(40,38,31),
        FIELD=new Color(29,32,27), GOLD=new Color(219,181,108), TEXT=new Color(232,220,196),
        MUTED=new Color(172,161,139), EDGE=new Color(100,84,56), RED=new Color(198,40,40);
    private JournalTheme() { }
    static Border border(int padding)
    { return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE),BorderFactory.createEmptyBorder(padding,padding,padding,padding)); }

    static void apply(Component component)
    {
        component.setBackground(BACKGROUND);
        component.setForeground(TEXT);
        if(component instanceof Container)
            for(Component child:((Container)component).getComponents()) apply(child);
        if("journalMetric".equals(component.getName())) component.setBackground(PANEL);
        if(component instanceof JTextArea)
        {
            JTextArea text=(JTextArea)component;
            text.setForeground(TEXT); text.setBackground(FIELD); text.setOpaque(true);
            text.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
            text.setSelectionColor(EDGE); text.setSelectedTextColor(Color.WHITE);
        }
        if(component instanceof JTextField)
        {
            JTextField field=(JTextField)component;
            field.setBackground(FIELD); field.setBorder(border(4)); field.setCaretColor(GOLD);
        }
        if(component instanceof JButton)
        {
            JButton button=(JButton)component;
            button.setBackground(PANEL); button.setForeground(GOLD); button.setBorder(border(6));
            button.setOpaque(true); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setFont(button.getFont().deriveFont(Font.PLAIN,11f));
        }
        if(component instanceof JComboBox)
        { component.setBackground(PANEL); component.setForeground(GOLD); }
        if(component instanceof JScrollPane)
        {
            JScrollPane pane=(JScrollPane)component;
            pane.setBorder(BorderFactory.createLineBorder(EDGE)); pane.getViewport().setBackground(FIELD);
        }
        if(component instanceof JScrollBar)
        {
            JScrollBar bar=(JScrollBar)component;
            bar.setPreferredSize(new Dimension(11,11));
            bar.setUI(new BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() { thumbColor=EDGE; trackColor=FIELD; }
                @Override protected JButton createDecreaseButton(int orientation) { return arrow(); }
                @Override protected JButton createIncreaseButton(int orientation) { return arrow(); }
                private JButton arrow() { JButton b=new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
            });
        }
        if(component instanceof JTabbedPane)
        {
            JTabbedPane tabs=(JTabbedPane)component;
            tabs.setForeground(GOLD);
            tabs.setUI(new BasicTabbedPaneUI() {
                @Override protected void installDefaults() { super.installDefaults(); lightHighlight=EDGE; shadow=EDGE; darkShadow=EDGE; focus=GOLD; }
                @Override protected void paintTabBackground(Graphics g,int p,int i,int x,int y,int w,int h,boolean selected)
                { g.setColor(selected?PANEL:BACKGROUND); g.fillRect(x,y,w,h); }
                @Override protected void paintContentBorder(Graphics g,int placement,int selected)
                { /* Each content section has its own brass border. */ }
            });
        }
    }

    /** One outline shared by the panel and navigation icon, in a 120-unit view box. */
    static void paintCrest(Graphics2D g, float strokeWidth)
    {
        Path2D pulse=new Path2D.Double();
        pulse.moveTo(84,96); pulse.lineTo(78,96); pulse.lineTo(72,90);
        pulse.lineTo(68,96); pulse.lineTo(64,108); pulse.lineTo(56,80);
        pulse.lineTo(50,96); pulse.lineTo(36,96);

        Path2D shield=new Path2D.Double();
        shield.moveTo(60,6); shield.lineTo(106,24); shield.lineTo(106,64);
        shield.curveTo(106,78,97,88,84,96);
        shield.append(pulse,true);
        shield.curveTo(23,88,14,78,14,64); shield.lineTo(14,24); shield.closePath();
        g.setColor(new Color(34,42,37)); g.fill(shield);
        g.setStroke(new BasicStroke(strokeWidth,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,4f));
        g.setColor(GOLD); g.draw(shield);
        g.setColor(new Color(139,189,180)); g.draw(pulse);

        g.setColor(new Color(227,204,160));
        g.fillRoundRect(31,50,9,22,2,2); g.fillRoundRect(43,43,9,29,2,2);
        g.fillRoundRect(55,48,9,24,2,2); g.fillRoundRect(67,35,9,37,2,2);
        g.setColor(RED); g.fillRoundRect(79,44,9,28,2,2);
    }

    static JComponent crest()
    {
        JComponent emblem=new JComponent() {
            @Override protected void paintComponent(Graphics graphics)
            {
                Graphics2D g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                double scale=Math.min(getWidth(),getHeight())/120.0;
                g.translate((getWidth()-120*scale)/2,(getHeight()-120*scale)/2); g.scale(scale,scale);
                paintCrest(g,3f);
                g.dispose();
            }
        };
        emblem.setPreferredSize(new Dimension(60,66));
        emblem.setToolTipText("RuneLite Hitch Profiler — frame-time monitor");
        return emblem;
    }
}
