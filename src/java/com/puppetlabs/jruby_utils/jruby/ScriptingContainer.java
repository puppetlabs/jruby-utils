package com.puppetlabs.jruby_utils.jruby;

import org.jruby.embed.EmbedEvalUnit;
import org.jruby.embed.EmbedRubyInstanceConfigAdapter;
import org.jruby.runtime.Block;

/**
 */
public interface ScriptingContainer extends EmbedRubyInstanceConfigAdapter {
    Object callMethod(Object receiver, String methodName, Object... args);
    Object callMethod(Object receiver, String methodName,
                      Block block, Object... args);
    <T> T callMethod(Object receiver, String methodName, Class<T> returnType);
    <T> T callMethod(Object receiver, String methodName, Object singleArg,
                     Class<T> returnType);
    <T> T callMethod(Object receiver, String methodName, Object[] args,
                     Class<T> returnType);
    <T> T callMethod(Object receiver, String methodName, Object[] args,
                     Block block, Class<T> returnType);
    <T> T callMethod(Object receiver, String methodName, Class<T> returnType,
                     EmbedEvalUnit unit);
    <T> T callMethod(Object receiver, String methodName, Object[] args,
                     Class<T> returnType, EmbedEvalUnit unit);
    <T> T callMethod(Object receiver, String methodName, Object[] args,
                     Block block, Class<T> returnType, EmbedEvalUnit unit);
    Object callMethodWithArgArray(Object receiver,
                              String methodName,
                              Object[] args,
                              Class<? extends Object> returnType);
    Object runScriptlet(String script);
    void terminate();
}
