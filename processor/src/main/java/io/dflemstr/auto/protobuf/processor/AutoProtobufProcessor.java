package io.dflemstr.auto.protobuf.processor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import com.google.auto.service.AutoService;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.UncheckedTimeoutException;
import io.dflemstr.auto.protobuf.AutoProtobuf;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Processor.class)
public final class AutoProtobufProcessor extends AbstractProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(AutoProtobufProcessor.class);
  // Used for control flow, so we re-use the same exception instance
  private static final SkipElementException SKIP = new SkipElementException();

  // protoc version → command that can be used by ProcessBuilder
  private final Function<String, String> protocCommandSupplier;

  @SuppressWarnings("unused") // For SPI
  public AutoProtobufProcessor() {
    this(DefaultArtifactResolver.create(), DefaultClassifierDetector.create());
  }

  private AutoProtobufProcessor(
      final ArtifactResolver artifactResolver, final Supplier<String> classifierDetector) {
    this(CacheBuilder.newBuilder().build(protocCacheLoader(artifactResolver, classifierDetector)));
  }

  private AutoProtobufProcessor(final Function<String, String> protocCommandSupplier) {
    this.protocCommandSupplier = protocCommandSupplier;
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return ImmutableSet.of(AutoProtobuf.class.getName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  public boolean process(
      final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final Filer filer = processingEnv.getFiler();
    final Messager messager = processingEnv.getMessager();

    for (final Element element : roundEnv.getElementsAnnotatedWith(AutoProtobuf.class)) {

      try {
        if (element.getKind() != ElementKind.PACKAGE) {
          throw fail("Annotated element is not a package", messager, element);
        }

        final AutoProtobuf annotation = element.getAnnotation(AutoProtobuf.class);
        run(annotation, filer, messager, (PackageElement) element);
      } catch (final SkipElementException e) {
        messager.printMessage(
            Diagnostic.Kind.WARNING, "Skipping this element due to errors", element);
      }
    }

    return false;
  }

  private void run(
      final AutoProtobuf annotation,
      final Filer filer,
      final Messager messager,
      final PackageElement element)
      throws SkipElementException {
    final String version = annotation.protoVersion();
    final ImmutableSet<String> includes = ImmutableSet.copyOf(annotation.include());
    final ImmutableSet<String> inputs = ImmutableSet.copyOf(annotation.input());
    final String targetPackageName = element.getQualifiedName().toString();

    final String protocCommand = protocCommand(version, messager, element);

    final List<String> command = Lists.newArrayList();
    command.add(protocCommand);

    final Path stagingDir = createTempDir("protoc-staging-", messager, element);
    final Path outputDir = createTempDir("protoc-output-", messager, element);
    command.add("--proto_path=" + stagingDir);
    command.add("--java_out=" + outputDir);

    for (final String include : includes) {
      final Path path = Paths.get(include);
      final FileObject fileObject = findFile(path, filer, messager, element);
      copyFile(fileObject, stagingDir.resolve(include), messager, element);
    }

    for (final String input : inputs) {
      final Path path = Paths.get(input);
      final FileObject fileObject = findFile(path, filer, messager, element);
      copyFile(fileObject, stagingDir.resolve(input), messager, element);
      command.add(stagingDir.resolve(input).toString());
    }

    final Process process;
    try {
      process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.PIPE)
              .start();
    } catch (IOException e) {
      throw fail("Could not start protoc", e, messager, element);
    }

    final Runnable tailer =
        () -> warnLines("protoc: ", process.getInputStream(), messager, element);
    new Thread(tailer, "protoc-output-reporter").start();

    try {
      if (process.waitFor(10, TimeUnit.SECONDS)) {
        final int exitCode = process.exitValue();
        if (exitCode != 0) {
          final String message =
              MessageFormat.format("Failed to run protoc, exit code {0}", exitCode);
          throw fail(message, messager, element);
        }
      } else {
        throw fail("Timed out while waiting for protoc", messager, element);
      }
    } catch (InterruptedException e) {
      throw fail("Interrupted while running protoc", e, messager, element);
    }

    try (final Stream<Path> paths = Files.walk(outputDir)) {
      final List<Path> javaFiles =
          paths.filter(AutoProtobufProcessor::isJavaFile).collect(toList());
      for (final Path javaPath : javaFiles) {
        final Path relativePath = outputDir.relativize(javaPath);
        final String relativePathString = relativePath.toString();
        final String separator = relativePath.getFileSystem().getSeparator();
        final String className =
            relativePathString
                .substring(0, relativePathString.length() - ".java".length())
                .replace(separator, ".");

        final String classPackage;
        if (className.contains(".")) {
          classPackage = className.substring(0, className.lastIndexOf('.'));
        } else {
          classPackage = "";
        }

        if (!classPackage.equals(targetPackageName)) {
          final String message =
              MessageFormat.format(
                  "Generated class package does not match annotated package: {0} != {1}",
                  classPackage, targetPackageName);
          throw fail(message, messager, element);
        }

        final JavaFileObject fileObject = filer.createSourceFile(className, element);

        try (final OutputStream os = fileObject.openOutputStream()) {
          Files.copy(javaPath, os);
        }
      }

    } catch (IOException e) {
      throw fail("Could not copy files from " + outputDir, e, messager, element);
    }
  }

  @SuppressWarnings("SameParameterValue")
  private static void warnLines(
      final String prefix,
      final InputStream input,
      final Messager messager,
      final PackageElement context) {
    try (final BufferedReader in = new BufferedReader(new InputStreamReader(input, UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        messager.printMessage(Diagnostic.Kind.WARNING, prefix + line, context);
      }
    } catch (IOException e) {
      // Ignore
    }
  }

  private static Path createTempDir(
      final String prefix, final Messager messager, final Element element)
      throws SkipElementException {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw fail("Could not create temporary directory", e, messager, element);
    }
  }

  private String protocCommand(
      final String protocVersion, final Messager messager, final Element element)
      throws SkipElementException {
    final Throwable throwable;

    try {
      return protocCommandSupplier.apply(protocVersion);
    } catch (final UncheckedExecutionException
        | UncheckedIOException
        | UncheckedTimeoutException e) {
      throwable = e.getCause();
    } catch (final Exception e) {
      throwable = e;
    }

    throw fail("Could not find protoc version " + protocVersion, throwable, messager, element);
  }

  private static void copyFile(
      final FileObject source,
      final Path destination,
      final Messager messager,
      final Element element)
      throws SkipElementException {
    final Path parent = destination.getParent();

    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw fail("Could not create directory " + parent, e, messager, element);
      }
    }

    try (final InputStream is = source.openInputStream()) {
      Files.copy(is, destination);
    } catch (IOException e) {
      throw fail("Could not create file " + destination, e, messager, element);
    }
  }

  private static FileObject findFile(
      final Path path, final Filer filer, final Messager messager, final Element context)
      throws SkipElementException {

    // Convert the relative path to a classpath reference
    final String pkg;
    final Path parent = path.getParent();
    if (parent == null) {
      pkg = "";
    } else {
      pkg = parent.toString().replace(path.getFileSystem().getSeparator(), ".");
    }
    final String relativeName = path.getFileName().toString();

    try {
      try {
        return filer.getResource(StandardLocation.CLASS_PATH, pkg, relativeName);
      } catch (NoSuchFileException e) {
        // Ignore
      }
      return filer.getResource(StandardLocation.CLASS_OUTPUT, pkg, relativeName);
    } catch (IOException e) {
      throw fail("Could not open file " + path, e, messager, context);
    }
  }

  @CheckReturnValue
  private static SkipElementException fail(
      final String message,
      final Throwable throwable,
      final Messager messager,
      final Element context)
      throws SkipElementException {
    LOG.error(message, throwable);
    final String messageAndCause =
        MessageFormat.format("{0}: {1}", message, throwable.getMessage());
    return fail(messageAndCause, messager, context);
  }

  @CheckReturnValue
  private static SkipElementException fail(
      final String reason, final Messager messager, final Element context)
      throws SkipElementException {
    messager.printMessage(Diagnostic.Kind.ERROR, reason, context);
    throw SKIP;
  }

  private static boolean isJavaFile(final Path path) {
    return path.getFileName().toString().endsWith(".java");
  }

  private static CacheLoader<String, String> protocCacheLoader(
      final ArtifactResolver artifactResolver, final Supplier<String> classifierSupplier) {
    return CacheLoader.from(
        version -> findProtocCommand(version, classifierSupplier, artifactResolver));
  }

  private static String findProtocCommand(
      final @Nullable String protocVersion,
      final Supplier<String> classifierSupplier,
      final ArtifactResolver artifactResolver)
      throws AutoProtobufException {
    if (protocVersion == null) {
      throw new AutoProtobufException("The protobuf version must not be null");
    } else {
      final String classifier = classifierSupplier.get();
      final String coords =
          String.format("com.google.protobuf:protoc:exe:%s:%s", classifier, protocVersion);
      final ImmutableList<Artifact> candidateArtifacts =
          artifactResolver.resolve(new DefaultArtifact(coords), "compile");
      final Artifact artifact = Iterables.getOnlyElement(candidateArtifacts);
      final File file = artifact.getFile();

      ensureExecutable(file);

      return file.getAbsolutePath();
    }
  }

  private static void ensureExecutable(final File file) throws AutoProtobufException {
    if (!(file.canExecute() || file.setExecutable(true))) {
      throw new AutoProtobufException("Failed to make file executable: " + file);
    }
  }

  private static class SkipElementException extends Exception {}
}
