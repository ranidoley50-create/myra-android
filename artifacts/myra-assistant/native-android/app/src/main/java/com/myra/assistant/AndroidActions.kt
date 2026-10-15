package com.myra.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

object AndroidActions {
    const val EXTRA_ACTION_KIND = "myra.action.kind"
    const val EXTRA_ACTION_VALUE = "myra.action.value"
    const val KIND_OPEN_APP = "open_app"
    const val KIND_DIAL = "dial"

    fun setVolumePercent(context: Context, percent: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100.0).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return "Media volume set to ${percent.coerceIn(0, 100)} percent."
    }

    fun changeVolume(context: Context, deltaPercent: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val delta = (max * deltaPercent / 100.0).toInt().coerceAtLeast(1)
        val next = (current + delta).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        val percentage = if (max == 0) 0 else next * 100 / max
        return "Media volume is now $percentage percent."
    }

    fun dispatchFromService(context: Context, kind: String, value: String): String {
        if (ForegroundState.activityVisible) {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(AssistantService.ACTION_EXECUTE_ACTION)
                .putExtra(EXTRA_ACTION_KIND, kind)
                .putExtra(EXTRA_ACTION_VALUE, value)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            context.startActivity(intent)
            return when (kind) {
                KIND_OPEN_APP -> "Opening $value."
                KIND_DIAL -> "Opening the dialer. Review the number and tap Call."
                else -> "Opening the requested action."
            }
        }

        postUserAction(context, kind, value)
        return when (kind) {
            KIND_OPEN_APP -> "MYRA prepared $value. Unlock the phone and tap the MYRA notification to open it."
            KIND_DIAL -> "MYRA prepared the number. Unlock the phone and tap the notification to review it in the dialer."
            else -> "Unlock the phone and tap the MYRA notification to continue."
        }
    }

    fun executeApprovedAction(context: Context, kind: String, value: String): String {
        return when (kind) {
            KIND_OPEN_APP -> launchApp(context, value)
            KIND_DIAL -> openDialer(context, value)
            else -> "That phone action is not supported."
        }
    }

    private fun launchApp(context: Context, requestedLabel: String): String {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        val app = matches
            .mapNotNull { resolve ->
                val info = resolve.activityInfo.applicationInfo
                val label = resolve.loadLabel(context.packageManager).toString()
                if (SensitiveAppPolicy.isSensitiveApp(label, info.packageName, info.category)) {
                    null
                } else {
                    info.packageName to label
                }
            }
            .distinctBy { it.first }
            .firstOrNull { (_, label) ->
                label.equals(requestedLabel, ignoreCase = true) ||
                    label.contains(requestedLabel, ignoreCase = true) ||
                    requestedLabel.contains(label, ignoreCase = true)
            }

        if (app == null) {
            return if (SensitiveAppPolicy.isSensitiveTarget(requestedLabel)) {
                "$requestedLabel is excluded from MYRA's app commands."
            } else {
                "I couldn't find an eligible app called $requestedLabel."
            }
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.first)
            ?: return "Android did not provide a launch action for ${app.second}."
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return "Opening ${app.second}."
    }

    private fun openDialer(context: Context, number: String): String {
        val safeNumber = number.filter { it.isDigit() || it == '+' }
        if (safeNumber.length !in 6..16) return "That phone number is not valid."
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", safeNumber, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "The dialer is open. Review the number and tap Call."
    }

    private fun postUserAction(context: Context, kind: String, value: String) {
        val channelId = "myra_actions"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "MYRA actions",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Actions that Android requires you to confirm."
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
            )
        }

        val actionIntent = Intent(context, MainActivity::class.java)
            .setAction(AssistantService.ACTION_EXECUTE_ACTION)
            .putExtra(EXTRA_ACTION_KIND, kind)
            .putExtra(EXTRA_ACTION_VALUE, value)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val requestCode = (kind + value).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (kind == KIND_DIAL) "Review call in the dialer" else "Open $value"
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MYRA needs your confirmation")
            .setContentText(title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(requestCode, notification)
    }
}
