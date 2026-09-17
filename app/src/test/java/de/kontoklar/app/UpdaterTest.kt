package de.kontoklar.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun onlySamePackageWithHigherVersionCodeCanBeInstalled() {
        assertTrue(isInstallablePackageUpdate("de.kontoklar.app", 19, "de.kontoklar.app", 20))
        assertFalse(isInstallablePackageUpdate("de.kontoklar.app", 19, "de.other.app", 20))
        assertFalse(isInstallablePackageUpdate("de.kontoklar.app", 19, "de.kontoklar.app", 19))
        assertFalse(isInstallablePackageUpdate("de.kontoklar.app", 19, "de.kontoklar.app", 18))
        assertFalse(isInstallablePackageUpdate("de.kontoklar.app", 19, null, 20))
    }

    @Test fun signingCertificateSetMustMatchExactlyAndCannotBeEmpty() {
        assertTrue(sameSigningCertificates(setOf("cert-a"), setOf("cert-a")))
        assertFalse(sameSigningCertificates(setOf("cert-a"), setOf("cert-b")))
        assertFalse(sameSigningCertificates(setOf("cert-a", "cert-b"), setOf("cert-a")))
        assertFalse(sameSigningCertificates(emptySet(), emptySet()))
    }

    @Test fun dottedReleaseVersionsCompareNumerically() {
        assertTrue(isNewerVersion("0.20.0", "0.19.0"))
        assertTrue(isNewerVersion("1.0.0", "0.99.99"))
        assertFalse(isNewerVersion("0.19.0", "0.19.0"))
        assertFalse(isNewerVersion("0.18.9", "0.19.0"))
    }

    @Test fun updateRedirectsAreRestrictedToGithubHosts() {
        assertTrue(isAllowedUpdateHost("github.com"))
        assertTrue(isAllowedUpdateHost("release-assets.githubusercontent.com"))
        assertFalse(isAllowedUpdateHost("example.com"))
        assertFalse(isAllowedUpdateHost("api.github.com"))
    }
}
