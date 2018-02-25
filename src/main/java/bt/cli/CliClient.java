/*
 * Copyright (c) 2018 Andrei Tomashpolskiy.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.cli;

import bt.Bt;
import bt.BtClientBuilder;
import bt.data.Storage;
import bt.data.file.FileSystemStorage;
import bt.dht.DHTConfig;
import bt.dht.DHTModule;
import bt.protocol.crypto.EncryptionPolicy;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.service.IRuntimeLifecycleBinder;
import bt.torrent.selector.PieceSelector;
import bt.torrent.selector.RarestFirstSelector;
import bt.torrent.selector.SequentialSelector;
import com.google.inject.Module;
import joptsimple.OptionException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Objects;
import java.util.Optional;

public class CliClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliClient.class);

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (OptionException e) {
            Options.printHelp(System.out);
            return;
        }

        configureLogging(options.getLogLevel());
        configureSecurity();
        registerLog4jShutdownHook();

        CliClient client = new CliClient(options);
        client.start();
    }

    private static void configureLogging(Options.LogLevel logLevel) {
        Level log4jLogLevel;
        switch (Objects.requireNonNull(logLevel)) {
            case NORMAL: {
                log4jLogLevel = Level.INFO;
                break;
            }
            case VERBOSE: {
                log4jLogLevel = Level.DEBUG;
                break;
            }
            case TRACE: {
                log4jLogLevel = Level.TRACE;
                break;
            }
            default: {
                throw new IllegalArgumentException("Unknown log level: " + logLevel.name());
            }
        }
        Configurator.setLevel("bt", log4jLogLevel);
    }

    private static void configureSecurity() {
        // Starting with JDK 8u152 this is a way to programmatically allow unlimited encryption
        // See http://www.oracle.com/technetwork/java/javase/8u152-relnotes-3850503.html
        String key = "crypto.policy";
        String value = "unlimited";
        try {
            Security.setProperty(key, value);
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to set security property '%s' to '%s'", key, value), e);
        }
    }

    private static void registerLog4jShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if( LogManager.getContext() instanceof LoggerContext) {
                    Configurator.shutdown((LoggerContext)LogManager.getContext());
                }
            }
        });
    }

    private final Options options;
    private final SessionStatePrinter printer;
    private final BtClient client;

    public CliClient(Options options) {
        this.options = options;
        this.printer = new SessionStatePrinter();

        Config config = buildConfig(options);

        BtRuntime runtime = BtRuntime.builder(config)
                .module(buildDHTModule(options))
                .autoLoadModules()
                .build();

        Storage storage = new FileSystemStorage(options.getTargetDirectory().toPath());
        PieceSelector selector = options.downloadSequentially() ?
                SequentialSelector.sequential() : RarestFirstSelector.randomizedRarest();

        BtClientBuilder clientBuilder = Bt.client(runtime)
                .storage(storage)
                .selector(selector);

        if (!options.shouldDownloadAllFiles()) {
            CliFileSelector fileSelector = new CliFileSelector();
            clientBuilder.fileSelector(fileSelector);
            runtime.service(IRuntimeLifecycleBinder.class).onShutdown(fileSelector::shutdown);
        }

        clientBuilder.afterTorrentFetched(printer::onTorrentFetched);
        clientBuilder.afterFilesChosen(printer::onFilesChosen);

        if (options.getMetainfoFile() != null) {
            clientBuilder = clientBuilder.torrent(toUrl(options.getMetainfoFile()));
        } else if (options.getMagnetUri() != null) {
            clientBuilder = clientBuilder.magnet(options.getMagnetUri());
        } else {
            throw new IllegalStateException("Torrent file or magnet URI is required");
        }

        this.client = clientBuilder.build();
    }

    private static Config buildConfig(Options options) {
        Optional<InetAddress> acceptorAddressOverride = getAcceptorAddressOverride(options);
        Optional<Integer> portOverride = tryGetPort(options.getPort());

        return new Config() {
            @Override
            public InetAddress getAcceptorAddress() {
                return acceptorAddressOverride.orElseGet(super::getAcceptorAddress);
            }

            @Override
            public int getAcceptorPort() {
                return portOverride.orElseGet(super::getAcceptorPort);
            }

            @Override
            public int getNumOfHashingThreads() {
                return Runtime.getRuntime().availableProcessors();
            }

            @Override
            public EncryptionPolicy getEncryptionPolicy() {
                return options.enforceEncryption()? EncryptionPolicy.REQUIRE_ENCRYPTED : EncryptionPolicy.PREFER_PLAINTEXT;
            }
        };
    }

    private static Optional<Integer> tryGetPort(Integer port) {
        if (port == null) {
            return Optional.empty();
        } else if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port + "; expected 1024..65535");
        }
        return Optional.of(port);
    }

    private static Optional<InetAddress> getAcceptorAddressOverride(Options options) {
        String inetAddress = options.getInetAddress();
        if (inetAddress == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(inetAddress));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Failed to parse the acceptor's internet address", e);
        }
    }

    private static Module buildDHTModule(Options options) {
        Optional<Integer> dhtPortOverride = tryGetPort(options.getDhtPort());

        return new DHTModule(new DHTConfig() {
            @Override
            public int getListeningPort() {
                return dhtPortOverride.orElseGet(super::getListeningPort);
            }

            @Override
            public boolean shouldUseRouterBootstrap() {
                return true;
            }
        });
    }

    private static URL toUrl(File file) {
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Unexpected error", e);
        }
    }

    private void start() {
        printer.start();
        client.startAsync(state -> {
            boolean complete = (state.getPiecesRemaining() == 0);
            if (complete) {
                if (options.shouldSeedAfterDownloaded()) {
                    printer.onDownloadComplete();
                } else {
                    printer.stop();
                    client.stop();
                }
            }
            printer.updateState(state);
        }, 1000).join();
    }
}
