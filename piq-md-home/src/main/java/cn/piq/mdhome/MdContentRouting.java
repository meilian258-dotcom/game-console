// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;
import java.util.Objects;
import java.util.UUID;

/** Constant-space, exact-connection download authority; unknown tokens never mean private. */
public final class MdContentRouting {
    public enum Lane { REJECT, PUBLIC, PRIVATE }
    private Object connection;private UUID token;private Lane lane=Lane.REJECT;
    public boolean grant(Object source,UUID token,Lane lane,boolean admitted){
        Objects.requireNonNull(source);Objects.requireNonNull(token);Objects.requireNonNull(lane);
        if(!admitted||lane==Lane.REJECT)return false;
        if(this.token!=null)return this.connection==source&&this.token.equals(token)&&this.lane==lane;
        this.connection=source;this.token=token;this.lane=lane;return true;
    }
    public Lane route(Object source,UUID candidate){return connection==source&&token!=null&&token.equals(candidate)?lane:Lane.REJECT;}
    public void retire(Object source,UUID candidate){if(route(source,candidate)!=Lane.REJECT)clear();}
    public void clear(){connection=null;token=null;lane=Lane.REJECT;}
}
