package com.flowdroid.common.service

/**
 * Façade used by [com.flowdroid.FlowDroidApplication] and BootReceiver to bring the system up.
 * Owned by the services agent.
 */
interface ServiceController {
    /** Start the foreground service and schedule the watchdog. Idempotent. */
    fun bootstrap()

    /** Force-toggle the NotificationListenerService component (disable+enable) to force OS rebind. */
    fun toggleNotificationListener()

    /** Request the OS to rebind to the listener without toggling (lighter-weight). */
    fun requestNotificationListenerRebind()

    /** Stop everything cleanly (only used by tests and a future "panic" button). */
    fun shutdown()
}
