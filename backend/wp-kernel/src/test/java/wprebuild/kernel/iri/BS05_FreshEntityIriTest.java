package wprebuild.kernel.iri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;

/** Fresh entity IRI text form, escaping, and round trip (BS-05). */
class BS05_FreshEntityIriTest {

    private static final String DISCRIMINATOR = "12345678-1234-1234-1234-123456789abc";

    private static final String LANG_TAG = "lang-tag";

    private static final String SUPPLIED_NAME = "Supplied Name";

    private static final IRI PARENT_A = IRI.create("http://example.org/ParentIriA");

    private static final IRI PARENT_B = IRI.create("http://example.orf/ParentIriB");

    private final Set<IRI> parentIris = Set.of(PARENT_A, PARENT_B);

    private final FreshEntityIri fresh = new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, parentIris);

    @Test
    void keeps_the_supplied_name_lang_tag_discriminator_and_parent_iris() {
        assertThat(fresh.suppliedName()).isEqualTo(SUPPLIED_NAME);
        assertThat(fresh.langTag()).isEqualTo(LANG_TAG);
        assertThat(fresh.discriminator()).isEqualTo(DISCRIMINATOR);
        assertThat(fresh.parentIris()).containsExactlyInAnyOrder(PARENT_A, PARENT_B);
    }

    @Test
    void rejects_null_parts() {
        assertThatThrownBy(() -> new FreshEntityIri(null, LANG_TAG, DISCRIMINATOR, parentIris))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FreshEntityIri(SUPPLIED_NAME, null, DISCRIMINATOR, parentIris))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, null, parentIris))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FreshEntityIri.isFresh(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FreshEntityIri.parse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void accepts_empty_name_lang_tag_discriminator_and_parents() {
        assertThat(new FreshEntityIri("", LANG_TAG, DISCRIMINATOR, parentIris).suppliedName())
                .isEmpty();
        assertThat(new FreshEntityIri(SUPPLIED_NAME, "", DISCRIMINATOR, parentIris).langTag())
                .isEmpty();
        assertThat(new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, "", parentIris).discriminator())
                .isEmpty();
        assertThat(new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, Set.of()).parentIris())
                .isEmpty();
    }

    @Test
    void copies_parent_iris() {
        Set<IRI> parents = new HashSet<>();
        parents.add(PARENT_A);
        FreshEntityIri parsed = new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, parents);
        parents.add(PARENT_B);

        assertThat(parsed.parentIris()).containsExactly(PARENT_A);
        assertThatThrownBy(() -> parsed.parentIris().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void round_trips_through_the_iri_text() {
        assertRoundTrips(fresh);
        assertRoundTrips(new FreshEntityIri("", LANG_TAG, DISCRIMINATOR, parentIris));
        assertRoundTrips(new FreshEntityIri(SUPPLIED_NAME, "", DISCRIMINATOR, parentIris));
        assertRoundTrips(new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, "", parentIris));
        assertRoundTrips(new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, Set.of()));
        assertRoundTrips(new FreshEntityIri("", "", "", Set.of()));
    }

    @Test
    void round_trips_a_supplied_name_that_contains_a_hash() {
        assertRoundTrips(new FreshEntityIri("The#Supplied#Name", LANG_TAG, DISCRIMINATOR, parentIris));
    }

    @Test
    void escapes_ampersand_and_hash_in_parent_iris() {
        IRI ampersand = IRI.create("http://example.org/A&B");
        IRI hash = IRI.create("http://example.org#A");
        IRI both = IRI.create("http://example.org/A&B#C");
        FreshEntityIri withAmpersand = new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, Set.of(ampersand));
        FreshEntityIri withHash = new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, Set.of(hash));
        FreshEntityIri withBoth = new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, DISCRIMINATOR, Set.of(both));

        assertThat(withAmpersand.toIri().toString()).contains("parentIri=http://example.org/A%26B");
        assertThat(withHash.toIri().toString()).contains("parentIri=http://example.org%23A");
        assertThat(withBoth.toIri().toString()).contains("parentIri=http://example.org/A%26B%23C");
        assertThat(withAmpersand.toIri().toString())
                .startsWith("entity:?langTag=" + LANG_TAG + "&discriminator=" + DISCRIMINATOR);
        assertRoundTrips(withAmpersand);
        assertRoundTrips(withHash);
        assertRoundTrips(withBoth);
        assertRoundTrips(fresh);
    }

    @Test
    void rejects_delimiters_in_the_lang_tag_and_discriminator() {
        assertThatThrownBy(() -> new FreshEntityIri(SUPPLIED_NAME, "en&fr", DISCRIMINATOR, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreshEntityIri(SUPPLIED_NAME, LANG_TAG, "a#b", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recognises_only_the_entity_scheme() {
        assertThat(FreshEntityIri.isFresh(fresh.toIri())).isTrue();
        assertThat(FreshEntityIri.isFresh(IRI.create("entity:placeholder"))).isTrue();
        assertThat(FreshEntityIri.isFresh(IRI.create("http://example.org/A"))).isFalse();
        assertThat(FreshEntityIri.parse(IRI.create("http://example.org/A"))).isEmpty();
        assertThat(FreshEntityIri.parse(IRI.create("entity:placeholder"))).isEmpty();
        assertThat(FreshEntityIri.parse(IRI.create("entity:?langTag=en#missing-discriminator")))
                .isEmpty();
    }

    private static void assertRoundTrips(FreshEntityIri original) {
        assertThat(FreshEntityIri.parse(original.toIri())).contains(original);
    }
}
