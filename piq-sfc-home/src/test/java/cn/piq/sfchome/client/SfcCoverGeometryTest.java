package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCoverGeometryTest {
    @Test void itemLabelFitsUserRecessAndPreservesTwoToOne(){var f=SfcCoverGeometry.label(false);assertTrue(f.left()>6.075/16&&f.right()<9.925/16);assertEquals((.845+6.55)/16,f.bottom(),1e-7);assertEquals((2.58+6.55)/16,f.top(),1e-7);assertEquals(7.5965/16,f.z(),1e-7);assertTrue(f.z()<7.5985/16);assertEquals(2,(f.right()-f.left())/(f.top()-f.bottom()),1e-6);}
    @Test void insertedCoverUsesExactlyUserTranslationWithoutScale(){var a=SfcCoverGeometry.label(false);var b=SfcCoverGeometry.label(true);assertEquals(a.left(),b.left());assertEquals(a.right(),b.right());assertEquals(a.bottom()+(2.18-6.55)/16,b.bottom(),1e-7);assertEquals(a.z()+3.711/16,b.z(),1e-7);assertEquals(2,(b.right()-b.left())/(b.top()-b.bottom()),1e-6);}
}
