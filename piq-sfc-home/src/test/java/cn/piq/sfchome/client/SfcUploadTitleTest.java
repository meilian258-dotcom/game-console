package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcUploadTitleTest {
    @Test void requiresSuccessfulExactHashAcknowledgment(){var p=new SfcUploadTitle("newHash","草稿");assertFalse(p.afterUpload("失败","newHash","file.sfc"));assertFalse(p.afterUpload("UPLOAD_READY","newHash","file.sfc"));assertFalse(p.afterUpload("写入完成","oldHash","file.sfc"));assertTrue(p.afterUpload("写入完成","newHash","file.sfc"));}
    @Test void blankDraftNeverTriggersAnotherWrite(){assertFalse(new SfcUploadTitle("h","").afterUpload("写入完成","h","file.sfc"));assertFalse(new SfcUploadTitle("h","  ").afterUpload("写入完成","h","file.sfc"));}
    @Test void matchingTitleNeedsNoFollowUp(){assertFalse(new SfcUploadTitle("h","file.sfc").afterUpload("写入完成","h","file.sfc"));}
    @Test void followUpIsAtMostOnceAndKeepsDraft(){var p=new SfcUploadTitle("h","我的名称");assertTrue(p.afterUpload("写入完成","h","file.sfc"));assertFalse(p.afterUpload("写入完成","h","file.sfc"));assertEquals("我的名称",p.draft());}
}
