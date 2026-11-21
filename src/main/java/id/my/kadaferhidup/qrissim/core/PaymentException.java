package id.my.kadaferhidup.qrissim.core;

/** A payment request the simulator refuses, with a code the API maps to an HTTP status. */
public class PaymentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_REQUEST(400),
        INVALID_QR(400),
        UNKNOWN_ISSUER(400),
        AMOUNT_MISMATCH(400),
        AMOUNT_OUT_OF_RANGE(400),
        WRONG_PIN(401),
        FORBIDDEN(403),
        PIN_LOCKED(423),
        NOT_FOUND(404),
        ALREADY_PAID(409),
        EXPIRED(409),
        CANCELLED(409),
        IDEMPOTENCY_CONFLICT(409),
        INSUFFICIENT_FUNDS(422);

        private final int httpStatus;

        Code(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Code code;

    public PaymentException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
