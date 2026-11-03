package wprebuild.kernel.codec;

import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Encodes every ontology in a project so a cold start can load it without replaying history.
 * {@link #id()} is stored beside the payload (ADR-011).
 */
public interface SnapshotCodec {

    String id();

    byte[] encode(ProjectOntologies ontologies);

    MutableProjectOntologies decode(byte[] payload);
}
