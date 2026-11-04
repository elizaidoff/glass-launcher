package com.glass.launcher.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.CancellationSignal
import java.util.concurrent.atomic.AtomicBoolean

object Auth {

    fun deviceSecure(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * System prompt with face or fingerprint (or the screen-lock PIN as a fallback).
     * If the phone has no screen lock at all, there is nothing to check against, so it succeeds.
     */
    fun prompt(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFail: () -> Unit = {},
    ) {
        if (!deviceSecure(activity)) { onSuccess(); return }
        val done = AtomicBoolean(false)
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        if (subtitle != null) builder.setSubtitle(subtitle)
        runCatching {
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        if (done.compareAndSet(false, true)) onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (done.compareAndSet(false, true)) onFail()
                    }
                },
            )
        }.onFailure { if (done.compareAndSet(false, true)) onFail() }
    }

    /** True when a fingerprint can be read silently, with no system dialog on screen. */
    @Suppress("DEPRECATION")
    fun silentFingerprintAvailable(ctx: Context): Boolean = runCatching {
        val fm = ctx.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
        fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints()
    }.getOrDefault(false)

    /**
     * Listens for a fingerprint in the background, with no dialog.
     * [onResult] gets true for the owner's finger, false for an unknown finger or an error.
     * Returns false if this phone does not allow silent reading.
     */
    @Suppress("DEPRECATION")
    fun silentFingerprint(ctx: Context, cancel: CancellationSignal, onResult: (Boolean) -> Unit): Boolean {
        if (!silentFingerprintAvailable(ctx)) return false
        val fm = ctx.getSystemService(Context.FINGERPRINT_SERVICE) as FingerprintManager
        val done = AtomicBoolean(false)
        return runCatching {
            fm.authenticate(null, cancel, 0, object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult?) {
                    if (done.compareAndSet(false, true)) onResult(true)
                }

                override fun onAuthenticationFailed() {
                    if (done.compareAndSet(false, true)) onResult(false)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode == FingerprintManager.FINGERPRINT_ERROR_CANCELED) return
                    if (done.compareAndSet(false, true)) onResult(false)
                }
            }, null)
            true
        }.getOrDefault(false)
    }
}
