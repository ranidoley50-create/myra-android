package com.myra.assistant

import android.content.pm.ApplicationInfo

/**
 * Financial apps are not launchable through MYRA's voice command path.
 * The Android finance category, explicit package IDs, and a conservative
 * name/package rule are all checked. There is no user override in the UI.
 */
object SensitiveAppPolicy {
    private val protectedPackages = setOf(
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp",
        "com.sbi.lotusintouch",
        "com.hdfcbank.mobilebanking",
        "com.csam.icici.bank.imobile",
        "com.axis.mobile",
        "com.axis.mobilebanking",
        "com.kotak.bank.mobilebanking",
        "com.idfcfirstbank.optimus",
        "com.pnb.mobile",
    )

    private val sensitiveTerms = listOf(
        "upi", "bank", "banking", "payment", "paytm", "phonepe", "gpay",
        "google pay", "bhim", "wallet", "finance", "financial", "money",
        "rupee", "cred", "mobikwik", "freecharge", "yono", "imobile",
        "kotak", "icici", "hdfc", "sbi", "axis bank", "canara", "punjab national",
        "indusind", "federal bank", "yes bank", "idfc", "idbi", "union bank",
        "bank of baroda", "bob world", "rbl bank", "au 0101",
    )

    fun isSensitiveTarget(target: String): Boolean {
        val normalized = target.normalized()
        return sensitiveTerms.any { term -> normalized.contains(term) }
    }

    fun isSensitiveApp(label: String, packageName: String, category: Int): Boolean {
        if (category == ApplicationInfo.CATEGORY_FINANCE) return true
        val normalizedPackage = packageName.lowercase()
        if (normalizedPackage in protectedPackages) return true
        return isSensitiveTarget(label) || isSensitiveTarget(packageName)
    }

    private fun String.normalized(): String =
        lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
