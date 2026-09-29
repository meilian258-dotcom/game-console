package cn.piq.fcarcade.home;
import java.util.Objects;
import java.util.UUID;
/** One-shot, connection-bound UI intent. Hardware identity is rechecked by the caller. */
public final class HomeSaveIntent<C> {
    private final UUID token;private final C source;private final long deadline;private boolean consumed;
    public HomeSaveIntent(UUID token,C source,long deadline){this.token=Objects.requireNonNull(token);this.source=Objects.requireNonNull(source);this.deadline=deadline;}
    public UUID token(){return token;}
    public boolean valid(UUID candidate,C connection,long now){return !consumed&&now>=0&&now<deadline&&token.equals(candidate)&&source==connection;}
    public boolean consume(UUID candidate,C connection,long now){if(!valid(candidate,connection,now))return false;consumed=true;return true;}
    public boolean expired(long now){return consumed||now<0||now>=deadline;}
}
