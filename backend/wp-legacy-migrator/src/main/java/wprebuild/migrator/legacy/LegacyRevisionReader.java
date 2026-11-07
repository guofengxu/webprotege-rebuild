package wprebuild.migrator.legacy;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.semanticweb.binaryowl.BinaryOWLMetadata;
import org.semanticweb.binaryowl.BinaryOWLOntologyChangeLog;
import org.semanticweb.binaryowl.change.OntologyChangeRecordList;
import org.semanticweb.binaryowl.chunk.SkipSetting;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.change.SetOntologyIDData;
import org.semanticweb.owlapi.model.OWLDataFactory;

/**
 * Reads a legacy BinaryOWL change log one revision at a time. Unlike the old {@code load()}, any
 * failure stops the read and names the project, the last accepted revision, and the file offset.
 */
public final class LegacyRevisionReader {

    static final String USERNAME = "username";
    static final String REVISION = "revision";
    static final String DESCRIPTION = "description";

    private final OWLDataFactory dataFactory;

    public LegacyRevisionReader() {
        this(OWLManager.getOWLDataFactory());
    }

    LegacyRevisionReader(OWLDataFactory dataFactory) {
        this.dataFactory = Objects.requireNonNull(dataFactory, "dataFactory");
    }

    public void read(Path changeDataBinary, Consumer<LegacyRevision> consumer) {
        Objects.requireNonNull(changeDataBinary, "changeDataBinary");
        Objects.requireNonNull(consumer, "consumer");
        String projectId = LegacyDataDir.projectIdOf(changeDataBinary);
        Progress progress = new Progress();
        try (InputStream raw = Files.newInputStream(changeDataBinary);
                PositionInputStream input = new PositionInputStream(new BufferedInputStream(raw))) {
            readChunks(projectId, input, consumer, progress);
        } catch (LegacyReadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(projectId, progress.lastRevision, progress.offset, exception);
        }
    }

    private void readChunks(
            String projectId, PositionInputStream input, Consumer<LegacyRevision> consumer, Progress progress) {
        try {
            new BinaryOWLOntologyChangeLog()
                    .readChanges(
                            input,
                            dataFactory,
                            (list, skipSetting, ignoredOffset) ->
                                    accept(projectId, list, input.position(), consumer, progress),
                            SkipSetting.SKIP_NONE);
            // BinaryOWL's callback offset double-counts bulk reads, so it is not a file position.
            // A cut that lands exactly on a chunk boundary is a normal EOF: the format has no trailer.
            if (input.lastRead() != -1) {
                throw new LegacyReadException(
                        projectId,
                        progress.lastRevision,
                        input.position(),
                        "change log ended on a non-chunk marker",
                        null);
            }
            progress.offset = input.position();
        } catch (LegacyReadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(projectId, progress.lastRevision, input.position(), exception);
        }
    }

    private static void accept(
            String projectId,
            OntologyChangeRecordList list,
            long offset,
            Consumer<LegacyRevision> consumer,
            Progress progress) {
        BinaryOWLMetadata metadata = list.getMetadata();
        if (metadata == null) {
            throw new LegacyReadException(
                    projectId, progress.lastRevision, offset, "revision metadata is missing", null);
        }
        long revisionNumber = revisionAttribute(projectId, metadata, offset, progress);
        List<OWLOntologyChangeRecord> records = list.getChangeRecords();
        if (records == null) {
            throw new LegacyReadException(
                    projectId,
                    progress.lastRevision,
                    offset,
                    "revision " + revisionNumber + " has no change records",
                    null);
        }
        for (int index = 0; index < records.size(); index++) {
            OWLOntologyChangeRecord record = records.get(index);
            if (record == null || record.getData() == null) {
                throw new LegacyReadException(
                        projectId,
                        progress.lastRevision,
                        offset,
                        "revision " + revisionNumber + " changeIndex=" + index + " is missing",
                        null);
            }
            if (record.getData() instanceof SetOntologyIDData) {
                throw new UnsupportedLegacyChangeException(
                        projectId, revisionNumber, index, progress.lastRevision, offset);
            }
        }
        String username = stringAttribute(metadata, USERNAME);
        String description = stringAttribute(metadata, DESCRIPTION);
        LegacyRevision revision =
                new LegacyRevision(revisionNumber, username, list.getTimestamp(), description, records);
        progress.lastRevision = revision.revision();
        consumer.accept(revision);
    }

    private static long revisionAttribute(
            String projectId, BinaryOWLMetadata metadata, long offset, Progress progress) {
        Long value = metadata.getLongAttribute(REVISION, null);
        if (value == null) {
            throw new LegacyReadException(
                    projectId, progress.lastRevision, offset, "revision attribute is missing", null);
        }
        return value;
    }

    private static String stringAttribute(BinaryOWLMetadata metadata, String name) {
        String value = metadata.getStringAttribute(name, "");
        return value == null ? "" : value;
    }

    private static LegacyReadException failure(String projectId, Long lastRevision, long offset, Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = exception.getClass().getSimpleName();
        } else {
            detail = exception.getClass().getSimpleName() + ": " + detail;
        }
        return new LegacyReadException(projectId, lastRevision, offset, detail, exception);
    }

    private static final class Progress {
        private Long lastRevision;
        private long offset;
    }

    /**
     * Counts bytes actually pulled from the log and remembers the last single-byte read. BinaryOWL
     * ends a log by reading one marker byte, so that byte distinguishes EOF from trailing junk.
     */
    private static final class PositionInputStream extends FilterInputStream {

        private long position;
        private int lastRead = -1;

        private PositionInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            lastRead = value;
            if (value >= 0) {
                position++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count > 0) {
                position += count;
            }
            return count;
        }

        private long position() {
            return position;
        }

        private int lastRead() {
            return lastRead;
        }
    }
}
