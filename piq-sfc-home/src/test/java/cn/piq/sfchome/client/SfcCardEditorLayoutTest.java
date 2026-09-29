package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCardEditorLayoutTest {
    @Test void minimumGuiSupportsAFullRowBelowMetadataControls(){var l=SfcCardEditorLayout.of(320,240);assertTrue(l.supported());assertEquals(1,l.rows());assertTrue(l.width()-110>=180);assertTrue(l.listY()>=l.y()+121);assertTrue((l.width()-42)/4>=65);}
    @Test void undersizedGuiDoesNotCreateOffscreenControls(){assertFalse(SfcCardEditorLayout.of(319,240).supported());assertFalse(SfcCardEditorLayout.of(320,239).supported());}
    @Test void allSupportedSizesKeepRowsAndFooterSeparated(){for(int w:new int[]{320,360,480,640,1280})for(int h:new int[]{240,256,300,400,480,720}){var l=SfcCardEditorLayout.of(w,h);assertTrue(l.x()>=0&&l.y()>=0);assertTrue(l.x()+l.width()<=w);assertTrue(l.y()+l.height()<=h);assertTrue(l.listY()+l.rows()*22<=l.pageY());assertTrue(l.pageY()+18<=l.actionY());assertTrue(l.actionY()+20<=l.statusY());assertTrue(l.statusY()+20<=l.y()+l.height());}}
}
