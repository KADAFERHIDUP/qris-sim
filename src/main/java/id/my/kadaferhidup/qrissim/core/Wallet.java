package id.my.kadaferhidup.qrissim.core;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The payer's e-wallet. The PIN is kept only as a PBKDF2 hash; three wrong PINs in a row
 * lock the wallet for {@link #LOCK_DURATION}.
 */
public final class Wallet {

    public static final int MAX_ATTEMPTS = 3;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    private static final int ITERATIONS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String id;
    private final String owner;
    private final byte[] salt;
    private final byte[] pinHash;
    private long balance;
    private int failedAttempts;
    private Instant lockedUntil;

    public Wallet(String id, String owner, String pin, long balance) {
        if (pin == null || !pin.matches("\\d{6}")) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "PIN harus 6 angka");
        }
        this.id = id;
        this.owner = owner;
        this.salt = new byte[16];
        RANDOM.nextBytes(salt);
        this.pinHash = hash(pin, salt);
        this.balance = balance;
    }

    /** Checks the PIN, counting failures. Must be called under the service lock. */
    void verifyPin(String pin, Instant now) {
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            long minutes = Math.max(1, Duration.between(now, lockedUntil).toMinutes());
            throw new PaymentException(PaymentException.Code.PIN_LOCKED,
                    "PIN terkunci. Coba lagi dalam " + minutes + " menit");
        }
        boolean ok = pin != null && MessageDigest.isEqual(pinHash, hash(pin, salt));
        if (ok) {
            failedAttempts = 0;
            lockedUntil = null;
            return;
        }
        failedAttempts++;
        if (failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0;
            lockedUntil = now.plus(LOCK_DURATION);
            throw new PaymentException(PaymentException.Code.PIN_LOCKED,
                    "PIN salah 3 kali. Dompet terkunci " + LOCK_DURATION.toMinutes() + " menit");
        }
        int left = MAX_ATTEMPTS - failedAttempts;
        throw new PaymentException(PaymentException.Code.WRONG_PIN, "PIN salah. Sisa " + left + " percobaan");
    }

    void debit(long amount) {
        if (amount > balance) {
            throw new PaymentException(PaymentException.Code.INSUFFICIENT_FUNDS, "Saldo tidak cukup");
        }
        balance -= amount;
    }

    private static byte[] hash(String pin, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 tidak tersedia", e);
        }
    }

    public String id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public long balance() {
        return balance;
    }

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }
}
