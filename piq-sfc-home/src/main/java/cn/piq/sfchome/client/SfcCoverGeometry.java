// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

/** Fits the FC 2:1 cover inside the user's 3.85 x 1.735 label without stretching it. */
final class SfcCoverGeometry {
    record Face(float left,float bottom,float right,float top,float z){}
    private SfcCoverGeometry(){}
    static Face label(boolean inserted){
        double bottom=.845,top=2.58,half=top-bottom;
        double left=8-half,right=8+half,z=7.5965;
        // Independent source card is translated only; supplied UV and proportions stay intact.
        double y=inserted?2.18:6.55;bottom+=y;top+=y;if(inserted)z+=3.711;
        return new Face((float)(left/16),(float)(bottom/16),(float)(right/16),(float)(top/16),(float)(z/16));
    }
}
