package com.xposed.wetypehook

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ModuleBridgeContract {
    private const val TAG = "MIUIIME.ModuleBridge"
    const val ACTION_BRIDGE = "com.xposed.wetypehook.action.BRIDGE"
    const val MESSAGE_RECORD_ACTIVATION = 2
    const val EXTRA_MESSAGE_TYPE = "message_type"

    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"

    fun explicitBridgeIntent(): Intent = Intent(ACTION_BRIDGE).setComponent(
        ComponentName(MODULE_PACKAGE_NAME, ModuleBridgeReceiver::class.java.name)
    )

    fun sendWithIdentity(context: Context, intent: Intent): Boolean {
        intent.addFlags(
            Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND
        )
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
                context.sendBroadcast(intent, null, options)
            } else {
                context.sendBroadcast(intent)
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to send ${intent.action} to ${intent.component ?: intent.`package`}", error)
        }.isSuccess
    }
}

class ModuleBridgeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MIUIIME.ModuleBridge"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModuleBridgeContract.ACTION_BRIDGE) return
        when (intent.getIntExtra(ModuleBridgeContract.EXTRA_MESSAGE_TYPE, 0)) {
            ModuleBridgeContract.MESSAGE_RECORD_ACTIVATION -> recordActivation(context, intent)
        }
    }

    private fun recordActivation(context: Context, intent: Intent) {
        val sourcePackage = intent.getStringExtra(ModuleActivationTracker.EXTRA_SOURCE_PACKAGE)
            ?: return
        if (!isTrustedSender(context, sourcePackage)) {
            Log.w(TAG, "Rejected activation for $sourcePackage from an untrusted sender")
            return
        }
        ModuleActivationTracker.recordActivation(
            context = context,
            sourcePackage = sourcePackage,
            sourceProcess = intent.getStringExtra(ModuleActivationTracker.EXTRA_SOURCE_PROCESS)
        )
    }

    private fun isTrustedSender(context: Context, expectedPackage: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.packageManager.getPackagesForUid(sentFromUid)
            ?.contains(expectedPackage) == true
    }
}
