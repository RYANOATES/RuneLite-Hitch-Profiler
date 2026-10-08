package runelitehitchprofiler;

import org.junit.Test;
import static org.junit.Assert.*;

public class HitchSessionTest
{
    @Test public void loadInsideLongFrameIsNotMissed()
    {
        HitchSession s = new HitchSession(50,150);
        s.signal(1_100_000_000L);
        HitchSession.Entry e = s.frame(1_000_000_000L, 1_300_000_000L, 0, "BOAT_LIKELY");
        assertEquals(300,e.ms,.001);
        assertTrue(e.nearLoad);
        assertEquals(1,s.total.severe);
    }
    @Test public void laterLoadSignalCorrelatesBeforeFinalization()
    {
        HitchSession s = new HitchSession(50,150);
        HitchSession.Entry e=s.frame(1_000_000_000L,1_060_000_000L,0,"UNKNOWN");
        assertTrue(s.drain(1_500_000_000L,false).isEmpty());
        s.signal(1_700_000_000L);
        assertEquals(1,s.drain(2_100_000_000L,false).size());
        assertTrue(e.nearLoad);
        assertEquals(1,s.correlated);
        assertTrue(s.drain(3_000_000_000L,false).isEmpty());
    }
    @Test public void unrelatedLoadDoesNotExplainHitch()
    {
        HitchSession s = new HitchSession(50,150);
        s.signal(1_000_000_000L);
        HitchSession.Entry e=s.frame(4_000_000_000L,4_100_000_000L,0,"LAND_LIKELY");
        s.drain(6_000_000_000L,false);
        assertFalse(e.nearLoad); assertEquals(0,s.correlated);
    }
    @Test public void thresholdsAndStatisticsRemainIndependentOfHistoryLimit()
    {
        HitchSession s = new HitchSession(50,150);
        for(int i=0;i<1000;i++) {
            long start=i*200_000_000L;
            s.frame(start,start+50_000_000L,0,"BOAT_LIKELY");
            s.drain(start+50_000_000L,false);
        }
        assertEquals(300,s.history.size()); assertEquals(1000,s.total.hitches);
        assertEquals(50,s.total.percentile(.95));
        assertEquals(1000,s.contexts.get("BOAT_LIKELY").frames);
        assertEquals(0,s.contexts.get("LAND_LIKELY").frames);
    }
    @Test public void normalFramesAndEmptySessionAreHandled()
    {
        HitchSession s=new HitchSession(50,150);
        assertEquals(0,s.total.percentile(.95));
        assertNull(s.frame(0,16_000_000L,0,"UNKNOWN"));
        assertEquals(1,s.total.frames); assertEquals(0,s.total.hitches);
        assertFalse(s.report().contains("NaN"));
    }
    @Test public void csvRoundTripsQuotedNotesAndRejectsTruncatedRows()
    {
        String row=HitchLog.row("session",0,"NOTE","","","","","","GPU, \"test\"\nnext").trim();
        assertEquals(11,HitchLog.parse(row).size());
        assertEquals("GPU, \"test\" next",HitchLog.parse(row).get(9));
        assertTrue(HitchLog.parse("\"unfinished").isEmpty());
    }

    @Test public void severeHitchColoursOnlyTheDurationCell()
    {
        String row=HitchLog.row("session",0,"HITCH","BOAT_LIKELY","175.0","","","true","", "");
        String html=HitchLog.htmlRow(row,true);
        assertTrue(html.contains("<td class=\"severe\">175.0</td>"));
        assertEquals(1,html.split("class=\"severe\"",-1).length-1);
        assertTrue(HitchLog.HTML_HEADER.contains("background:#c62828"));
        assertFalse(HitchLog.htmlRow(row,false).contains("class=\"severe\""));
        assertEquals(HitchLog.parse(row.trim()),HitchLog.readHtmlRow(html));
    }

    @Test public void severeLocationsAreStoredForSeparateReportSectionOnly()
    {
        String location = "3200, 3201, plane 0";
        String severe = HitchLog.row("session",0,"HITCH","BOAT_LIKELY","175.0","","","true","",location);
        String ordinary = HitchLog.row("session",0,"HITCH","BOAT_LIKELY","75.0","","","false","","");
        String severeHtml = HitchLog.htmlRow(severe,true);
        String ordinaryHtml = HitchLog.htmlRow(ordinary,false);
        assertEquals(location,HitchLog.readHtmlRow(severeHtml).get(10));
        assertTrue(severeHtml.contains("location-data\" hidden>3200, 3201, plane 0"));
        assertTrue(ordinaryHtml.contains("location-data\" hidden></td>"));
        assertTrue(HitchLog.HTML_HEADER.contains("Severe hitch locations"));
        assertTrue(HitchLog.HTML_HEADER.contains("r.red&&locationPattern.test(r.location)"));
    }

    @Test public void htmlNotesAreEscapedAndNonHitchesAreNotRed()
    {
        String note="<script>alert('test')</script> & \"quoted\", text";
        String row=HitchLog.row("session",0,"NOTE","","","","","",note);
        String html=HitchLog.htmlRow(row,true);
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("class=\"severe\""));
        assertEquals(note,HitchLog.readHtmlRow(html).get(9));
        assertTrue(HitchLog.readHtmlRow("<!--record:invalid! -->").isEmpty());
    }

    @Test public void themeUpgradePreservesRecordedRowsAndIsIdempotent() throws Exception
    {
        String row=HitchLog.htmlRow(HitchLog.row("session",0,"HITCH","BOAT_LIKELY","250","","","true",""),true);
        String old="<!doctype html>\n<!-- sailing-hitch-finder-html-v1 -->\n<html><body><table><tbody>\n"+row+HitchLog.FOOTER;
        String upgraded=HitchLog.upgradeReport(old);
        assertEquals(HitchLog.HTML_HEADER+row+HitchLog.FOOTER,upgraded);
        assertEquals(upgraded,HitchLog.upgradeReport(upgraded));
        assertTrue(upgraded.contains("id=\"search\""));
        assertTrue(upgraded.contains("<svg"));
    }

    @Test(expected=java.io.IOException.class) public void themeUpgradeRejectsIncompleteLog() throws Exception
    {
        HitchLog.upgradeReport(HitchLog.HTML_HEADER);
    }
}
