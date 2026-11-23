package edu.usc.csci201.team3;

/** An expected failure whose message is safe to show to the user. */
final class ApiException extends RuntimeException {
    private final int status;

    ApiException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    int status() {
        return status;
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    static ApiException unauthorized() {
        return new ApiException(401, "Please log in to continue.");
    }

    static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    static ApiException notFound(String what) {
        return new ApiException(404, what + " not found");
    }

    static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
