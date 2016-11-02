package com.puppetlabs.jruby_utils.jruby;

import org.jruby.embed.EmbedEvalUnit;
import org.jruby.embed.EmbedRubyInstanceConfigAdapter;
import org.jruby.runtime.Block;

/**
 */
public interface ScriptingContainer extends EmbedRubyInstanceConfigAdapter {
    Object callMethodWithArgArray(Object receiver,
                              String methodName,
                              Object[] args,
                              Class<? extends Object> returnType);
    Object runScriptlet(String script);
    void terminate();
}
