package org.bouncycastle.crypto.tls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.Signer;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.io.SignerInputStream;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.math.ec.ECPoint;

/**
 * ECDHE key exchange (see RFC 4492)
 */
class TlsECDHEKeyExchange extends TlsECDHKeyExchange {

    protected TlsSignerCredentials serverCredentials = null;

    TlsECDHEKeyExchange(int keyExchange) {
        super(keyExchange);
    }

    public void processServerCredentials(TlsCredentials serverCredentials) throws IOException {

        if (!(serverCredentials instanceof TlsSignerCredentials)) {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        processServerCertificate(serverCredentials.getCertificate());

        this.serverCredentials = (TlsSignerCredentials) serverCredentials;
    }

    public byte[] generateServerKeyExchange() throws IOException {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // TODO Allow specification of ECDH parameters to use
        short curveType = ECCurveType.named_curve;
        int namedCurve = NamedCurve.secp256r1;
        ECDomainParameters curve_params = NamedCurve.getECParameters(namedCurve);

        ECKeyPairGenerator kpg = new ECKeyPairGenerator();
        kpg.init(new ECKeyGenerationParameters(curve_params, context.getSecureRandom()));
        AsymmetricCipherKeyPair kp = kpg.generateKeyPair();

        ECPoint Q = ((ECPublicKeyParameters) kp.getPublic()).getQ();
        // TODO Check the point encoding we should use
        byte[] publicBytes = Q.getEncoded();

        TlsUtils.writeUint8(curveType, buf);
        TlsUtils.writeUint16(namedCurve, buf);
        TlsUtils.writeOpaque8(publicBytes, buf);

        byte[] digestInput = buf.toByteArray();

        Digest d = new CombinedHash();
        SecurityParameters securityParameters = context.getSecurityParameters();
        d.update(securityParameters.clientRandom, 0, securityParameters.clientRandom.length);
        d.update(securityParameters.serverRandom, 0, securityParameters.serverRandom.length);
        d.update(digestInput, 0, digestInput.length);

        byte[] hash = new byte[d.getDigestSize()];
        d.doFinal(hash, 0);

        byte[] sigBytes = serverCredentials.generateCertificateSignature(hash);
        TlsUtils.writeOpaque16(sigBytes, buf);

        return buf.toByteArray();
    }

    public void processServerKeyExchange(InputStream input) throws IOException {

        SecurityParameters securityParameters = context.getSecurityParameters();

        Signer signer = initSigner(tlsSigner, securityParameters);
        InputStream sigIn = new SignerInputStream(input, signer);

        short curveType = TlsUtils.readUint8(sigIn);
        ECDomainParameters curve_params;

        // Currently, we only support named curves
        if (curveType == ECCurveType.named_curve) {
            int namedCurve = TlsUtils.readUint16(sigIn);

            // TODO Check namedCurve is one we offered?

            curve_params = NamedCurve.getECParameters(namedCurve);
        } else {
            // TODO Add support for explicit curve parameters (read from sigIn)

            throw new TlsFatalAlert(AlertDescription.handshake_failure);
        }

        byte[] publicBytes = TlsUtils.readOpaque8(sigIn);

        byte[] sigByte = TlsUtils.readOpaque16(input);
        if (!signer.verifySignature(sigByte)) {
            throw new TlsFatalAlert(AlertDescription.bad_certificate);
        }

        // TODO Check curve_params not null

        ECPoint Q = curve_params.getCurve().decodePoint(publicBytes);

        this.ecAgreeServerPublicKey = validateECPublicKey(new ECPublicKeyParameters(Q, curve_params));
    }

    public void validateCertificateRequest(CertificateRequest certificateRequest)
        throws IOException {
        /*
         * RFC 4492 3. [...] The ECDSA_fixed_ECDH and RSA_fixed_ECDH mechanisms are usable with
         * ECDH_ECDSA and ECDH_RSA. Their use with ECDHE_ECDSA and ECDHE_RSA is prohibited because
         * the use of a long-term ECDH client key would jeopardize the forward secrecy property of
         * these algorithms.
         */
        short[] types = certificateRequest.getCertificateTypes();
        for (int i = 0; i < types.length; ++i) {
            switch (types[i]) {
            case ClientCertificateType.rsa_sign:
            case ClientCertificateType.dss_sign:
            case ClientCertificateType.ecdsa_sign:
                break;
            default:
                throw new TlsFatalAlert(AlertDescription.illegal_parameter);
            }
        }
    }

    public void processClientCredentials(TlsCredentials clientCredentials) throws IOException {
        if (clientCredentials instanceof TlsSignerCredentials) {
            // OK
        } else {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }
    }

    protected Signer initSigner(TlsSigner tlsSigner, SecurityParameters securityParameters) {
        Signer signer = tlsSigner.createVerifyer(this.serverPublicKey);
        signer.update(securityParameters.clientRandom, 0, securityParameters.clientRandom.length);
        signer.update(securityParameters.serverRandom, 0, securityParameters.serverRandom.length);
        return signer;
    }
}
