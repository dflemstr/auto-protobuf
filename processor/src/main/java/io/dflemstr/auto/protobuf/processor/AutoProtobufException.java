package io.dflemstr.auto.protobuf.processor;

@SuppressWarnings("unused")
class AutoProtobufException extends RuntimeException {

  AutoProtobufException() {
    super();
  }

  AutoProtobufException(final String message) {
    super(message);
  }

  AutoProtobufException(final String message, final Throwable cause) {
    super(message, cause);
  }

  AutoProtobufException(final Throwable cause) {
    super(cause);
  }

  AutoProtobufException(
      final String message,
      final Throwable cause,
      final boolean enableSuppression,
      final boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
