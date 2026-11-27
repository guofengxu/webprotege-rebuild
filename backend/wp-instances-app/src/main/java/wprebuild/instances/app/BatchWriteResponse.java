package wprebuild.instances.app;

import java.time.Instant;

/** Success summary for a batch write (P6-59). */
record BatchWriteResponse(int applied, Instant at, long ontologyRev, String source) {}
