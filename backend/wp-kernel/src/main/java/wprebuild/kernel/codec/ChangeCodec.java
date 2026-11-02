package wprebuild.kernel.codec;

import java.util.List;
import wprebuild.kernel.change.OntologyChange;

/**
 * Encodes one revision's change list. {@link #id()} is stored beside the payload (ADR-011).
 */
public interface ChangeCodec {

    String id();

    byte[] encode(List<OntologyChange> changes);

    List<OntologyChange> decode(byte[] payload);
}
