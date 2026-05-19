package com.flowdroid.engine.actions

import com.flowdroid.common.flow.AccessibilityError
import com.flowdroid.common.flow.ExecutionError

/**
 * Maps an [AccessibilityError] from the [com.flowdroid.common.flow.AccessibilityController]
 * façade into the engine's typed [ExecutionError] domain.
 *
 * Mapping rationale:
 *  - [AccessibilityError.ServiceNotBound] → [ExecutionError.PermissionMissing] with the
 *    BIND_ACCESSIBILITY_SERVICE permission name — this is the user-facing remediation hint:
 *    "go enable the accessibility service in Settings".
 *  - [AccessibilityError.KeyguardDismissDenied] → also surfaced as a permission/capability issue;
 *    on Android the keyguard cannot be dismissed without user consent.
 *  - [AccessibilityError.NodeNotFound] / [AccessibilityError.NoFocusedEditableNode]
 *    → [ExecutionError.TargetNotFound], preserving the original needle/mode for diagnostics.
 *  - [AccessibilityError.GestureFailed] / [AccessibilityError.SystemFailure]
 *    → [ExecutionError.SystemFailure].
 *  - [AccessibilityError.Timeout] → [ExecutionError.Timeout].
 *
 * This is `internal` so the engine module shares it across all UI executors without exposing it
 * outside the package.
 */
internal fun AccessibilityError.toExecutionError(): ExecutionError = when (this) {
    is AccessibilityError.ServiceNotBound ->
        ExecutionError.PermissionMissing("BIND_ACCESSIBILITY_SERVICE")
    is AccessibilityError.KeyguardDismissDenied ->
        ExecutionError.PermissionMissing("DISMISS_KEYGUARD")
    is AccessibilityError.NodeNotFound ->
        ExecutionError.TargetNotFound("${mode.name}:$needle")
    is AccessibilityError.NoFocusedEditableNode ->
        ExecutionError.TargetNotFound("focused-editable-node")
    is AccessibilityError.GestureFailed ->
        ExecutionError.SystemFailure("gesture failed: $reason")
    is AccessibilityError.SystemFailure ->
        ExecutionError.SystemFailure(message)
    is AccessibilityError.Timeout ->
        ExecutionError.Timeout(afterMillis)
}
