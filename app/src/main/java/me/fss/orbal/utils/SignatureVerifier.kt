package me.fss.orbal.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class SignatureVerifier(private val context: Context) {

    fun isSignedByTrustedCert(): Boolean = try {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        val md = MessageDigest.getInstance("SHA-256")
        signatures.any { sig ->
            val hex = md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            hex == TRUSTED_DIGEST || hex == DEBUG_DIGEST
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        // ORBAL keystore SHA-256 — Orbal release APK
        private const val TRUSTED_DIGEST = "98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c"
        // Debug keystore for local release testing (S22) — also trusted
        private const val DEBUG_DIGEST = "359a3629ecce7be28ea9d6259e98e4ecab9ca5d3f78e5755abbdc1ce93f17139"
    }
}
