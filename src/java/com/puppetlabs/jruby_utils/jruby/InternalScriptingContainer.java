package com.puppetlabs.jruby_utils.jruby;

import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.util.JRubyClassLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Map;

/**
 * An extension of the JRuby ScriptingContainer class which is
 * slightly easier to use from Clojure.
 */
public class InternalScriptingContainer
        extends org.jruby.embed.ScriptingContainer
        implements ScriptingContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            InternalScriptingContainer.class);

    /**
     * {@link #terminateJarIndexCacheEntries(URL[])} method for more info on the
     * purpose of the jar index cache purge code
     */
    private static Map<String, Object> jarIndexCache = null;
    private static Method jarIndexReleaseMethod = null;
    private static Exception jarIndexCacheException = null;
    private static Method classLoaderTempDirMethod = null;

    /**
     * We have to use reflection to get access to the jar index cache since it
     * is not public.
     */
    static {
        try {
            Class jarResourceClass = Class.forName("org.jruby.util.JarResource");
            Field jarCacheField = jarResourceClass.getDeclaredField("jarCache");
            jarCacheField.setAccessible(true);
            Object jarCache = jarCacheField.get(jarResourceClass);
            Class jarCacheClass = jarCache.getClass();
            Field indexCacheField = jarCacheClass.getDeclaredField("indexCache");
            indexCacheField.setAccessible(true);

            @SuppressWarnings("unchecked") Map<String, Object> indexCache =
                    (Map<String, Object>) indexCacheField.get(jarCache);
            jarIndexCache = indexCache;

            Class<?> jarIndexClass = Class.forName("org.jruby.util.JarCache$JarIndex");
            jarIndexReleaseMethod = jarIndexClass.getMethod("release");
            jarIndexReleaseMethod.setAccessible(true);

            classLoaderTempDirMethod = JRubyClassLoader.class.getDeclaredMethod(
                    "getTempDir");
            classLoaderTempDirMethod.setAccessible(true);
        } catch (ClassNotFoundException|
                NoSuchFieldException|
                NoSuchMethodException|
                IllegalAccessException ex) {
            jarIndexCacheException = ex;
        }
    }

    private String classLoaderTempDir = null;

    public InternalScriptingContainer(LocalContextScope scope) {
        super(scope);
        validateJarCacheAccess();
    }

    public InternalScriptingContainer(LocalContextScope scope,
                                      LocalVariableBehavior behavior) {
        super(scope, behavior);
        validateJarCacheAccess();
    }

    private void validateJarCacheAccess() {
        if (jarIndexCache == null || jarIndexReleaseMethod == null ||
                classLoaderTempDirMethod == null) {
            throw new RuntimeException(
                    "Unable to access jar index cache", jarIndexCacheException);
        }
    }

    private String getClassLoaderTempDir() {
        File classLoaderTempDirAsFile = null;

        try {
            classLoaderTempDirAsFile = (File)
                    classLoaderTempDirMethod.invoke(getJRubyClassLoader());
            classLoaderTempDir = classLoaderTempDirAsFile.getPath();
        } catch (IllegalAccessException|InvocationTargetException ex) {
            throw new RuntimeException(
                    "Unable to get temp directory for jruby classloader", ex);
        }

        return classLoaderTempDirAsFile.getPath();
    }

    private void removeJarIfTemp(String urlPath, String tempDir) {
        // In JRuby 9k, when the JRubyClassLoader for a ScriptingContainer loads
        // jars within jars, it ends up copying the original jar to a file under
        // a temp directory.  See:
        // https://github.com/jruby/jruby/blob/9.1.8.0/core/src/main/java/org/jruby/util/JRubyClassLoader.java#L78-L89.
        // To avoid letting these temporary jars pile up on disk after the
        // container has been terminated, the jars are deleted here.  The code
        // below avoids deleting any jar not underneath the temp directory
        // created for the class loader since that is more likely intended to be
        // persistent.
        if (urlPath.startsWith(classLoaderTempDir)) {
            new File(urlPath).delete();
        }
    }

    private JRubyClassLoader getJRubyClassLoader() {
        return getProvider().getRuntime().getJRubyClassLoader();
    }

    /**
     * When a ScriptingContainer is initialized under JRuby 9k, embedded jars
     * like jopenssl and psych are copied out to a per-container temporary
     * directory.  File descriptors are opened to the temporary jars and loaded
     * into a static JarCache.  Entries added into the JarCache are not freed
     * when the ScriptingContainer for which the entries are added is terminated.
     * This leads to both the jar files on disk and associated file descriptors
     * in memory piling up as containers are recycled.
     *
     * See https://github.com/jruby/jruby/issues/3928.
     *
     * Ideally, the jar files and descriptors would be cleaned up automatically
     * by JRuby.  In lieu of that, we clean those up here, using some ugly code
     * which accesses private functionality in JRuby.  Hopefully, we'll be able
     * to remove this code later on when a fix is available in JRuby.
     * https://tickets.puppetlabs.com/browse/SERVER-1777 describes the work
     * for investigating a longer-term fix.
     *
     * Although the JarCache code exists both in JRuby 1.7 and 9k, entries only
     * appear to be added per-container to the JarCache in JRuby 9k, so this
     * code appears to basically be a no-op in JRuby 1.7.
     */
    private void terminateJarIndexCacheEntries(URL[] classLoaderUrls) {
        String classLoaderTempDir = getClassLoaderTempDir();

        for (URL classLoaderUrl : classLoaderUrls) {
            String urlPath = classLoaderUrl.getPath();
            Object jarEntry = jarIndexCache.get(urlPath);
            if (jarEntry != null) {
                jarIndexCache.remove(urlPath);
                try {
                    jarIndexReleaseMethod.invoke(jarEntry);
                } catch (IllegalAccessException|InvocationTargetException ex) {
                    throw new RuntimeException(
                            "Unable to release jar index cache entry", ex);
                }
                removeJarIfTemp(urlPath, classLoaderTempDir);
            }
        }
    }

    /**
     * This method delegates to a specific signature of #callMethod from the
     * parent class.  There are many overloaded signatures in the parent class,
     * many of which have overlapping arities.  This sometimes causes problems
     * with Clojure attempting to determine the correct signature to call.
     *
     * @param receiver   - the Ruby object to call a method on
     * @param methodName - the name of the method to call
     * @param args       - an array of args to call the method with
     * @param returnType - the expected type of the return value from the method call
     * @return - the result of calling the method on the Ruby receiver object
     */
    public Object callMethodWithArgArray(Object receiver, String methodName,
                                         Object[] args, Class<? extends Object> returnType) {
        return callMethod(receiver, methodName, args, returnType);
    }

    @Override
    public void terminate() {
        URL[] classLoaderUrls = getJRubyClassLoader().getURLs();
        super.terminate();
        terminateJarIndexCacheEntries(classLoaderUrls);
    }
}
