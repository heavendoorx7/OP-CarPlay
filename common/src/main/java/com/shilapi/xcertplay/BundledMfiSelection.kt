package com.shilapi.xcertplay

/** A partially selected custom pair must never be silently mixed with bundled material. */
internal data class MfiDocumentSelection(val certificate: String, val privateKey: String)

internal object BundledMfiSelection {
    fun documents(packageName: String) = MfiDocumentSelection(
        "content://$packageName.bundled-mfi/certificate.p7b",
        "content://$packageName.bundled-mfi/identity.pk8",
    )
    fun resolve(packageName: String, stored: MfiDocumentSelection, available: Boolean): MfiDocumentSelection =
        if (available && stored.certificate.isEmpty() && stored.privateKey.isEmpty()) documents(packageName) else stored

    fun assetName(packageName: String, uri: String): String? = when (uri) {
        documents(packageName).certificate -> "certificate.p7b"
        documents(packageName).privateKey -> "identity.pk8"
        else -> null
    }
}
