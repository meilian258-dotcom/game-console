package cn.piq.replayquick;

public final class SableWireContractTest {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("expected supported boolean");
        if(args[0].equals("unsupported")){
            try{SableWireContract.supported();throw new AssertionError("unreviewed bytes accepted");}
            catch(java.io.IOException expected){System.out.println("SableWireContractTest unsupported bytes rejected PASS");return;}
        }
        boolean expected=Boolean.parseBoolean(args[0]),actual=SableWireContract.supported();
        if(actual!=expected)throw new AssertionError("expected "+expected+", actual "+actual);
        System.out.println("SableWireContractTest supported="+actual+" PASS (real classpath resources)");
    }
}
