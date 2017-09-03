/*
 * Copyright 2014 Trustin Heuiseung Lee.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.dflemstr.auto.protobuf.processor;

import java.util.Locale;
import java.util.Properties;
import java.util.function.Supplier;
import javax.annotation.Nullable;

final class DefaultClassifierDetector implements Supplier<String> {

  private static final String UNKNOWN = "unknown";

  private final Properties props;

  private DefaultClassifierDetector(final Properties props) {
    this.props = props;
  }

  static DefaultClassifierDetector create() {
    return new DefaultClassifierDetector(System.getProperties());
  }

  @Override
  public String get() {
    final String osName = props.getProperty("os.name");
    final String osArch = props.getProperty("os.arch");

    final String detectedName = normalizeOs(osName);
    final String detectedArch = normalizeArch(osArch);

    return detectedName + '-' + detectedArch;
  }

  private static String normalizeOs(@Nullable String value) {
    value = normalize(value);
    if (value.startsWith("aix")) {
      return "aix";
    }
    if (value.startsWith("hpux")) {
      return "hpux";
    }
    if (value.startsWith("os400")) {
      // Avoid the names such as os4000
      if (value.length() <= 5 || !Character.isDigit(value.charAt(5))) {
        return "os400";
      }
    }
    if (value.startsWith("linux")) {
      return "linux";
    }
    if (value.startsWith("macosx") || value.startsWith("osx")) {
      return "osx";
    }
    if (value.startsWith("freebsd")) {
      return "freebsd";
    }
    if (value.startsWith("openbsd")) {
      return "openbsd";
    }
    if (value.startsWith("netbsd")) {
      return "netbsd";
    }
    if (value.startsWith("solaris") || value.startsWith("sunos")) {
      return "sunos";
    }
    if (value.startsWith("windows")) {
      return "windows";
    }

    return UNKNOWN;
  }

  private static String normalizeArch(@Nullable String value) {
    value = normalize(value);
    if (value.matches("^(x8664|amd64|ia32e|em64t|x64)$")) {
      return "x86_64";
    }
    if (value.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) {
      return "x86_32";
    }
    if (value.matches("^(ia64|itanium64)$")) {
      return "itanium_64";
    }
    if (value.matches("^(sparc|sparc32)$")) {
      return "sparc_32";
    }
    if (value.matches("^(sparcv9|sparc64)$")) {
      return "sparc_64";
    }
    if (value.matches("^(arm|arm32)$")) {
      return "arm_32";
    }
    if ("aarch64".equals(value)) {
      return "aarch_64";
    }
    if (value.matches("^(ppc|ppc32)$")) {
      return "ppc_32";
    }
    if ("ppc64".equals(value)) {
      return "ppc_64";
    }
    if ("ppc64le".equals(value)) {
      return "ppcle_64";
    }
    if ("s390".equals(value)) {
      return "s390_32";
    }
    if ("s390x".equals(value)) {
      return "s390_64";
    }

    return UNKNOWN;
  }

  private static String normalize(@Nullable String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
  }
}
