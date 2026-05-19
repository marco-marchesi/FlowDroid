package com.flowdroid.platform.webhook

import com.flowdroid.common.flow.WebhookMethod

/**
 * In-memory registration record for a single webhook trigger. Keyed by `path` inside the
 * [WebhookServer]'s registry.
 *
 * @property flowId  ID of the flow that owns this hook — used as the dispatch target.
 * @property method  Allowed HTTP method; [WebhookMethod.ANY] accepts everything.
 * @property secret  If non-null, requests must carry header `X-FlowDroid-Secret` equal to it.
 */
internal data class WebhookEntry(
    val flowId: String,
    val method: WebhookMethod,
    val secret: String?,
)
