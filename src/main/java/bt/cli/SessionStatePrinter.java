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

import bt.metainfo.Torrent;
import bt.torrent.TorrentSessionState;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SessionStatePrinter {
    public enum ProcessingStage {
        FETCHING_METADATA, CHOOSING_FILES, DOWNLOADING, SEEDING
    }

    private AtomicReference<Torrent> torrent;
    private AtomicReference<TorrentSessionState> sessionState;
    private AtomicReference<ProcessingStage> processingStage;
    private AtomicBoolean shutdown;

    private long started;
    private long downloaded;
    private long uploaded;

    public SessionStatePrinter() {
        this.torrent = new AtomicReference<>(null);
        this.sessionState = new AtomicReference<>(null);
        this.processingStage = new AtomicReference<>(ProcessingStage.FETCHING_METADATA);
        this.shutdown = new AtomicBoolean(false);
    }

    public void updateState(TorrentSessionState sessionState) {
        this.sessionState.set(sessionState);
    }

    public void onTorrentFetched(Torrent torrent) {
        System.out.println(String.format("Downloading %s (%,d B)", torrent.getName(), torrent.getSize()));
        this.torrent.set(torrent);
        this.processingStage.set(ProcessingStage.CHOOSING_FILES);
    }

    public void onFilesChosen() {
        this.processingStage.set(ProcessingStage.DOWNLOADING);
    }

    public void onDownloadComplete() {
        this.processingStage.set(ProcessingStage.SEEDING);
    }

    public void start() {
        this.started = System.currentTimeMillis();

        System.out.println("Fetching metadata... Please wait");

        Thread t = new Thread(() -> {
            do {
                TorrentSessionState sessionState = this.sessionState.get();
                if (sessionState != null) {
                    Duration elapsedTime = getElapsedTime();
                    Rate downloadRate = new Rate(sessionState.getDownloaded() - this.downloaded);
                    Rate uploadRate = new Rate(sessionState.getUploaded() - this.uploaded);

                    print(torrent.get(), sessionState, processingStage.get(), new Stats(elapsedTime, downloadRate, uploadRate));

                    this.downloaded = sessionState.getDownloaded();
                    this.uploaded = sessionState.getUploaded();
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            } while (!shutdown.get());
        });
        t.setDaemon(true);
        t.start();
    }

    private Duration getElapsedTime() {
        return Duration.ofMillis(System.currentTimeMillis() - started);
    }

    private static final String FORMAT_D = "Downloading from %3d peers... Ready: %.2f%%, Target: %.2f%%,  Down: %s, Up: %s, Elapsed: %s, Remaining: %s";
    private static final String FORMAT_S = "Download is complete, seeding to %d peers... Up: %s";

    private static void print(Torrent torrent, TorrentSessionState sessionState, ProcessingStage stage, Stats stats) {
        int peerCount = sessionState.getConnectedPeers().size();

        switch (stage) {
            case DOWNLOADING: {
                double completePercents = getCompletePercentage(sessionState.getPiecesTotal(), sessionState.getPiecesComplete());
                double requiredPercents = getTargetPercentage(sessionState.getPiecesTotal(),
                        sessionState.getPiecesComplete(), sessionState.getPiecesRemaining());
                String down = formatRate(stats.getDownloadRate());
                String up = formatRate(stats.getUploadRate());
                String elapsedTime = formatDuration(stats.getElapsedTime());
                String remainingTime = formatTime(getRemainingTime(torrent.getChunkSize(),
                        stats.getDownloadRate().getBytes(), sessionState.getPiecesRemaining()));

                System.out.println(String.format(FORMAT_D, peerCount, completePercents, requiredPercents, down, up, elapsedTime, remainingTime));
                break;
            }
            case SEEDING: {
                String up = formatRate(stats.getUploadRate());
                System.out.println(String.format(FORMAT_S, peerCount, up));
                break;
            }
            default: {
                // ignore
                break;
            }
        }
    }

    private static String formatTime(int remainingSeconds) {
        if (remainingSeconds < 0) {
            return "\u221E"; // infinity
        } else {
            return formatDuration(Duration.ofSeconds(remainingSeconds));
        }
    }

    private static double getCompletePercentage(double total, double completed) {
        return completed / total * 100;
    }

    private static double getTargetPercentage(double total, double completed, double remaining) {
        return (completed + remaining) / total * 100;
    }

    private static String formatRate(Rate rate) {
        return String.format("%5.1f %2s/s", rate.getQuantity(), rate.getMeasureUnit());
    }

    // returns number of seconds or -1 if the remaining time can't be calculated
    private static int getRemainingTime(long chunkSize, long downloaded, int piecesRemaining) {
        if (downloaded == 0) {
            return -1;
        }
        long remainingBytes = chunkSize * piecesRemaining;
        return (int) (remainingBytes / downloaded);
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long absSeconds = Math.abs(seconds);
        String positive = String.format("%d:%02d:%02d", absSeconds / 3600, (absSeconds % 3600) / 60, absSeconds % 60);
        return seconds < 0 ? "-" + positive : positive;
    }

    public void stop() {
        shutdown.set(true);
    }
}

class Stats {
    private final Duration elapsedTime;
    private final Rate downloadRate;
    private final Rate uploadRate;

    public Stats(Duration elapsedTime, Rate downloadRate, Rate uploadRate) {
        this.elapsedTime = elapsedTime;
        this.downloadRate = downloadRate;
        this.uploadRate = uploadRate;
    }

    public Duration getElapsedTime() {
        return elapsedTime;
    }

    public Rate getDownloadRate() {
        return downloadRate;
    }

    public Rate getUploadRate() {
        return uploadRate;
    }
}

class Rate {
    private final double quantity;
    private final String measureUnit;
    private final long bytes;

    Rate(long delta) {
        if (delta < 0) {
            delta = 0;
            quantity = 0;
            measureUnit = "B";
        } else if (delta < (1 << 10)) {
            quantity = delta;
            measureUnit = "B";
        } else if (delta < (1 << 20)) {
            quantity = delta / (1 << 10);
            measureUnit = "KB";
        } else {
            quantity = ((double) delta) / (1 << 20);
            measureUnit = "MB";
        }
        bytes = delta;
    }

    public long getBytes() {
        return bytes;
    }

    public double getQuantity() {
        return quantity;
    }

    public String getMeasureUnit() {
        return measureUnit;
    }
}
