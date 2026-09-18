package dev.lukka.oculus.auth;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.recovery.RecoveryCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;

import java.util.Base64;

public class TotpService {

    private final DefaultSecretGenerator secretGenerator;
    private final DefaultCodeVerifier verifier;
    private final ZxingPngQrGenerator qrGenerator;
    private final RecoveryCodeGenerator recoveryCodeGenerator;

    public TotpService() {
        this.secretGenerator = new DefaultSecretGenerator(64);
        this.verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
        this.verifier.setTimePeriod(30);
        this.verifier.setAllowedTimePeriodDiscrepancy(1);
        this.qrGenerator = new ZxingPngQrGenerator();
        this.recoveryCodeGenerator = new RecoveryCodeGenerator();
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public String getOtpAuthUrl(String secret, String label, String issuer) {
        QrData data = new QrData.Builder()
                .label(label)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        return data.getUri();
    }

    public String generateQrCodeBase64(String secret, String label, String issuer) {
        QrData data = new QrData.Builder()
                .label(label)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        try {
            byte[] imageData = qrGenerator.generate(data);
            return Base64.getEncoder().encodeToString(imageData);
        } catch (QrGenerationException e) {
            throw new RuntimeException("Error generating QR code", e);
        }
    }

    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null) return false;
        return verifier.isValidCode(secret, code.trim());
    }

    public String[] generateRecoveryCodes() {
        return recoveryCodeGenerator.generateCodes(10);
    }
}
