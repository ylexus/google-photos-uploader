package net.yudichev.googlephotosupload.ui;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import net.yudichev.googlephotosupload.core.UploadStateManager;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.System.lineSeparator;
import static java.lang.management.ManagementFactory.*;
import static java.time.ZoneOffset.UTC;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static javafx.scene.control.Alert.AlertType.ERROR;
import static javafx.scene.control.Alert.AlertType.INFORMATION;
import static javafx.scene.input.KeyCombination.*;
import static javafx.scene.input.KeyEvent.KEY_RELEASED;
import static net.yudichev.googlephotosupload.core.Bindings.SettingsRoot;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.Runnables.guarded;

final class Diagnostics extends BaseLifecycleComponent {
    private static final DateTimeFormatter DUMP_FILE_NAME_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Logger logger = LoggerFactory.getLogger(Diagnostics.class);

    private final Provider<JavafxApplicationResources> javafxApplicationResourcesProvider;
    private final Path settingsRoot;
    private final UploadStateManager uploadStateManager;
    private final ResourceBundle resourceBundle;

    @Inject
    Diagnostics(Provider<JavafxApplicationResources> javafxApplicationResourcesProvider,
                @SettingsRoot Path settingsRoot,
                UploadStateManager uploadStateManager,
                ResourceBundle resourceBundle) {
        this.javafxApplicationResourcesProvider = checkNotNull(javafxApplicationResourcesProvider);
        this.settingsRoot = checkNotNull(settingsRoot);
        this.uploadStateManager = checkNotNull(uploadStateManager);
        this.resourceBundle = checkNotNull(resourceBundle);
    }

    @Override
    protected void doStart() {
        var primaryStage = javafxApplicationResourcesProvider.get().primaryStage();
        registerThreadDumpHandler(primaryStage);
        registerHeapDumpHandler(primaryStage);
        registerDbUiLauncher(primaryStage);
    }

    private void registerHeapDumpHandler(Stage primaryStage) {
        KeyCombination keyCodeCombination = new KeyCodeCombination(KeyCode.H, CONTROL_DOWN, ALT_DOWN, SHIFT_DOWN);
        primaryStage.addEventHandler(KEY_RELEASED, event -> {
            if (keyCodeCombination.match(event)) {
                new HeapDumps().writeHeapDump()
                        .whenComplete((path, e) -> Platform.runLater(() -> {
                            if (e != null) {
                                logger.error("Failed to write heap dump", e);
                                var alert = new Alert(ERROR, humanReadableMessage(e), ButtonType.OK);
                                alert.setHeaderText(resourceBundle.getString("diagnosticsHeapDumpFailureMessage"));
                                alert.showAndWait();
                            } else {
                                var alert = new Alert(INFORMATION,
                                        String.format(resourceBundle.getString("diagnosticsHeapDumpSuccessMessage"), path.toAbsolutePath()),
                                        ButtonType.OK);
                                alert.setHeaderText("Heap dump");
                                alert.showAndWait();
                            }
                        }));
                event.consume();
            }
        });
    }

    private void registerThreadDumpHandler(Stage primaryStage) {
        KeyCombination keyCodeCombination = new KeyCodeCombination(KeyCode.T, CONTROL_DOWN, ALT_DOWN, SHIFT_DOWN);
        primaryStage.addEventHandler(KEY_RELEASED, event -> {
            if (keyCodeCombination.match(event)) {
                new ThreadDumps().writeSeveralThreadDumpsAsync();
                event.consume();
            }
        });
    }

    private void registerDbUiLauncher(Stage primaryStage) {
        KeyCombination keyCodeCombination = new KeyCodeCombination(KeyCode.D, CONTROL_DOWN, ALT_DOWN, SHIFT_DOWN);
        primaryStage.addEventHandler(KEY_RELEASED, event -> {
            if (keyCodeCombination.match(event)) {
                uploadStateManager.startWebServer();
                event.consume();
            }
        });
    }

    private static String dumpTimestamp() {
        return DUMP_FILE_NAME_DATE_FORMATTER.format(Instant.now().atZone(UTC));
    }

    private final class HeapDumps {
        public CompletableFuture<Path> writeHeapDump() {
            logger.info("Requested heap dump");
            return CompletableFuture.supplyAsync(this::doWriteHeapDump);
        }

        private Path doWriteHeapDump() {
            var baseDir = settingsRoot.resolve("heapdumps");
            asUnchecked(() -> Files.createDirectories(baseDir));
            var dumpFile = baseDir.resolve("heapdump-" + dumpTimestamp() + ".hprof");

            asUnchecked(() -> {
                var clazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
                var mxBean = newPlatformMXBeanProxy(getPlatformMBeanServer(), "com.sun.management:type=HotSpotDiagnostic", clazz);
                clazz.getMethod("dumpHeap", String.class, boolean.class).invoke(mxBean, dumpFile.toAbsolutePath().toString(), true);
            });

            logger.info("Heap dump written to {}", dumpFile);
            return dumpFile;
        }
    }

    private final class ThreadDumps {

        private int count = 5;
        private ScheduledExecutorService scheduledExecutor;

        public void writeSeveralThreadDumpsAsync() {
            logger.info("Requested thread dumps");
            scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setDaemon(true).build());
            scheduledExecutor.scheduleAtFixedRate(guarded(logger, "dump threads", this::writeThreadDump), 0, 5, SECONDS);
        }

        private void writeThreadDump() {
            try {
                var baseDir = settingsRoot.resolve("threaddumps");
                asUnchecked(() -> Files.createDirectories(baseDir));
                var dumpFile = baseDir.resolve("threaddump-" + dumpTimestamp() + ".txt");
                var infos = getThreadMXBean().dumpAllThreads(true, true);
                asUnchecked(() -> Files.writeString(dumpFile, Stream.of(infos).map(Object::toString).collect(joining(lineSeparator()))));
                logger.info("Wrote thread dump to {}", dumpFile);
            } finally {
                if (--count == 0) {
                    scheduledExecutor.shutdown();
                }
            }
        }
    }
}
