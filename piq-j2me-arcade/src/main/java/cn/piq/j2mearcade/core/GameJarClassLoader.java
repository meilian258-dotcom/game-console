package cn.piq.j2mearcade.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Child-first loader for one user-supplied MIDlet jar. */
final class GameJarClassLoader extends URLClassLoader {
    private static final String COMMON_BRIDGE = IsolatedGameCommon.class.getName();

    static {
        registerAsParallelCapable();
    }

    GameJarClassLoader(Path jar, ClassLoader parent) throws IOException {
        super(new URL[] {jar.toUri().toURL()}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (COMMON_BRIDGE.equals(name)) {
                    loaded = defineBridgeClass();
                } else if (mustDelegate(name)) {
                    loaded = getParent().loadClass(name);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInGameJar) {
                        loaded = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        URL gameResource = findResource(name);
        return gameResource != null ? gameResource : getParent().getResource(name);
    }

    private Class<?> defineBridgeClass() throws ClassNotFoundException {
        String resourceName = COMMON_BRIDGE.replace('.', '/') + ".class";
        try (InputStream input = getParent().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new ClassNotFoundException(COMMON_BRIDGE);
            }
            byte[] bytes = input.readAllBytes();
            return defineClass(COMMON_BRIDGE, bytes, 0, bytes.length);
        } catch (IOException error) {
            throw new ClassNotFoundException(COMMON_BRIDGE, error);
        }
    }

    private static boolean mustDelegate(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.microedition.")
                || name.startsWith("javax.wireless.messaging.")
                || name.startsWith("com.nokia.")
                || name.startsWith("org.microemu.")
                || (name.startsWith("cn.piq.j2mearcade.") && !COMMON_BRIDGE.equals(name));
    }
}
