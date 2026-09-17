package com.unuslumen.app.util.permissions

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@Stable
class AndroidPermissionState(
    private val permission: Permission,
    private val context: Context,
    private val activity: Activity?
) : PermissionState {

    override var shouldShowRationale by mutableStateOf(false)

    override var isGranted by mutableStateOf(getPermissionStatus())

    override fun launchRequest() {
        if (permission.isSpecial) {
            val intent = permission.toSettingsIntent(context).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                openAppSettings()
            }
        } else if (permission == Permission.ACCESS_BACKGROUND_LOCATION) {
            ActivityCompat.requestPermissions(
                activity!!,
                arrayOf(permission.toAndroidPermission()),
                1
            )
        } else {
            val perm = permission.toAndroidPermission()
            if (perm.isNotEmpty()) {
                launcher?.launch(perm)
            }
        }
    }

    internal var launcher: ActivityResultLauncher<String>? = null

    override fun refresh() {
        isGranted = getPermissionStatus()
    }

    private fun getPermissionStatus(): Boolean {
        if (permission.isSpecial) {
            return permission.isSpecialGranted(context)
        }
        val perm = permission.toAndroidPermission()
        if (perm.isEmpty()) return true

        val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED

        shouldShowRationale = !granted && activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, perm)
        } ?: true
        return granted
    }

    @SuppressLint("InlinedApi")
    override fun openAppSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.parse("package:" + context.applicationContext.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

@Composable
fun rememberPermissionState(
    permission: Permission,
): PermissionState {
    val context = LocalContext.current
    val permissionState = remember(permission) {
        AndroidPermissionState(permission, context, context.getActivity())
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionState.refresh()
    }

    LifecycleResumeEffect(permission, launcher) {
        if (!permissionState.isGranted) {
            permissionState.refresh()
        }
        if (permissionState.launcher == null) {
            permissionState.launcher = launcher
        }
        onPauseOrDispose {
            permissionState.launcher = null
        }
    }

    return permissionState
}

fun Context.getActivity(): Activity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    throw IllegalStateException("Permissions should be called in the context of an Activity")
}