package cn.piq.fcarcade.client.ui;

import java.util.Locale;

/** Presentation codes only: no retry, CRC, transport or core policy is changed. */
public enum NetplayFailureCode {
    STATE_MISMATCH("联机状态校验不一致，已安全停止，请复制诊断"),
    SEED_RESTORE("联机初始状态无法恢复，请复制诊断"),
    INPUT_SEQUENCE("联机输入或数据顺序异常，请重新加入"),
    DISCONNECTED("本局联机连接已结束"),
    TIMEOUT("Netplay 连接或同步超时，请重新加入"),
    PROTOCOL("Netplay 组件不匹配，请核对配套版本"),
    DEPENDENCY("运行环境不可用，请检查本机依赖"),
    DISK_FULL("磁盘空间不足，请清理运行目录所在磁盘"),
    RUNTIME("Netplay 运行已停止，请复制诊断");

    private final String summary;
    NetplayFailureCode(String summary){this.summary=summary;}
    public String summary(){return summary;}

    /** Classify the actual reason, never the combined startup/native diagnostic tail. */
    public static NetplayFailureCode classify(Throwable failure){
        Throwable current=failure;
        for(int depth=0;current!=null&&depth<8;depth++,current=current.getCause()){
            if(current instanceof Failure known)return known.code();
            if(current instanceof LinkageError)return DEPENDENCY;
            NetplayFailureCode code=classifyReason(current.getMessage());
            if(code!=RUNTIME)return code;
        }
        return RUNTIME;
    }
    public static NetplayFailureCode classifyReason(String reason){
        String value=reason==null?"":reason.toLowerCase(Locale.ROOT);
        // A reason is one logical error. Incidental log lines are not dependency evidence.
        int newline=value.indexOf('\n');if(newline>=0)value=value.substring(0,newline);
        if(cn.piq.retro.storage.RuntimeWorkspace.diskFull(value))return DISK_FULL;
        if(contains(value,"crc","确认状态","state mismatch"))return STATE_MISMATCH;
        if(contains(value,"种子","seed","恢复不精确","不能精确恢复"))return SEED_RESTORE;
        if(contains(value,"顺序异常","包序列","输入帧越界","预输入超限","输入超限","时间线倒退","已提交的","接收阶段异常","积压"))return INPUT_SEQUENCE;
        if(contains(value,"协议不匹配","音画边界异常","profile mismatch"))return PROTOCOL;
        if(contains(value,"连接已结束","连接断开","已断开","disconnected","connection closed","connection reset","broken pipe"))return DISCONNECTED;
        if(contains(value,"timeout","timed out","超时"))return TIMEOUT;
        if(contains(value,"unsatisfiedlink","vcruntime","msvcp","运行库缺失","运行库不可用","缺少运行库")
                ||value.contains("dll")&&contains(value,"missing","not found","nosuchfile","不存在","缺少","无法加载","failed to load","could not load"))return DEPENDENCY;
        return RUNTIME;
    }
    private static boolean contains(String value,String... words){for(String word:words)if(value.contains(word))return true;return false;}

    /** Carries a classified process reason separately from its diagnostic log. */
    public static final class Failure extends IllegalStateException {
        private final NetplayFailureCode code;
        public Failure(String reason){super(reason);code=classifyReason(reason);}
        public NetplayFailureCode code(){return code;}
    }
}
