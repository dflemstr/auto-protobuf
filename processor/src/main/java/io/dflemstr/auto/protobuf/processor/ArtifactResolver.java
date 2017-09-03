package io.dflemstr.auto.protobuf.processor;

import com.google.common.collect.ImmutableList;
import org.eclipse.aether.artifact.Artifact;

@FunctionalInterface
interface ArtifactResolver {

  ImmutableList<Artifact> resolve(Artifact artifact, String scope)
      throws AutoProtobufArtifactException;
}
