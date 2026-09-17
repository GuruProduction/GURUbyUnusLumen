package com.unuslumen.app.util.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Gateway for GURU's tools to request and check permissions.
 *
 * How it works:
 * 1. Tool calls PermissionGateway.requestPermission(context, permission)
 * 2. This broadcasts an intent that MainActivity catches
 * 3. MainActivity fires the Android permission dialog or opens Settings
 * 4. Result is broadcast back via PERMISSION_RESULT_ACTION
 * 5. Tool calls PermissionGateway.isGranted(context, permission) to check
 *
 * Once granted, it stays granted. Android handles persistence.
 */
object PermissionGateway {

    const val ACTION_REQUEST_PERMISSION = "com.unuslumen.app.guru.REQUEST_PERMISSION"
    const val ACTION_PERMISSION_RESULT = "com.unuslumen.app.guru.PERMISSION_RESULT"
    const val EXTRA_PERMISSION = "permission"
    const val EXTRA_GRANTED = "granted"

    /**
     * Request a single permission. Fires a broadcast that MainActivity catches.
     * Standard permissions show the Android dialog.
     * Special permissions open the Settings screen.
     */
    fun requestPermission(context: Context, permission: Permission) {
        val intent = Intent(ACTION_REQUEST_PERMISSION).apply {
            putExtra(EXTRA_PERMISSION, permission.name)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Request multiple standard permissions at once.
     * Only works for non-special permissions. Special ones are skipped.
     */
    fun requestStandardPermissions(context: Context) {
        standardRuntimePermissions.forEach { permission ->
            if (!isGranted(context, permission)) {
                requestPermission(context, permission)
            }
        }
    }

    /**
     * Request all special permissions that need Settings screens.
     */
    fun requestSpecialPermissions(context: Context) {
        specialPermissions.forEach { permission ->
            if (!isGranted(context, permission)) {
                requestPermission(context, permission)
            }
        }
    }

    /**
     * Request background location. Must be called AFTER fine location is granted.
     */
    fun requestBackgroundLocation(context: Context) {
        if (!isGranted(context, Permission.ACCESS_BACKGROUND_LOCATION)) {
            requestPermission(context, Permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    /**
     * Check if a permission is currently granted.
     * Works for both standard and special permissions.
     */
    fun isGranted(context: Context, permission: Permission): Boolean {
        return if (permission.isSpecial) {
            permission.isSpecialGranted(context)
        } else {
            val perm = permission.toAndroidPermission()
            if (perm.isEmpty()) true
            else ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if any permission in a list is missing.
     */
    fun getMissingPermissions(context: Context, permissions: List<Permission>): List<Permission> {
        return permissions.filter { !isGranted(context, it) }
    }

    /**
     * Get a list of all permissions that are not yet granted.
     */
    fun getAllMissingPermissions(context: Context): List<Permission> {
        val all = standardRuntimePermissions + specialPermissions + backgroundLocationPermissions
        return getMissingPermissions(context, all)
    }

    /**
     * Revoke a permission from within the app.
     * Standard runtime permissions: uses revokeSelfPermissionsOnKill on API 33+.
     * Accessibility: calls disableSelf() on the service instance.
     * Notification Listener: calls requestUnbind() on the service instance.
     * Other special permissions: opens the relevant Settings screen.
     */
    fun revokePermission(context: Context, permission: Permission) {
        if (!isGranted(context, permission)) return

        if (permission == Permission.ACCESSIBILITY_SERVICE) {
            com.unuslumen.app.util.shell.GuruAccessibilityService.instance?.disableSelf()
            return
        }

        if (permission == Permission.NOTIFICATION_LISTENER) {
            // Use the static requestUnbind(ComponentName) method so we don't need
            // a direct reference to GuruNotificationListener, which lives in portal:data.
            // The ComponentName identifies our listener service by its fully qualified class name.
            val componentName = android.content.ComponentName(
                context.packageName,
                "com.unuslumen.app.data.tools.GuruNotificationListener"
            )
            try {
                android.service.notification.NotificationListenerService.requestUnbind(componentName)
            } catch (e: Exception) {
                android.util.Log.w("PermissionGateway", "requestUnbind failed: ${e.message}")
            }
            return
        }

        if (permission.isSpecial) {
            // Special permissions without self-revoke APIs open Settings
            val intent = permission.toSettingsIntent(context).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to app settings
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            }
            return
        }

        // Standard runtime permission: revoke via API 33+ self-revoke
        val perm = permission.toAndroidPermission()
        if (perm.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.revokeSelfPermissionsOnKill(listOf(perm))
            } else {
                // Below API 33: open app settings so user can revoke manually
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        }
    }

    /**
     * Broadcast the result of a permission request.
     * Called by MainActivity after the user grants or denies.
     */
    fun broadcastResult(context: Context, permission: Permission, granted: Boolean) {
        val intent = Intent(ACTION_PERMISSION_RESULT).apply {
            putExtra(EXTRA_PERMISSION, permission.name)
            putExtra(EXTRA_GRANTED, granted)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}