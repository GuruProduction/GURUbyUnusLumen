package com.unuslumen.app.guru.presentation.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager.LayoutParams
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.unuslumen.app.guru.presentation.app_lock.AppLockManager
import com.unuslumen.app.util.permissions.*
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModel()

    companion object {
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1001
        private const val STANDARD_PERMISSION_REQUEST_CODE = 2000
        private const val BACKGROUND_LOCATION_REQUEST_CODE = 2001
        const val ACTION_SCREEN_CAPTURE_RESULT = "com.unuslumen.app.guru.SCREEN_CAPTURE_RESULT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private var pendingSpecialPermission: Permission? = null

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.unuslumen.app.guru.REQUEST_SCREEN_CAPTURE") {
                requestScreenCapturePermission()
            }
        }
    }

    private val bluetoothPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.unuslumen.app.guru.REQUEST_BLUETOOTH_PERMISSION") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                        1
                    )
                }
            }
        }
    }

    /**
     * Generalized permission receiver. Catches any permission request from GURU's tools.
     * Standard permissions fire the Android dialog. Special permissions open Settings.
     * Result is broadcast back so GURU knows whether it was granted.
     */
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PermissionGateway.ACTION_REQUEST_PERMISSION) {
                val permName = intent.getStringExtra(PermissionGateway.EXTRA_PERMISSION) ?: return
                val permission = runCatching { Permission.valueOf(permName) }.getOrNull() ?: return
                handlePermissionRequest(permission)
            }
        }
    }

    private fun handlePermissionRequest(permission: Permission) {
        if (PermissionGateway.isGranted(this, permission)) {
            PermissionGateway.broadcastResult(this, permission, true)
            return
        }

        if (permission.isSpecial) {
            pendingSpecialPermission = permission
            val settingsIntent = permission.toSettingsIntent(this).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(settingsIntent)
            } catch (e: Exception) {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(fallback)
            }
        } else if (permission == Permission.ACCESS_BACKGROUND_LOCATION) {
            val perm = permission.toAndroidPermission()
            if (perm.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(perm),
                    BACKGROUND_LOCATION_REQUEST_CODE
                )
            }
        } else {
            val perm = permission.toAndroidPermission()
            if (perm.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(perm),
                    STANDARD_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            STANDARD_PERMISSION_REQUEST_CODE, BACKGROUND_LOCATION_REQUEST_CODE, 1 -> {
                permissions.forEachIndexed { index, perm ->
                    val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
                    val matched = findPermissionByAndroidString(perm)
                    if (matched != null) {
                        PermissionGateway.broadcastResult(this, matched, granted)
                    }
                }
            }
        }
    }

    private fun findPermissionByAndroidString(androidPerm: String): Permission? {
        return Permission.entries.find { it.toAndroidPermission() == androidPerm }
    }

    override fun onResume() {
        super.onResume()
        // When returning from a Settings screen, check if the special permission was granted
        pendingSpecialPermission?.let { permission ->
            val granted = PermissionGateway.isGranted(this, permission)
            PermissionGateway.broadcastResult(this, permission, granted)
            pendingSpecialPermission = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FontRegistry needs an app context to load user-dropped guru_fonts.
        com.unuslumen.app.ui.theme.FontRegistry.init(this)

        registerReceiver(
            screenCaptureReceiver,
            IntentFilter("com.unuslumen.app.guru.REQUEST_SCREEN_CAPTURE"),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            bluetoothPermissionReceiver,
            IntentFilter("com.unuslumen.app.guru.REQUEST_BLUETOOTH_PERMISSION"),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            permissionReceiver,
            IntentFilter(PermissionGateway.ACTION_REQUEST_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED
        )

        val appLockManager = AppLockManager(this)
        setContent {
            val blockScreenshots by viewModel.blockScreenshots.collectAsState(initial = false)

            LaunchedEffect(blockScreenshots) {
                if (blockScreenshots) {
                    window.setFlags(
                        LayoutParams.FLAG_SECURE,
                        LayoutParams.FLAG_SECURE
                    )
                } else
                    window.clearFlags(LayoutParams.FLAG_SECURE)
            }
            LaunchedEffect(Unit) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.Transparent.toArgb(),
                        Color.Transparent.toArgb(),
                        detectDarkMode = { false }
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.Transparent.toArgb(),
                        Color.Transparent.toArgb(),
                        detectDarkMode = { false }
                    ),
                )
            }
            guruApp(
                viewModel = viewModel,
                appLockManager = appLockManager
            )
        }
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureResultCode = resultCode
            com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureData = data
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenCaptureReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bluetoothPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
    }
}