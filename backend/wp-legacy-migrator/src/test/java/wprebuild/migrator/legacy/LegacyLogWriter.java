package wprebuild.migrator.legacy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import org.semanticweb.binaryowl.BinaryOWLMetadata;
import org.semanticweb.binaryowl.BinaryOWLOntologyChangeLog;
import org.semanticweb.binaryowl.change.OntologyChangeRecordList;

/**
 * Writes a change log the way {@code RevisionSerializationTask} did: one
 * {@link OntologyChangeRecordList} per revision, appended with the same metadata attribute names.
 */
public final class LegacyLogWriter {

    static final String USERNAME = "username";
    static final String REVISION = "revision";
    static final String DESCRIPTION = "description";
    static final String REVISION_TYPE = "revisionType";
    static final String EDIT = "EDIT";

    public void append(Path changeDataBinary, LegacyRevision revision) throws IOException {
        Objects.requireNonNull(changeDataBinary, "changeDataBinary");
        Objects.requireNonNull(revision, "revision");
        BinaryOWLMetadata metadata = new BinaryOWLMetadata();
        metadata.setStringAttribute(USERNAME, revision.username());
        metadata.setLongAttribute(REVISION, revision.revision());
        metadata.setStringAttribute(DESCRIPTION, revision.description());
        metadata.setStringAttribute(REVISION_TYPE, EDIT);
        OntologyChangeRecordList chunk =
                new OntologyChangeRecordList(revision.timestamp(), metadata, revision.records());
        new BinaryOWLOntologyChangeLog().appendChanges(chunk, changeDataBinary.toFile());
    }
}
