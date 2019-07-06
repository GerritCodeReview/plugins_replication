package com.googlesource.gerrit.plugins.replication;

import static com.google.common.truth.Truth.assertThat;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;

import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Injector;
import com.google.inject.Module;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.easymock.IAnswer;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Before;

abstract class AbstractConfigTest {
  protected final Path sitePath;
  protected final SitePaths sitePaths;
  protected final Destination.Factory destinationFactoryMock;
  protected final Path pluginDataPath;

  static class FakeDestination extends Destination {
    public final DestinationConfiguration config;

    protected FakeDestination(DestinationConfiguration config) {
      super(injectorMock(), null, null, null, null, null, null, null, null, null, config);
      this.config = config;
    }

    private static Injector injectorMock() {
      Injector injector = createNiceMock(Injector.class);
      Injector childInjectorMock = createNiceMock(Injector.class);
      expect(injector.createChildInjector((Module) anyObject())).andReturn(childInjectorMock);
      replay(childInjectorMock);
      replay(injector);
      return injector;
    }
  }

  AbstractConfigTest() throws IOException {
    sitePath = createTempPath("site");
    sitePaths = new SitePaths(sitePath);
    pluginDataPath = createTempPath("data");
    destinationFactoryMock = createMock(Destination.Factory.class);
  }

  @Before
  public void setup() {
    expect(destinationFactoryMock.create(isA(DestinationConfiguration.class)))
        .andAnswer(
            new IAnswer<Destination>() {
              @Override
              public Destination answer() throws Throwable {
                return new FakeDestination((DestinationConfiguration) getCurrentArguments()[0]);
              }
            })
        .anyTimes();
    replay(destinationFactoryMock);
  }

  protected static Path createTempPath(String prefix) throws IOException {
    return java.nio.file.Files.createTempDirectory(prefix);
  }

  protected FileBasedConfig newReplicationConfig() {
    FileBasedConfig replicationConfig =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    return replicationConfig;
  }

  protected void assertThatIsDestination(
      Destination destination, String remoteName, String... remoteUrls) {
    DestinationConfiguration destinationConfig = ((FakeDestination) destination).config;
    assertThat(destinationConfig.getRemoteConfig().getName()).isEqualTo(remoteName);
    assertThat(destinationConfig.getUrls()).containsExactlyElementsIn(remoteUrls);
  }

  protected void assertThatContainsDestination(
      List<Destination> destinations, String remoteName, String... remoteUrls) {
    List<Destination> matchingDestinations =
        destinations.stream()
            .filter(
                (Destination dst) ->
                    ((FakeDestination) dst).config.getRemoteConfig().getName().equals(remoteName))
            .collect(Collectors.toList());

    assertThat(matchingDestinations).isNotEmpty();

    assertThatIsDestination(matchingDestinations.get(0), remoteName, remoteUrls);
  }
}
