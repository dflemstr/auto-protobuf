package io.dflemstr.auto.protobuf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies that <a href="https://github.com/dflemstr/auto-protobuf">AutoProtobuf</a> should
 * generate {@code protobuf-java}-compatible schema classes in this package. The annotated package
 * must match the {@code option java_package} in the Protobuf schema files.
 *
 * <p>An example (in a {@code package-info.java} file):
 *
 * <pre>
 * &#64;AutoProtobuf("myorg/account.proto")
 * package com.myorg.account;
 *
 * import io.dflemstr.auto.protobuf.AutoProtobuf;
 * </pre>
 */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.CLASS)
@Documented
public @interface AutoProtobuf {

  /**
   * The version of protoc to use. This should preferably match the {@code major.minor} version of
   * the {@code protobuf-java} dependency being used, but note that the full {@code
   * major.minor.patch} version needs to be specified.
   */
  String protoVersion();

  /** Schema ({@code .proto}) files to compile, relative to the classpath. */
  String[] input();

  /** Schema ({@code .proto}) files to make available for inclusion, relative to the classpath. */
  String[] include() default {};
}
