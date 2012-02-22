package org.dcache.gplazma.strategies;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import org.dcache.gplazma.AuthenticationException;
import org.dcache.gplazma.plugins.GPlazmaSessionPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides support for the SESSION phase of logging in.  It tries
 * the first plugin.  For each plugin, it either tries the following plugin (if
 * one is available) or returns depending on the plugin's result and the
 * configured control (OPTIONAL, REQUIRED, etc).
 */
public class DefaultSessionStrategy implements SessionStrategy
{
    private static final Logger logger =
            LoggerFactory.getLogger(DefaultSessionStrategy.class);

    private PAMStyleStrategy<GPlazmaSessionPlugin> pamStyleSessionStrategy;

    /**
     *
     * @param plugins
     */
    @Override
    public void setPlugins(List<GPlazmaPluginElement<GPlazmaSessionPlugin>> plugins)
    {
        pamStyleSessionStrategy = new PAMStyleStrategy<GPlazmaSessionPlugin>(plugins);
    }

    /**
     * Devegates execution of the
     * {@link GPlazmaSessionPlugin#session(SessionID, Set<Principal>,Set<Object>) GPlazmaSessionPlugin.session}
     * methods of the plugins supplied by
     * {@link GPlazmaStrategy#setPlugins(List<GPlazmaPluginElement<T>>) GPlazmaStrategy.setPlugins}
     *  to
     * {@link  PAMStyleStrategy#callPlugins(PluginCaller<T>) PAMStyleStrategy.callPlugins(PluginCaller<T>)}
     * by providing anonymous implementation of the
     * {@link PluginCaller#call(org.dcache.gplazma.plugins.GPlazmaPlugin) PluginCaller}
     * interface.
     *
     * @param sessionID
     * @param authorizedPrincipals
     * @param attrib
     * @throws org.dcache.gplazma.AuthenticationException
     * @see PAMStyleStrategy
     * @see PluginCaller
     */
    @Override
    public synchronized void session(final Set<Principal> authorizedPrincipals,
            final Set<Object> attrib) throws AuthenticationException
    {
        pamStyleSessionStrategy.callPlugins( new PluginCaller<GPlazmaSessionPlugin>()
        {
            @Override
            public void call(GPlazmaSessionPlugin plugin) throws AuthenticationException
            {
                logger.debug("calling (pricipals: {}, attrib: {})",
                        authorizedPrincipals, attrib);

                plugin.session(authorizedPrincipals, attrib);
            }
        });
    }
}