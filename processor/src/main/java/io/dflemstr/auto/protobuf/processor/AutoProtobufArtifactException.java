package io.dflemstr.auto.protobuf.processor;

@SuppressWarnings("unused")
class AutoProtobufArtifactException extends AutoProtobufException {

  AutoProtobufArtifactException() {
    super();
  }

  AutoProtobufArtifactException(final String message) {
    super(message);
  }

  AutoProtobufArtifactException(final String message, final Throwable cause) {
    super(message, cause);
  }

  AutoProtobufArtifactException(final Throwable cause) {
    super(cause);
  }

  AutoProtobufArtifactException(
      final String message,
      final Throwable cause,
      final boolean enableSuppression,
      final boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
