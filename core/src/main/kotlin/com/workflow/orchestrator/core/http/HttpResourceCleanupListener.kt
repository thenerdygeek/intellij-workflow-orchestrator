package com.workflow.orchestrator.core.http

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

/**
 * Releases [HttpClientFactory]'s shared HTTP resources when this plugin is unloaded
 * (dynamic unload / dev hot-reload). Without this, the shared `ConnectionPool`'s idle
 * connections and the OkHttp dispatcher's threads survive in the now-orphaned plugin
 * classloader until their keep-alive timers expire. Closes audit finding core:F-7.
 *
 * Registered as an application listener on [DynamicPluginListener.TOPIC] in plugin.xml.
 */
class HttpResourceCleanupListener : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId?.idString == PLUGIN_ID) {
            HttpClientFactory.shutdownSharedResources()
        }
    }

    companion object {
        private const val PLUGIN_ID = "com.workflow.orchestrator.plugin"
    }
}
