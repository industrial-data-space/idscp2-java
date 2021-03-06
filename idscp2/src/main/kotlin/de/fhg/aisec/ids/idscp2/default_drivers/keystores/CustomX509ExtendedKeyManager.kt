/*-
 * ========================LICENSE_START=================================
 * idscp2
 * %%
 * Copyright (C) 2021 Fraunhofer AISEC
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package de.fhg.aisec.ids.idscp2.default_drivers.keystores

import org.slf4j.LoggerFactory
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.HashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * A custom X590ExtendedKeyManager, that allows to choose a TrustStore entry by a given certificate alias and
 * delegates all other function calls to given default X509ExtendedKeyManager
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
class CustomX509ExtendedKeyManager internal constructor(
    private val certAlias: String,
    private val keyType: String,
    private val delegate: X509ExtendedKeyManager
) : X509ExtendedKeyManager() {
    // server and client aliases are cached in a private context (in entryCacheMap) by the X509ExtendedKeyManager
    // implementation. Therefore, getServerAliases() / getClientAliases() returns only uncached aliases since the
    // update on java 11. As we have to check in chooseClientAliases() and chooseServerAlias() if the alias exists in
    // the keystore and we cannot access the cached aliases without an overwritten X509KeyManagerImpl instance, we will
    // also cache the aliases and its properties in the following HashMap.
    private val cachedAliases = HashMap<String, CachedAliasValue?>()

    override fun getClientAliases(keyType: String, issuers: Array<Principal>?): Array<String> {
        val clientAliases = delegate.getClientAliases(keyType, issuers)
        for (alias in clientAliases) {
            // TODO get issuer
            cachedAliases.putIfAbsent(alias, CachedAliasValue(keyType, null))
        }
        return clientAliases
    }

    /** returns an existing certAlias that matches one of the given KeyTypes, or null;
     called only by client in TLS handshake */
    override fun chooseClientAlias(keyTypes: Array<String>, issuers: Array<Principal>?, socket: Socket): String? {
        if (listOf(*keyTypes).contains(keyType)) {
            if (cachedAliases[certAlias]?.match(keyType, issuers) == true ||
                listOf(*getClientAliases(keyType, issuers)).contains(certAlias)
            ) {
                if (LOG.isTraceEnabled) {
                    LOG.trace("CertificateAlias is {}", certAlias)
                }
                return certAlias
            } else {
                if (LOG.isTraceEnabled) {
                    LOG.trace("certAlias '{}' was not found in keystore", certAlias)
                }
            }
        } else if (LOG.isTraceEnabled) {
            if (LOG.isTraceEnabled) {
                LOG.trace(
                    "Different keyType '{}' in chooseClientAlias() in CustomX509ExtendedKeyManager, expected '{}'",
                    keyType, this.keyType
                )
            }
        }
        return null
    }

    override fun getServerAliases(keyType: String, issuers: Array<Principal>?): Array<String> {
        val serverAliases = delegate.getServerAliases(keyType, issuers)
        for (alias in serverAliases) {
            // TODO get issuer
            cachedAliases.putIfAbsent(alias, CachedAliasValue(keyType, null))
        }
        return serverAliases
    }

    /**
     * returns an existing certAlias that matches the given KeyType, or null;
     * called only by server in TLS handshake
     */
    override fun chooseServerAlias(keyType: String, issuers: Array<Principal>?, socket: Socket): String? {
        if (keyType == this.keyType) {
            if (cachedAliases[certAlias]?.match(keyType, issuers) == true ||
                listOf(*getServerAliases(keyType, issuers)).contains(certAlias)
            ) {
                if (LOG.isTraceEnabled) {
                    LOG.trace("CertificateAlias is {}", certAlias)
                }
                return certAlias
            } else {
                if (LOG.isTraceEnabled) {
                    LOG.trace("certAlias '{}' was not found in keystore", certAlias)
                }
            }
        } else if (LOG.isTraceEnabled) {
            if (LOG.isTraceEnabled) {
                LOG.trace(
                    "Different keyType '{}' in chooseServerAlias() in CustomX509ExtendedKeyManager, expected '{}'",
                    keyType, this.keyType
                )
            }
        }
        return null
    }

    /** returns the certificate chain of a given certificateAlias;
     * called by client and server in TLS Handshake after alias was chosen
     */
    override fun getCertificateChain(certAlias: String): Array<X509Certificate>? {
        return if (certAlias == this.certAlias) {
            delegate.getCertificateChain(certAlias)
        } else {
            LOG.warn(
                "Different certAlias '{}' in getCertificateChain() in class X509ExtendedKeyManager, " +
                    "expected: '{}'",
                certAlias, this.certAlias
            )
            null
        }
    }

    /** returns a privateKey of a given certificateAlias;
     *  called by client and server in TLS Handshake after alias was chosen */
    override fun getPrivateKey(certAlias: String): PrivateKey? {
        return if (certAlias == this.certAlias) {
            delegate.getPrivateKey(certAlias)
        } else {
            LOG.warn(
                "Different certAlias '{}' in getPrivateKey() in class X509ExtendedKeyManager, expected '{}'",
                certAlias, this.certAlias
            )

            null
        }
    }

    override fun chooseEngineClientAlias(
        keyType: Array<String>,
        issuers: Array<Principal>,
        sslEngine: SSLEngine
    ): String {
        return delegate.chooseEngineClientAlias(keyType, issuers, sslEngine)
    }

    override fun chooseEngineServerAlias(keyType: String, issuers: Array<Principal>, sslEngine: SSLEngine): String {
        return delegate.chooseEngineServerAlias(keyType, issuers, sslEngine)
    }

    /**
     * @param keyType Name of the algorithm associated with the key
     * @param issuer The certificate issuer
     */
    private class CachedAliasValue(
        private val keyType: String,
        private val issuer: Principal?
    ) {
        /**
         * This method is called for a given certificate alias and checks if the corresponding
         * cached alias entry (contains keytype for certAlias and issuer of thee certificate)
         * matches the requested conditions in the TLS handshake.
         *
         * It must enforce checking if the certAlias belongs to a valid key algorithm type name,
         * e.g. 'RSA' or 'EC' and it must check if the certificate issuer is one of the accepted
         * issuer from the given principals list.
         *
         * returns true, if the keyAlias fulfills the requirements
         */
        // FIXME currently all issuers are allowed
        fun match(keyType: String, issuers: Array<Principal>?) =
            this.keyType == keyType && (issuers == null || listOf(*issuers).contains(issuer))
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(CustomX509ExtendedKeyManager::class.java)
    }
}
