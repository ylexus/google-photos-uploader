package net.yudichev.googlephotosupload.core;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.commons.cli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;

final class IntegrationTestUploadStarter extends BaseLifecycleComponent {
    private static final Logger logger = LoggerFactory.getLogger(IntegrationTestUploadStarter.class);

    private static final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private static final AtomicBoolean forgetUploadStateOnShutdown = new AtomicBoolean();
    private final Path rootDir;
    private final Uploader uploader;
    private final ApplicationLifecycleControl applicationLifecycleControl;
    private final boolean resume;

    @Inject
    IntegrationTestUploadStarter(CommandLine commandLine,
                                 Uploader uploader,
                                 ApplicationLifecycleControl applicationLifecycleControl) {
        rootDir = Paths.get(commandLine.getOptionValue('r'));
        resume = !commandLine.hasOption('n');
        this.uploader = checkNotNull(uploader);
        this.applicationLifecycleControl = checkNotNull(applicationLifecycleControl);
    }

    public static Optional<Throwable> getLastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    public static void forgetUploadStateOnShutdown() {
        forgetUploadStateOnShutdown.set(true);
    }

    @Override
    protected void doStart() {
        uploader.upload(ImmutableList.of(rootDir), resume)
                .whenComplete(logErrorOnFailure(logger, "Failed"))
                .whenComplete((aVoid, throwable) -> {
                    lastFailure.set(throwable);
                    if (forgetUploadStateOnShutdown.getAndSet(false)) {
                        uploader.forgetUploadState();
                    }
                    applicationLifecycleControl.initiateShutdown();
                });
    }
}
