import unittest
from check_large_lcd_presentation import runtime_frame_branch

SOURCE='''private static void drawFace(VertexConsumer consumer) {
 if (displayStyle == cn.piq.fcarcade.layout.ArcadeDisplayStyle.DUAL_CABINET
 || displayStyle == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_LARGE_LCD_TV
 || displayStyle == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_VINTAGE_TV) {
 var quad = cn.piq.fcarcade.layout.LargeLcdPresentation.frame(turns);
 rocketVertex(consumer, pose, quad.lowerMaxX(), quad.normal(), 0, 1);
 rocketVertex(consumer, pose, quad.lowerMinX(), quad.normal(), 1, 1);
 rocketVertex(consumer, pose, quad.upperMinX(), quad.normal(), 1, 0);
 rocketVertex(consumer, pose, quad.upperMaxX(), quad.normal(), 0, 0);
 return;
 }}'''

class LargeLcdPresentationSourceTests(unittest.TestCase):
    def test_shared_four_style_branch_is_accepted(self):
        self.assertTrue(runtime_frame_branch(SOURCE))

    def test_package_scope_draw_face_keeps_same_full_uv_contract(self):
        self.assertTrue(runtime_frame_branch(SOURCE.replace('private static','static')))

    def test_correct_neighbor_method_cannot_hide_wrong_draw_face(self):
        broken=SOURCE.replace('LargeLcdPresentation','WideLcdPresentation')
        self.assertFalse(runtime_frame_branch(broken+SOURCE.replace('drawFace','unrelated')))

    def test_method_named_differently_is_not_a_draw_face(self):
        self.assertFalse(runtime_frame_branch(SOURCE.replace('drawFace','drawSomeOtherFace')))

    def test_wrong_style_is_rejected(self):
        self.assertFalse(runtime_frame_branch(SOURCE.replace('HOME_LARGE_LCD_TV','HOME_WRONG_LCD_TV')))

    def test_wrong_frame_is_rejected(self):
        self.assertFalse(runtime_frame_branch(SOURCE.replace('LargeLcdPresentation','WideLcdPresentation')))

    def test_cropped_uv_is_rejected(self):
        self.assertFalse(runtime_frame_branch(SOURCE.replace('quad.normal(), 0, 1','quad.normal(), .1, 1')))

    def test_mirrored_uv_is_rejected(self):
        self.assertFalse(runtime_frame_branch(SOURCE.replace('quad.lowerMaxX()', 'quad.lowerMinX()')))

    def test_commented_good_branch_does_not_mask_bad_code(self):
        self.assertFalse(runtime_frame_branch('/*'+SOURCE+'*/'+SOURCE.replace('LargeLcdPresentation','WideLcdPresentation')))

    def test_elsewhere_frame_does_not_mask_wrong_branch(self):
        self.assertFalse(runtime_frame_branch(SOURCE.replace('LargeLcdPresentation','WideLcdPresentation')+'void ignored(){LargeLcdPresentation.frame(0);}'))

if __name__=='__main__':unittest.main()
