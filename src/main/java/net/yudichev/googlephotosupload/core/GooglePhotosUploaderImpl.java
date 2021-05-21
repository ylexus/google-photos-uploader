package net.yudichev.googlephotosupload.core;

import com.google.common.collect.ImmutableList;
import com.google.rpc.Code;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.ResultOrFailure;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.google.photos.GooglePhotosAlbum;
import net.yudichev.jiotty.connector.google.photos.GooglePhotosClient;
import net.yudichev.jiotty.connector.google.photos.NewMediaItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.util.Comparator.comparing;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.toConcurrentMap;
import static net.yudichev.googlephotosupload.core.Bindings.Backpressured;
import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static net.yudichev.jiotty.common.lang.ResultOrFailure.failure;
import static net.yudichev.jiotty.common.lang.ResultOrFailure.success;

final class GooglePhotosUploaderImpl extends BaseLifecycleComponent implements GooglePhotosUploader {
    public static final int GOOGLE_PHOTOS_API_BATCH_SIZE = 50;

    @SuppressWarnings("NonConstantLogger") // as designed
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final CloudOperationHelper cloudOperationHelper;
    private final AddToAlbumStrategy addToAlbumStrategy;
    private final FatalUserCorrectableRemoteApiExceptionHandler fatalUserCorrectableHandler;
    private final GooglePhotosClient googlePhotosClient;
    private final UploadStateManager uploadStateManager;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Provider<ExecutorService> executorServiceProvider;
    private final BackingOffRemoteApiExceptionHandler backOffHandler;

    // memory barrier for access to non-finals (as we must survive a restart), but should NOT be used to guard internal state of any other objects
    private volatile boolean memoryBarrier = true;

    private ExecutorService executorService;
    private Map<Path, CompletableFuture<ItemState>> uploadedItemStateByPath;
    private boolean requestedToForgetUploadStateOnShutdown;

    @Inject
    GooglePhotosUploaderImpl(GooglePhotosClient googlePhotosClient,
                             @Backpressured Provider<ExecutorService> executorServiceProvider,
                             BackingOffRemoteApiExceptionHandler backOffHandler,
                             FatalUserCorrectableRemoteApiExceptionHandler fatalUserCorrectableHandler,
                             UploadStateManager uploadStateManager,
                             CurrentDateTimeProvider currentDateTimeProvider,
                             CloudOperationHelper cloudOperationHelper,
                             AddToAlbumStrategy addToAlbumStrategy) {
        this.executorServiceProvider = checkNotNull(executorServiceProvider);
        this.backOffHandler = checkNotNull(backOffHandler);
        this.fatalUserCorrectableHandler = checkNotNull(fatalUserCorrectableHandler);
        this.googlePhotosClient = checkNotNull(googlePhotosClient);
        this.uploadStateManager = checkNotNull(uploadStateManager);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.cloudOperationHelper = checkNotNull(cloudOperationHelper);
        this.addToAlbumStrategy = checkNotNull(addToAlbumStrategy);
    }

    @Override
    public CompletableFuture<Void> uploadDirectory(Optional<GooglePhotosAlbum> googlePhotosAlbum,
                                                   List<Path> files,
                                                   ProgressStatus directoryProgressStatus,
                                                   ProgressStatus fileProgressStatus) {
        checkStarted();

        return supplyAsync(() -> files, executorService)
                .thenCompose(paths -> {
                    directoryProgressStatus.updateDescription(googlePhotosAlbum.map(GooglePhotosAlbum::getTitle).orElse(""));
                    var createMediaDataResultsFuture = paths.stream()
                            .sorted(comparing(path -> path.getFileName().toString()))
                            .map(path -> {
                                fileProgressStatus.updateDescription(path.toAbsolutePath().toString());
                                return createMediaData(path)
                                        .thenApply(itemState -> {
                                            itemState.toFailure().ifPresentOrElse(
                                                    error -> fileProgressStatus.addFailure(KeyedError.of(path, error)),
                                                    fileProgressStatus::incrementSuccess);
                                            return PathState.of(path, itemState);
                                        });
                            })
                            .collect(toFutureOfList());
                    return addToAlbumStrategy.addToAlbum(
                            createMediaDataResultsFuture,
                            googlePhotosAlbum,
                            fileProgressStatus,
                            (albumId, pathStates) -> createMediaItems(albumId, fileProgressStatus, pathStates),
                            this::getItemState);
                });
    }

    @Override
    public void doNotResume() {
        checkStarted();
        forgetUploadState();
    }

    @Override
    public void forgetUploadStateOnShutdown() {
        requestedToForgetUploadStateOnShutdown = true;
        memoryBarrier = true;
    }

    @Override
    protected void doStart() {
        executorService = executorServiceProvider.get();
        uploadedItemStateByPath = uploadStateManager.loadUploadedMediaItemIdByAbsolutePath().entrySet().stream()
                .collect(toConcurrentMap(
                        entry -> Paths.get(entry.getKey()),
                        entry -> completedFuture(entry.getValue())));
        memoryBarrier = true;
    }

    @Override
    protected void doStop() {
        checkState(memoryBarrier);
        if (requestedToForgetUploadStateOnShutdown) {
            forgetUploadState();
            requestedToForgetUploadStateOnShutdown = false;
        }
        memoryBarrier = true;
    }

    private ItemState getItemState(Path path) {
        var itemState = uploadedItemStateByPath.get(path).getNow(null);
        checkState(itemState != null, "item state future must be completed");
        return itemState;
    }

    /**
     * @return list of items that were successfully created, which may be less in size than the specified
     * {@code createMediaDataResults}
     */
    private CompletableFuture<List<PathMediaItemOrError>> createMediaItems(Optional<String> albumId,
                                                                           ProgressStatus fileProgressStatus,
                                                                           List<PathState> createMediaDataResults) {
        checkState(memoryBarrier);
        List<PathState> pendingPathStates = createMediaDataResults.stream()
                .filter(pathState -> {
                    var itemStateOptional = pathState.state().toSuccess();
                    return itemStateOptional.isPresent() &&
                            itemStateOptional.get().mediaId().isEmpty();
                })
                .collect(toImmutableList());
        if (pendingPathStates.isEmpty()) {
            return completedFuture(ImmutableList.of());
        }

        List<NewMediaItem> pendingNewMediaItems = pendingPathStates.stream()
                .map(pathState -> NewMediaItem.builder()
                        .setUploadToken(pathState.state().toSuccess().get().uploadState().get().token())
                        .setFileName(pathState.path().getFileName().toString())
                        .build())
                .collect(toImmutableList());
        return cloudOperationHelper.withBackOffAndRetry(
                "create media items",
                () -> googlePhotosClient.createMediaItems(albumId, pendingNewMediaItems, executorService),
                fileProgressStatus::onBackoffDelay)
                .<List<PathMediaItemOrError>>thenApply(mediaItemOrErrors -> {
                    ImmutableList.Builder<PathMediaItemOrError> resultListBuilder = ImmutableList.builder();
                    for (var i = 0; i < pendingPathStates.size(); i++) {
                        var pathState = pendingPathStates.get(i);
                        var mediaItemOrError = mediaItemOrErrors.get(i);
                        mediaItemOrError.errorStatus().ifPresent(status -> fileProgressStatus.addFailure(
                                KeyedError.of(pathState.path(), Code.forNumber(status.getCode()) + ": " + status.getMessage())));
                        mediaItemOrError.item().ifPresent(item -> {
                            uploadedItemStateByPath.compute(pathState.path(),
                                    (path, itemStateFuture) -> checkNotNull(itemStateFuture).thenApply(itemState -> {
                                        var newItemState = itemState.withMediaId(item.getId());
                                        uploadStateManager.saveItemState(path, newItemState);
                                        return newItemState;
                                    }));
                            resultListBuilder.add(PathMediaItemOrError.of(pathState.path(), item));
                        });
                    }
                    return resultListBuilder.build();
                })
                .exceptionally(throwable -> fatalUserCorrectableHandler.handle("create media items", throwable)
                        .map(errorMessage -> {
                            //noinspection Convert2MethodRef compiler fails for some reason
                            pendingPathStates.stream()
                                    .map(PathState::path)
                                    .map(path -> KeyedError.of(path, errorMessage))
                                    .forEach(keyedError -> fileProgressStatus.addFailure(keyedError));
                            return ImmutableList.<PathMediaItemOrError>of();
                        })
                        .orElseThrow(() -> new RuntimeException(throwable)));
    }

    private void forgetUploadState() {
        checkState(memoryBarrier);
        logger.info("Was asked not to resume - forgetting {} previously uploaded item(s)", uploadedItemStateByPath.size());
        uploadStateManager.forgetState();
        uploadedItemStateByPath.clear();
        memoryBarrier = true;
    }

    private CompletableFuture<ResultOrFailure<ItemState>> createMediaData(Path file) {
        checkStarted();
        checkState(memoryBarrier);
        return uploadedItemStateByPath.compute(file,
                (theFile, currentFuture) -> {
                    if (currentFuture == null || currentFuture.isCompletedExceptionally()) {
                        logger.info("Scheduling upload of {}", file);
                        currentFuture = doCreateMediaData(theFile);
                    } else {
                        var itemState = currentFuture.getNow(null);
                        if (itemState != null) {
                            currentFuture = itemState.uploadState()
                                    .filter(uploadMediaItemState -> {
                                        if (itemState.mediaId().isPresent()) {
                                            return true;
                                        }
                                        return uploadTokenNotExpired(file, uploadMediaItemState);
                                    })
                                    .map(uploadMediaItemState -> {
                                        logger.info("Already uploaded, skipping: {}", file);
                                        return completedFuture(itemState);
                                    })
                                    .orElseGet(() -> {
                                        logger.info("Uploaded, but upload token expired, re-uploading: {}", file);
                                        return doCreateMediaData(theFile);
                                    });
                        } else {
                            logger.error("Unexpected future state for {}: {}", file, currentFuture);
                        }
                    }
                    return currentFuture;
                })
                .thenApply(itemState -> {
                    checkState(memoryBarrier);
                    uploadStateManager.saveItemState(file, itemState);
                    backOffHandler.reset();
                    return success(itemState);
                })
                .exceptionallyCompose(exception -> {
                    var operationName = "uploading file " + file;
                    return fatalUserCorrectableHandler.handle(operationName, exception)
                            .<CompletableFuture<ResultOrFailure<ItemState>>>map(errorMessage -> completedFuture(failure(errorMessage)))
                            .orElseGet(() -> {
                                if (backOffHandler.handle(operationName, exception).isPresent()) {
                                    logger.debug("Retrying upload of {}", file);
                                    return createMediaData(file);
                                } else {
                                    throw new RuntimeException(exception);
                                }
                            });
                });
    }

    private boolean uploadTokenNotExpired(Path file, UploadMediaItemState uploadMediaItemState) {
        var expiry = uploadMediaItemState.uploadInstant().plus(23, HOURS);
        var notExpired = expiry.isAfter(currentDateTimeProvider.currentInstant());
        if (!notExpired) {
            logger.debug("upload token for {} expired, forgetting: {}", file, uploadMediaItemState);
        }
        return notExpired;
    }

    private CompletableFuture<ItemState> doCreateMediaData(Path file) {
        return googlePhotosClient.uploadMediaData(file, executorService)
                .thenApply(uploadToken -> {
                    logger.info("Uploaded file {}", file);
                    logger.debug("Upload token {}", uploadToken);
                    return ItemState.builder()
                            .setUploadState(UploadMediaItemState.of(uploadToken, currentDateTimeProvider.currentInstant()))
                            .build();
                });
    }
}
