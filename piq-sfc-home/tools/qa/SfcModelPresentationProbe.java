import cn.piq.sfchome.client.SfcModelPresentation;
public final class SfcModelPresentationProbe {
    public static void main(String[] args) {
        for (int i=0;i<SfcModelPresentation.MODEL_COUNT;i++) System.out.println("MODEL "+i+" "+SfcModelPresentation.path(i));
        for (int mask=0;mask<8;mask++) {
            StringBuilder visible=new StringBuilder();
            for (int i=0;i<SfcModelPresentation.MODEL_COUNT;i++) if(SfcModelPresentation.visible(i,(mask&1)!=0,(mask&2)!=0,(mask&4)!=0)) visible.append(i);
            System.out.println("VISIBLE "+mask+" "+visible);
        }
        for (int t=0;t<4;t++) System.out.println("YAW "+t+" "+SfcModelPresentation.yawDegrees(t));
    }
}
