package io.dflemstr.auto.protobuf.processor;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.File;
import java.text.MessageFormat;
import java.util.List;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoValue
abstract class DefaultArtifactResolver implements ArtifactResolver {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultArtifactResolver.class);
  private static final RemoteRepository CENTRAL_REPOSITORY =
      new RemoteRepository.Builder("central", "default", "http://central.maven.org/maven2/")
          .build();

  DefaultArtifactResolver() {
    // Prevent outside instantiation
  }

  static ArtifactResolver create() {
    return new AutoValue_DefaultArtifactResolver();
  }

  @Override
  public ImmutableList<Artifact> resolve(final Artifact artifact, final String scope)
      throws AutoProtobufArtifactException {
    final RepositorySystem repositorySystem = repositorySystem();
    final RepositorySystemSession session = createRepositorySystemSession();

    final ImmutableList<RemoteRepository> repositories = repositories(session);

    final ArtifactRequest artifactRequest = new ArtifactRequest(artifact, repositories, null);

    try {
      // Check that the artifact exists, or throw exception
      repositorySystem.resolveArtifact(session, artifactRequest);
    } catch (ArtifactResolutionException e) {
      final String message =
          MessageFormat.format("Could not resolve artifact {0}: {1}", artifact, e.getMessage());
      throw new AutoProtobufArtifactException(message, e);
    }

    final CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(new Dependency(artifact, scope));
    collectRequest.setRepositories(repositories);

    final CollectResult collectResult;
    try {
      collectResult = repositorySystem.collectDependencies(session, collectRequest);
    } catch (DependencyCollectionException e) {
      final String message =
          MessageFormat.format(
              "Could not collect artifact dependencies for {0}: {1}", artifact, e.getMessage());
      throw new AutoProtobufArtifactException(message, e);
    }

    final DependencyNode dependencyNode = collectResult.getRoot();
    final DependencyFilter dependencyFilter =
        DependencyFilterUtils.classpathFilter(
            JavaScopes.COMPILE, JavaScopes.PROVIDED, JavaScopes.SYSTEM, JavaScopes.RUNTIME);

    final DependencyRequest dependencyRequest =
        new DependencyRequest(collectRequest, dependencyFilter);
    dependencyRequest.setRoot(dependencyNode);

    final DependencyResult dependencyResult;
    try {
      dependencyResult = repositorySystem.resolveDependencies(session, dependencyRequest);
    } catch (DependencyResolutionException e) {
      final String message =
          MessageFormat.format(
              "Could not resolve artifact dependencies for {0}: {1}", artifact, e.getMessage());
      throw new AutoProtobufArtifactException(message, e);
    }

    final ImmutableList.Builder<Artifact> resultBuilder = ImmutableList.builder();

    for (final ArtifactResult artifactResult : dependencyResult.getArtifactResults()) {
      resultBuilder.add(artifactResult.getArtifact());
    }

    return resultBuilder.build();
  }

  private RepositorySystemSession createRepositorySystemSession()
      throws AutoProtobufArtifactException {
    final DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    session.setLocalRepositoryManager(createLocalRepositoryManager(session));
    session.setProxySelector(proxySelector());

    return session;
  }

  private ImmutableList<RemoteRepository> repositories(final RepositorySystemSession session) {
    final Settings settings = settings();
    final List<RemoteRepository> result = Lists.newArrayList();

    final List<Profile> profiles = settings.getProfiles();
    if (!profiles.isEmpty()) {
      final List<String> activeProfiles = settings.getActiveProfiles();

      for (final Profile profile : profiles) {
        final String profileId = profile.getId();
        if (profileId != null && activeProfiles.contains(profileId)) {
          for (final Repository repository : profile.getRepositories()) {
            result.add(convertRepository(repository, session));
          }
        }
      }
    }

    if (result.isEmpty()) {
      return defaultRepositories();
    } else {
      return ImmutableList.copyOf(result);
    }
  }

  private static ImmutableList<RemoteRepository> defaultRepositories() {
    return ImmutableList.of(CENTRAL_REPOSITORY);
  }

  private static RemoteRepository convertRepository(
      final Repository repository, final RepositorySystemSession session) {
    final RemoteRepository.Builder builder =
        new RemoteRepository.Builder(repository.getId(), "default", repository.getUrl());

    final RemoteRepository tempRepo = builder.build();
    final org.eclipse.aether.repository.Proxy proxy = session.getProxySelector().getProxy(tempRepo);
    builder.setProxy(proxy);
    return builder.build();
  }

  @Memoized
  DefaultProxySelector proxySelector() throws AutoProtobufArtifactException {
    final DefaultProxySelector proxySelector = new DefaultProxySelector();

    final Settings settings = settings();

    for (final Proxy proxy : settings.getProxies()) {
      proxySelector.add(convertProxy(proxy), proxy.getNonProxyHosts());
    }

    return proxySelector;
  }

  private static org.eclipse.aether.repository.Proxy convertProxy(final Proxy proxy) {
    final AuthenticationBuilder auth = new AuthenticationBuilder();

    auth.addUsername(proxy.getUsername()).addPassword(proxy.getPassword());

    return new org.eclipse.aether.repository.Proxy(
        proxy.getProtocol(), proxy.getHost(), proxy.getPort(), auth.build());
  }

  @Memoized
  Settings settings() throws AutoProtobufArtifactException {
    final DefaultSettingsBuilder settingsBuilder =
        new DefaultSettingsBuilderFactory().newInstance();

    final DefaultSettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
    final File settingsPath = settingsPath();
    request.setGlobalSettingsFile(settingsPath);

    final SettingsBuildingResult result;
    try {
      result = settingsBuilder.build(request);
    } catch (SettingsBuildingException e) {
      final String message =
          MessageFormat.format(
              "Could not build settings from file {0}: {1}", settingsPath, e.getMessage());
      throw new AutoProtobufArtifactException(message, e);
    }

    if (!result.getProblems().isEmpty()) {
      // Construct this exception to match the error reporting of the above catch clause
      final SettingsBuildingException exception =
          new SettingsBuildingException(result.getProblems());
      LOG.warn("Problems with settings file {}: {}", settingsPath, exception);
    }

    return result.getEffectiveSettings();
  }

  private LocalRepositoryManager createLocalRepositoryManager(final RepositorySystemSession session)
      throws AutoProtobufArtifactException {
    return repositorySystem()
        .newLocalRepositoryManager(session, new LocalRepository(localRepoPath()));
  }

  @Memoized
  RepositorySystem repositorySystem() {
    final DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();

    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

    return locator.getService(RepositorySystem.class);
  }

  @Memoized
  File settingsPath() {
    return new File(mavenHome(), "settings.xml");
  }

  @Memoized
  File localRepoPath() {
    return new File(mavenHome(), "repository");
  }

  @Memoized
  File mavenHome() {
    final String m2Home = System.getenv("M2_HOME");

    if (m2Home != null) {
      return new File(m2Home);
    } else {
      return new File(System.getProperty("user.home"), ".m2");
    }
  }
}
