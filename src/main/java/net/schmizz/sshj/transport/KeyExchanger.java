/*
 * Copyright (C)2009 - SSHJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.schmizz.sshj.transport;

import net.schmizz.concurrent.ErrorDeliveryUtil;
import net.schmizz.concurrent.Event;
import net.schmizz.sshj.common.*;
import net.schmizz.sshj.transport.cipher.Cipher;
import net.schmizz.sshj.transport.compression.Compression;
import net.schmizz.sshj.transport.digest.Digest;
import net.schmizz.sshj.transport.kex.KeyExchange;
import net.schmizz.sshj.transport.mac.MAC;
import net.schmizz.sshj.transport.verification.AlgorithmsVerifier;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Algorithm negotiation and key exchange. */
final class KeyExchanger
        implements SSHPacketHandler, ErrorNotifiable {

    private static enum Expected {
        /** we have sent or are sending KEXINIT, and expect the server's KEXINIT */
        KEXINIT,
        /** we are expecting some followup data as part of the exchange */
        FOLLOWUP,
        /** we are expecting SSH_MSG_NEWKEYS */
        NEWKEYS,
    }

    private final Logger log;
    private final TransportImpl transport;

    /**
     * {@link HostKeyVerifier#verify(String, int, java.security.PublicKey)} is invoked by {@link #verifyHost(PublicKey)}
     * when we are ready to verify the the server's host key.
     */
    private final Queue<HostKeyVerifier> hostVerifiers = new LinkedList<HostKeyVerifier>();

    private final Queue<AlgorithmsVerifier> algorithmVerifiers = new LinkedList<AlgorithmsVerifier>();

    private final AtomicBoolean kexOngoing = new AtomicBoolean();

    /** What we are expecting from the next packet */
    private Expected expected = Expected.KEXINIT;

    /** Instance of negotiated key exchange algorithm */
    private KeyExchange kex;

    /** Computed session ID */
    private byte[] sessionID;

    private Proposal clientProposal;
    private NegotiatedAlgorithms negotiatedAlgs;

    private final Event<TransportException> kexInitSent;

    private final Event<TransportException> done;

    KeyExchanger(TransportImpl trans) {
        this.transport = trans;
        log = trans.getConfig().getLoggerFactory().getLogger(getClass());
        kexInitSent = new Event<TransportException>("kexinit sent", TransportException.chainer, trans.getConfig().getLoggerFactory());

        /*
         * Use TransportImpl's writeLock, since TransportImpl.write() may wait on this event and the lock should
         * be released while waiting.
         */
        this.done = new Event<TransportException>("kex done", TransportException.chainer, trans.getWriteLock(), trans.getConfig().getLoggerFactory());
    }

    /**
     * Add a callback for host key verification.
     * <p/>
     * Any of the {@link HostKeyVerifier} implementations added this way can deem a host key to be acceptable, allowing
     * key exchange to successfully complete. Otherwise, a {@link TransportException} will result during key exchange.
     *
     * @param hkv object whose {@link HostKeyVerifier#verify} method will be invoked
     */
    synchronized void addHostKeyVerifier(HostKeyVerifier hkv) {
        hostVerifiers.add(hkv);
    }

    synchronized void addAlgorithmsVerifier(AlgorithmsVerifier verifier) {
        algorithmVerifiers.add(verifier);
    }

    /**
     * Returns the session identifier computed during key exchange.
     *
     * @return session identifier as a byte array
     */
    byte[] getSessionID() {
        return Arrays.copyOf(sessionID, sessionID.length);
    }

    /** @return whether key exchange has been completed */
    boolean isKexDone() {
        return done.isSet();
    }

    /** @return whether key exchange is currently ongoing */
    boolean isKexOngoing() {
        return kexOngoing.get();
    }

    /**
     * Starts key exchange by sending a {@code SSH_MSG_KEXINIT} packet. Key exchange needs to be done once mandatorily
     * after initializing the {@link Transport} for it to be usable and may be initiated at any later point e.g. if
     * {@link Transport#getConfig() algorithms} have changed and should be renegotiated.
     *
     * @param waitForDone whether should block till key exchange completed
     *
     * @throws TransportException if there is an error during key exchange
     * @see {@link Transport#setTimeoutMs} for setting timeout for kex
     */
    void startKex(boolean waitForDone)
            throws TransportException {
        if (!kexOngoing.getAndSet(true)) {
            if (isKeyExchangeAllowed()) {
                log.debug("Initiating key exchange");
                done.clear();
                sendKexInit();
            } else {
                kexOngoing.set(false);
            }
        }
        if (waitForDone)
            waitForDone();
    }

    /**
     * Key exchange can be initiated exactly once while connecting or later after authentication when re-keying.
     */
    private boolean isKeyExchangeAllowed() {
        return !isKexDone() || transport.isAuthenticated();
    }

    void waitForDone()
            throws TransportException {
        done.await(transport.getTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private synchronized void ensureKexOngoing()
            throws TransportException {
        if (!isKexOngoing())
            throw new TransportException(DisconnectReason.PROTOCOL_ERROR,
                                         "Key exchange packet received when key exchange was not ongoing");
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static void ensureReceivedMatchesExpected(Message got, Message expected)
            throws TransportException {
        if (got != expected)
            throw new TransportException(DisconnectReason.PROTOCOL_ERROR, "Was expecting " + expected);
    }

    /**
     * Sends SSH_MSG_KEXINIT and sets the {@link #kexInitSent} event.
     *
     * @throws TransportException
     */
    private void sendKexInit()
            throws TransportException {
        log.debug("Sending SSH_MSG_KEXINIT");
        List<String> knownHostAlgs = findKnownHostAlgs(transport.getRemoteHost(), transport.getRemotePort());
        clientProposal = new Proposal(transport.getConfig(), knownHostAlgs);
        transport.write(clientProposal.getPacket());
        kexInitSent.set();
    }

    private List<String> findKnownHostAlgs(String hostname, int port) {
        for (HostKeyVerifier hkv : hostVerifiers) {
            List<String> keyTypes = hkv.findExistingAlgorithms(hostname, port);
            if (keyTypes != null && !keyTypes.isEmpty()) {
                return keyTypes;
            }
        }
        return Collections.emptyList();
    }

    private void sendNewKeys()
            throws TransportException {
        log.debug("Sending SSH_MSG_NEWKEYS");
        transport.write(new SSHPacket(Message.NEWKEYS));
    }

    /**
     * Tries to validate host key with all the host key verifiers known to this instance ( {@link #hostVerifiers})
     *
     * @param key the host key to verify
     *
     * @throws TransportException
     */
    private synchronized void verifyHost(PublicKey key)
            throws TransportException {
        for (HostKeyVerifier hkv : hostVerifiers) {
            log.debug("Trying to verify host key with {}", hkv);
            if (hkv.verify(transport.getRemoteHost(), transport.getRemotePort(), key))
                return;
        }
        log.error("Disconnecting because none of the configured Host key verifiers ({}) could verify '{}' host key with fingerprint {} for {}:{}",
                hostVerifiers,
                KeyType.fromKey(key),
                SecurityUtils.getFingerprint(key),
                transport.getRemoteHost(),
                transport.getRemotePort());

        throw new TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE,
                                     "Could not verify `" + KeyType.fromKey(key)
                                             + "` host key with fingerprint `" + SecurityUtils.getFingerprint(key)
                                             + "` for `" + transport.getRemoteHost()
                                             + "` on port " + transport.getRemotePort());
    }

    private void setKexDone() {
        kexOngoing.set(false);
        kexInitSent.clear();
        done.set();
    }

    private void gotKexInit(SSHPacket buf)
            throws TransportException {
        buf.rpos(buf.rpos() - 1);
        final Proposal serverProposal = new Proposal(buf);
        negotiatedAlgs = clientProposal.negotiate(serverProposal);
        log.debug("Negotiated algorithms: {}", negotiatedAlgs);
        for(AlgorithmsVerifier v: algorithmVerifiers) {
            log.debug("Trying to verify algorithms with {}", v);
            if(!v.verify(negotiatedAlgs)) {
                throw new TransportException(DisconnectReason.KEY_EXCHANGE_FAILED,
                        "Failed to verify negotiated algorithms `" + negotiatedAlgs + "`");
            }
        }
        kex = Factory.Named.Util.create(transport.getConfig().getKeyExchangeFactories(),
                                        negotiatedAlgs.getKeyExchangeAlgorithm());
        transport.setHostKeyAlgorithm(Factory.Named.Util.create(transport.getConfig().getKeyAlgorithms(),
                                      negotiatedAlgs.getSignatureAlgorithm()));

        try {
            kex.init(transport,
                     transport.getServerID(), transport.getClientID(),
                     serverProposal.getPacket().getCompactData(), clientProposal.getPacket().getCompactData());
        } catch (GeneralSecurityException e) {
            throw new TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, e);
        }
    }

    /**
     * Private method used while putting new keys into use that will resize the key used to initialize the cipher to the
     * needed length.
     *
     * @param E         the key to resize
     * @param blockSize the cipher block size
     * @param hash      the hash algorithm
     * @param K         the key exchange K parameter
     * @param H         the key exchange H parameter
     *
     * @return the resized key
     */
    private static byte[] resizedKey(byte[] E, int blockSize, Digest hash, BigInteger K, byte[] H) {
        while (blockSize > E.length) {
            Buffer.PlainBuffer buffer = new Buffer.PlainBuffer().putMPInt(K).putRawBytes(H).putRawBytes(E);
            hash.update(buffer.array(), 0, buffer.available());
            byte[] foo = hash.digest();
            byte[] bar = new byte[E.length + foo.length];
            System.arraycopy(E, 0, bar, 0, E.length);
            System.arraycopy(foo, 0, bar, E.length, foo.length);
            E = bar;
        }
        return E;
    }

    /* See Sec. 7.2. "Output from Key Exchange", RFC 4253 */

    private void gotNewKeys() {
        final Digest hash = kex.getHash();

        final byte[] H = kex.getH();

        if (sessionID == null)
            // session id is 'H' from the first key exchange and does not change thereafter
            sessionID = H;

        final Buffer.PlainBuffer hashInput = new Buffer.PlainBuffer()
                .putMPInt(kex.getK())
                .putRawBytes(H)
                .putByte((byte) 0) // <placeholder>
                .putRawBytes(sessionID);
        final int pos = hashInput.available() - sessionID.length - 1; // Position of <placeholder>

        hashInput.array()[pos] = 'A';
        hash.update(hashInput.array(), 0, hashInput.available());
        final byte[] initialIV_C2S = hash.digest();

        hashInput.array()[pos] = 'B';
        hash.update(hashInput.array(), 0, hashInput.available());
        final byte[] initialIV_S2C = hash.digest();

        hashInput.array()[pos] = 'C';
        hash.update(hashInput.array(), 0, hashInput.available());
        final byte[] encryptionKey_C2S = hash.digest();

        hashInput.array()[pos] = 'D';
        hash.update(hashInput.array(), 0, hashInput.available());
        final byte[] encryptionKey_S2C = hash.digest();

        hashInput.array()[pos] = 'E';
        hash.update(hashInput.array(), 0, hashInput.available());
        final byte[] integrityKey_C2S = hash.digest();

        hashInput.array()[pos] = 'F';
        hash.update(hashInput.array(), 0, hashInput.available());
        final byte[] integrityKey_S2C = hash.digest();

        final Cipher cipher_C2S = Factory.Named.Util.create(transport.getConfig().getCipherFactories(),
                                                            negotiatedAlgs.getClient2ServerCipherAlgorithm());
        cipher_C2S.init(Cipher.Mode.Encrypt,
                        resizedKey(encryptionKey_C2S, cipher_C2S.getBlockSize(), hash, kex.getK(), kex.getH()),
                        initialIV_C2S);

        final Cipher cipher_S2C = Factory.Named.Util.create(transport.getConfig().getCipherFactories(),
                                                            negotiatedAlgs.getServer2ClientCipherAlgorithm());
        cipher_S2C.init(Cipher.Mode.Decrypt,
                        resizedKey(encryptionKey_S2C, cipher_S2C.getBlockSize(), hash, kex.getK(), kex.getH()),
                        initialIV_S2C);

        /*
         * For AES-GCM ciphers, MAC will also be AES-GCM, so it is handled by the cipher itself.
         * In that case, both s2c and c2s MACs are ignored.
         *
         * Refer to RFC5647 Section 5.1
         */
        MAC mac_C2S = null;
        if(cipher_C2S.getAuthenticationTagSize() == 0) {
            mac_C2S = Factory.Named.Util.create(transport.getConfig().getMACFactories(), negotiatedAlgs
                    .getClient2ServerMACAlgorithm());
            mac_C2S.init(resizedKey(integrityKey_C2S, mac_C2S.getBlockSize(), hash, kex.getK(), kex.getH()));
        }

        MAC mac_S2C = null;
        if(cipher_S2C.getAuthenticationTagSize() == 0) {
            mac_S2C  = Factory.Named.Util.create(transport.getConfig().getMACFactories(),
                    negotiatedAlgs.getServer2ClientMACAlgorithm());
            mac_S2C.init(resizedKey(integrityKey_S2C, mac_S2C.getBlockSize(), hash, kex.getK(), kex.getH()));
        }

        final Compression compression_S2C =
                Factory.Named.Util.create(transport.getConfig().getCompressionFactories(),
                                          negotiatedAlgs.getServer2ClientCompressionAlgorithm());
        final Compression compression_C2S =
                Factory.Named.Util.create(transport.getConfig().getCompressionFactories(),
                                          negotiatedAlgs.getClient2ServerCompressionAlgorithm());

        transport.getEncoder().setAlgorithms(cipher_C2S, mac_C2S, compression_C2S);
        transport.getDecoder().setAlgorithms(cipher_S2C, mac_S2C, compression_S2C);
    }

    @Override
    public void handle(Message msg, SSHPacket buf)
            throws TransportException {
        switch (expected) {

            case KEXINIT:
                ensureReceivedMatchesExpected(msg, Message.KEXINIT);
                log.debug("Received SSH_MSG_KEXINIT");
                startKex(false); // Will start key exchange if not already on
                /*
                * We block on this event to prevent a race condition where we may have received a SSH_MSG_KEXINIT before
                * having sent the packet ourselves (would cause gotKexInit() to fail)
                */
                kexInitSent.await(transport.getTimeoutMs(), TimeUnit.MILLISECONDS);
                gotKexInit(buf);
                expected = Expected.FOLLOWUP;
                break;

            case FOLLOWUP:
                ensureKexOngoing();
                log.debug("Received kex followup data");
                try {
                    if (kex.next(msg, buf)) {
                        verifyHost(kex.getHostKey());
                        sendNewKeys();
                        expected = Expected.NEWKEYS;
                    }
                } catch (GeneralSecurityException e) {
                    throw new TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, e);
                }
                break;

            case NEWKEYS:
                ensureReceivedMatchesExpected(msg, Message.NEWKEYS);
                ensureKexOngoing();
                log.debug("Received SSH_MSG_NEWKEYS");
                gotNewKeys();
                setKexDone();
                expected = Expected.KEXINIT;
                break;

            default:
                assert false;

        }
    }

    @Override
    public void notifyError(SSHException error) {
        log.debug("Got notified of {}", error.toString());
        ErrorDeliveryUtil.alertEvents(error, kexInitSent, done);
    }

}
