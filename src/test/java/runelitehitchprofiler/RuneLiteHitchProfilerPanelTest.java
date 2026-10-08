package runelitehitchprofiler;

import java.awt.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuneLiteHitchProfilerPanelTest
{
    @Test public void themedSidebarRendersAtRuneLiteWidth() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteHitchProfilerPanel panel=new RuneLiteHitchProfilerPanel(() -> {}, () -> {}, value -> {});
            panel.update("Recording: boat likely","Title\nSession\nThresholds\n2.1 min sampled; 8 hitches; 2 severe; peak 285 ms\nCompleted map intervals: 5\nHitches near load signals: 6","",
                java.util.Arrays.asList(
                    new String[]{"20:42:16","HITCH","285.0","BOAT_LIKELY","Near map-load signal","severe"},
                    new String[]{"20:42:15","LOAD_INTERVAL","27.4","BOAT_LIKELY","Partial load interval","normal"},
                    new String[]{"20:41:59","HITCH","68.2","BOAT_LIKELY","No nearby map-load signal","normal"}),
                "Coloured log: sailing-hitches.html",false);
            panel.metrics(8,2,285,5);
            panel.setSize(225,panel.getPreferredSize().height);layout(panel);
            JScrollPane history=(JScrollPane)findNamed(panel,"eventHistory");
            JTable table=(JTable)history.getViewport().getView();
            Component red=table.prepareRenderer(table.getCellRenderer(0,2),0,2);
            assertEquals(JournalTheme.RED,red.getBackground());
            java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(panel.getWidth(),panel.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics();panel.printAll(g);g.dispose();
            try { javax.imageio.ImageIO.write(image,"png",new java.io.File(System.getProperty("java.io.tmpdir"),"sailing-sidebar-preview.png")); }
            catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
        });
    }

    @Test public void eventBoxResizesVerticallyAndKeepsMinimumAcrossRefreshes() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteHitchProfilerPanel panel=new RuneLiteHitchProfilerPanel(() -> {}, () -> {}, value -> {});
            panel.setSize(225,panel.getPreferredSize().height); layout(panel);
            JComponent handle=(JComponent)findNamed(panel,"eventResizeHandle");
            JScrollPane events=(JScrollPane)findNamed(panel,"eventHistory");
            int width=events.getPreferredSize().width;
            int original=events.getHeight();
            dragEvent(handle,java.awt.event.MouseEvent.MOUSE_PRESSED,400);
            dragEvent(handle,java.awt.event.MouseEvent.MOUSE_DRAGGED,550);
            assertEquals(original+150,events.getPreferredSize().height);
            assertEquals(width,events.getPreferredSize().width);
            panel.update("Recording","a\nb\nc\nd\ne\nf","",Collections.emptyList(),"CSV",false);
            assertEquals(original+150,events.getPreferredSize().height);
            dragEvent(handle,java.awt.event.MouseEvent.MOUSE_DRAGGED,0);
            assertEquals(260,events.getPreferredSize().height);
            assertEquals(260,events.getMinimumSize().height);
            dragEvent(handle,java.awt.event.MouseEvent.MOUSE_RELEASED,0);
            dragEvent(handle,java.awt.event.MouseEvent.MOUSE_DRAGGED,800);
            assertEquals(260,events.getPreferredSize().height);
        });
    }

    private static Component findNamed(Container parent,String name)
    {
        for(Component child:parent.getComponents()) {
            if(name.equals(child.getName())) return child;
            if(child instanceof Container) { Component found=findNamed((Container)child,name); if(found!=null)return found; }
        }
        return null;
    }

    private static void dragEvent(JComponent handle,int id,int screenY)
    {
        handle.dispatchEvent(new java.awt.event.MouseEvent(handle,id,System.currentTimeMillis(),
            java.awt.event.InputEvent.BUTTON1_DOWN_MASK,5,5,100,screenY,1,false,
            id==java.awt.event.MouseEvent.MOUSE_DRAGGED?java.awt.event.MouseEvent.NOBUTTON:java.awt.event.MouseEvent.BUTTON1));
    }

    @Test public void largeReportsDoNotGrowPanelOrNoteField() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteHitchProfilerPanel panel=new RuneLiteHitchProfilerPanel(() -> {}, () -> {}, value -> {});
            int before=panel.getPreferredSize().height;
            String huge="A long diagnostic line with several words\n".repeat(2000);
            panel.update("Recording",huge,huge,Collections.emptyList(),huge,false);
            panel.savedHistory(huge);
            panel.setSize(225,panel.getPreferredSize().height);
            layout(panel);
            assertEquals(before,panel.getPreferredSize().height);
            assertTrue("Panel remains bounded",panel.getPreferredSize().height<1000);
            checkNoteHeight(panel);
        });
    }

    @Test public void wheelScrollsInnerPaneThenOuterPaneAtBoundary() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            JPanel tall=new JPanel(); tall.setPreferredSize(new Dimension(180,2000));
            JScrollPane inner=RuneLiteHitchProfilerPanel.scrolling(tall,100);
            JPanel content=new JPanel(null); content.setPreferredSize(new Dimension(225,2000)); content.add(inner);
            JScrollPane outer=new JScrollPane(content);
            outer.setSize(242,400); outer.doLayout();
            content.setSize(225,2000); inner.setBounds(0,0,210,100); inner.doLayout();
            inner.getViewport().setViewSize(tall.getPreferredSize());
            outer.getViewport().setViewSize(content.getPreferredSize());
            inner.getVerticalScrollBar().setValue(100);
            outer.getVerticalScrollBar().setValue(100);
            wheel(inner,1);
            assertTrue(inner.getVerticalScrollBar().getValue()>100);
            assertEquals(100,outer.getVerticalScrollBar().getValue());
            inner.getVerticalScrollBar().setValue(inner.getVerticalScrollBar().getMaximum());
            wheel(inner,1);
            assertTrue(outer.getVerticalScrollBar().getValue()>100);
            inner.getVerticalScrollBar().setValue(0);
            int previous=outer.getVerticalScrollBar().getValue();
            wheel(inner,-1);
            assertTrue(outer.getVerticalScrollBar().getValue()<previous);
        });
    }

    private static void wheel(JScrollPane pane,int direction)
    {
        pane.dispatchEvent(new java.awt.event.MouseWheelEvent(pane,
            java.awt.event.MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,5,5,0,false,
            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,3,direction));
    }

    private static void checkNoteHeight(Container container)
    {
        for(Component child:container.getComponents())
        {
            if(child instanceof JTextField) assertTrue("Note field must stay compact",child.getHeight()<=30);
            if(child instanceof Container) checkNoteHeight((Container)child);
        }
    }

    @Test public void liveRefreshDoesNotScrollReadOnlyTextIntoView() throws Exception
    {
        AtomicInteger scrollRequests = new AtomicInteger();
        RuneLiteHitchProfilerPanel[] panel = new RuneLiteHitchProfilerPanel[1];
        JScrollPane[] scroll = new JScrollPane[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new RuneLiteHitchProfilerPanel(() -> {}, () -> {}, value -> {});
            JPanel host = new JPanel(new BorderLayout()) {
                @Override public void scrollRectToVisible(Rectangle rect) { scrollRequests.incrementAndGet(); }
            };
            host.add(panel[0]);
            host.setPreferredSize(new Dimension(225, 1800));
            scroll[0] = new JScrollPane(host);
            scroll[0].setSize(250, 400);
            scroll[0].doLayout();
            host.setSize(225, 1800);
            layout(host);
            scroll[0].getViewport().setViewPosition(new Point(0, 150));
            // Exercise the caret's deferred visibility path before a live update.
            moveCaretsToEnd(panel[0]);
            for (int i = 0; i < 10; i++)
                panel[0].update("Recording", "Title\nSession\nThreshold\nFrames " + i
                    + "\nLoads 3\nHitches 2", "", Collections.emptyList(),
                    "CSV: C:/example/long/path/sailing-hitches.csv", false);
            panel[0].savedHistory("Older session\n".repeat(100));
        });
        // Drain deferred caret work; this used to scroll the enclosing sidebar.
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(0, scrollRequests.get());
            assertEquals(150, scroll[0].getViewport().getViewPosition().y);
        });
    }

    private static void layout(Container container)
    {
        container.doLayout();
        for (Component child : container.getComponents())
            if (child instanceof Container) layout((Container) child);
    }

    private static void moveCaretsToEnd(Container container)
    {
        for (Component child : container.getComponents())
        {
            if (child instanceof JTextArea)
            {
                JTextArea text = (JTextArea) child;
                text.setText("Sample text\n".repeat(100));
                text.setCaretPosition(text.getDocument().getLength());
            }
            if (child instanceof Container) moveCaretsToEnd((Container) child);
        }
    }
}
