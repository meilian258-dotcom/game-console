package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.session.NesCoreVariant;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Captured server-owned paths and immutable save authority, never client supplied paths. */
public record ServerCoreContext(Path gameRoot, Path saveRoot, UUID ownerId, UUID roomId,
                                NesCoreVariant nesVariant,NesManagedState managedNesState) {
    public ServerCoreContext {
        gameRoot=Objects.requireNonNull(gameRoot).toAbsolutePath().normalize();
        saveRoot=Objects.requireNonNull(saveRoot).toAbsolutePath().normalize();
        Objects.requireNonNull(ownerId); Objects.requireNonNull(roomId); Objects.requireNonNull(nesVariant);
    }
    public ServerCoreContext(Path gameRoot,Path saveRoot,UUID ownerId,UUID roomId,NesCoreVariant variant){this(gameRoot,saveRoot,ownerId,roomId,variant,null);}
    public ServerCoreContext(Path gameRoot,Path saveRoot,UUID ownerId,UUID roomId) {
        this(gameRoot,saveRoot,ownerId,roomId,NesCoreVariant.LEGACY,null);
    }
    /** roomId must be the stable device identity, not a newly generated network session token. */
    public Path saveDirectory(String backend) {
        if(backend==null||!backend.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Save backend");
        return saveRoot.resolve("server-hosted-v1").resolve(backend).resolve(ownerId.toString()).resolve(roomId.toString());
    }
}
