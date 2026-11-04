package wprebuild.kernel.crud;

import java.util.Objects;

/** Supplied-name suffixes. Camel case is the default whitespace treatment (BS-06). */
public record SuppliedNameKitSettings(WhiteSpaceTreatment whiteSpace) implements KitSettings {

    public SuppliedNameKitSettings {
        Objects.requireNonNull(whiteSpace, "white space treatment");
    }

    public static SuppliedNameKitSettings defaults() {
        return new SuppliedNameKitSettings(WhiteSpaceTreatment.CAMEL_CASE);
    }
}
