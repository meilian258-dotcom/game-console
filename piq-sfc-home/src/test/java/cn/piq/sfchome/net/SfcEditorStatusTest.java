package cn.piq.sfchome.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcEditorStatusTest {
    @Test void operationBusyPermissionAndRefreshRepliesDoNotEraseDirectoryFailure(){
        String failure=SfcEditorStatus.PREFIX+"ROM读取失败：超过扫描上限";
        for(String message:new String[]{"正在刷新服务器目录…","正在处理，请稍候","PERMISSIONS_UPDATED","名称已保存","文件任务繁忙，请稍后重试"}){
            assertEquals(failure,SfcEditorStatus.remember(failure,message));
        }
        String recovered=SfcEditorStatus.PREFIX+"ROM：1 项可用；封面：0 项可用";
        assertEquals(recovered,SfcEditorStatus.remember(failure,recovered));
    }
    @Test void compactDoesNotCutSurrogatePairsOrAllowControlCharacters(){
        assertEquals("x …",SfcEditorStatus.compact("x\n😀z",4));
        assertEquals("未知错误",SfcEditorStatus.compact(null,40));
    }
}
