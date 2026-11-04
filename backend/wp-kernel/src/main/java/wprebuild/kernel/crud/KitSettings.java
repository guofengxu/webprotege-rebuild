package wprebuild.kernel.crud;

/** Which IRI strategy a project uses (BS-06). */
public sealed interface KitSettings permits UuidKitSettings, SuppliedNameKitSettings, OboKitSettings {}
