package id.my.kadaferhidup.qrissim.qris;

/** A payload that does not follow the QRIS / EMVCo MPM format. */
public class QrisException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QrisException(String message) {
        super(message);
    }
}
